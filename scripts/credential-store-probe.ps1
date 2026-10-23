param(
  [ValidateSet('Parent', 'Pending', 'Ready', 'Locked')][string]$Mode = 'Parent',
  [string]$FixtureId
)
$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'This isolated probe requires Windows.' }
if ($Mode -eq 'Parent') { $FixtureId = [Guid]::NewGuid().ToString('N') }
if ($FixtureId -notmatch '^[a-f0-9]{32}$') { throw 'Invalid probe namespace.' }
$target = "AnimeOnCraft/probe/$FixtureId"
$mutexName = "Global\AnimeOnCraft.Probe.$FixtureId"

# Only this randomly named fixture is accessed; existing credentials are never enumerated.
if (-not ('AocCredentialProbe' -as [type])) {
  Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
public static class AocCredentialProbe {
  [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
  private struct Credential {
    public uint Flags, Type;
    public string TargetName, Comment;
    public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
    public uint CredentialBlobSize;
    public IntPtr CredentialBlob;
    public uint Persist, AttributeCount;
    public IntPtr Attributes;
    public string TargetAlias, UserName;
  }
  [DllImport("advapi32.dll", EntryPoint="CredWriteW", CharSet=CharSet.Unicode, SetLastError=true)]
  private static extern bool WriteNative(ref Credential value, uint flags);
  [DllImport("advapi32.dll", EntryPoint="CredReadW", CharSet=CharSet.Unicode, SetLastError=true)]
  private static extern bool ReadNative(string target, uint type, uint flags, out IntPtr result);
  [DllImport("advapi32.dll", EntryPoint="CredDeleteW", CharSet=CharSet.Unicode, SetLastError=true)]
  private static extern bool DeleteNative(string target, uint type, uint flags);
  [DllImport("advapi32.dll")] private static extern void CredFree(IntPtr buffer);
  private static void Guard(string target) {
    if (!System.Text.RegularExpressions.Regex.IsMatch(target, @"\AAnimeOnCraft/probe/[a-f0-9]{32}\z"))
      throw new ArgumentException("Invalid fixture target");
  }
  public static void Write(string target, byte[] blob) {
    Guard(target);
    if (blob.Length > 2048) throw new ArgumentException("Fixture blob exceeds application bound");
    IntPtr bytes = Marshal.AllocHGlobal(blob.Length);
    try {
      Marshal.Copy(blob, 0, bytes, blob.Length);
      var value = new Credential { Type=1, TargetName=target, UserName="fixture",
        CredentialBlobSize=(uint)blob.Length, CredentialBlob=bytes, Persist=2 };
      if (!WriteNative(ref value, 0)) throw new Win32Exception(Marshal.GetLastWin32Error());
    } finally {
      for (int i=0; i<blob.Length; ++i) Marshal.WriteByte(bytes, i, 0);
      Marshal.FreeHGlobal(bytes);
    }
  }
  public static byte[] Read(string target) {
    Guard(target);
    IntPtr pointer;
    if (!ReadNative(target, 1, 0, out pointer)) {
      int error=Marshal.GetLastWin32Error();
      if (error==1168) return null;
      throw new Win32Exception(error);
    }
    try {
      var value=(Credential)Marshal.PtrToStructure(pointer, typeof(Credential));
      if (value.Type!=1 || value.Persist!=2 || value.CredentialBlobSize>2048)
        throw new InvalidOperationException("Unexpected fixture metadata");
      byte[] result=new byte[value.CredentialBlobSize];
      Marshal.Copy(value.CredentialBlob, result, 0, result.Length);
      return result;
    } finally { CredFree(pointer); }
  }
  public static void Delete(string target) {
    Guard(target);
    if (!DeleteNative(target, 1, 0)) {
      int error=Marshal.GetLastWin32Error();
      if (error!=1168) throw new Win32Exception(error);
    }
  }
}
'@
}
function Assert-Blob([string]$Expected) {
  $blob = [AocCredentialProbe]::Read($target)
  if ($null -eq $blob -or [Text.Encoding]::UTF8.GetString($blob) -cne $Expected) {
    throw 'Fixture read-back mismatch.'
  }
}
$readyOld = '{"format":1,"state":"READY","refresh":"synthetic-old-not-a-token"}'
$pending = '{"format":1,"state":"PENDING","operation":"fixture"}'
$readyNew = '{"format":1,"state":"READY","refresh":"synthetic-new-not-a-token"}'
if ($Mode -eq 'Pending') { Assert-Blob $pending; Write-Output 'pending-requires-login'; exit 0 }
if ($Mode -eq 'Ready') { Assert-Blob $readyNew; Write-Output 'new-ready-visible'; exit 0 }
if ($Mode -eq 'Locked') {
  $childMutex = [Threading.Mutex]::new($false, $mutexName)
  try {
    if ($childMutex.WaitOne(0)) { $childMutex.ReleaseMutex(); throw 'Second process acquired held fixture lock.' }
    Write-Output 'second-process-blocked'
  } finally { $childMutex.Dispose() }
  exit 0
}

$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$toolsRoot = [IO.Path]::GetFullPath((Join-Path $workspace '.tools'))
$probeRoot = [IO.Path]::GetFullPath((Join-Path $toolsRoot "credential-probe-$FixtureId"))
if (-not $probeRoot.StartsWith($toolsRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
  throw 'Probe output escaped workspace.'
}
New-Item -ItemType Directory -Path $probeRoot | Out-Null
$shellPath = (Get-Process -Id $PID).Path
$mutex = [Threading.Mutex]::new($false, $mutexName)
$owned = $false
$fixtureOwned = $false
function Invoke-Child([string]$ChildMode, [string]$Expected) {
  $output = Join-Path $probeRoot "$ChildMode.out"
  $errorOutput = Join-Path $probeRoot "$ChildMode.err"
  $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $PSCommandPath + '"'), '-Mode', $ChildMode, '-FixtureId', $FixtureId)
  $child = Start-Process -FilePath $shellPath -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput $output -RedirectStandardError $errorOutput
  try {
    $null = $child.Handle
    if (-not $child.WaitForExit(30000)) { Stop-Process -Id $child.Id; throw 'Fixture child timed out.' }
    if ($child.ExitCode -ne 0 -or (Get-Content -LiteralPath $output -Raw).Trim() -cne $Expected) {
      $diagnostic = Get-Content -LiteralPath $errorOutput -Raw
      throw "Fixture child $ChildMode failed (exit $($child.ExitCode)): $diagnostic"
    }
  } finally { $child.Dispose() }
}
try {
  $owned = $mutex.WaitOne(0)
  if (-not $owned -or $null -ne [AocCredentialProbe]::Read($target)) { throw 'Fixture namespace collision.' }
  $fixtureOwned = $true
  [AocCredentialProbe]::Write($target, [Text.Encoding]::UTF8.GetBytes($readyOld))
  Assert-Blob $readyOld
  [AocCredentialProbe]::Write($target, [Text.Encoding]::UTF8.GetBytes($pending))
  Assert-Blob $pending
  Invoke-Child 'Pending' 'pending-requires-login'
  [AocCredentialProbe]::Write($target, [Text.Encoding]::UTF8.GetBytes($readyNew))
  Assert-Blob $readyNew
  Invoke-Child 'Ready' 'new-ready-visible'
  Invoke-Child 'Locked' 'second-process-blocked'
  Write-Output 'PASS: Windows credential create/replace/read across processes; pending marker; global lock exclusion.'
} finally {
  if ($fixtureOwned) {
    [AocCredentialProbe]::Delete($target)
    if ($null -ne [AocCredentialProbe]::Read($target)) { throw 'Fixture credential cleanup failed.' }
  }
  if ($owned) { $mutex.ReleaseMutex() }
  $mutex.Dispose()
  # The canonical absolute directory above was checked before creation; remove only own files.
  foreach ($file in Get-ChildItem -LiteralPath $probeRoot -File) { Remove-Item -LiteralPath $file.FullName }
  Remove-Item -LiteralPath $probeRoot
  Write-Output 'PASS: fixture credential absent; own output removed.'
}
