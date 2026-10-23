param(
 [string]$Server='193.178.158.117',
 [string]$KeyPath=(Join-Path $env:USERPROFILE '.ssh\animeoncraft-maatrix'),
 [string]$Destination=(Join-Path ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))) '.tools\offsite-backups')
)
$ErrorActionPreference='Stop'
if($env:OS -ne 'Windows_NT'){throw 'Use Windows PowerShell'}
if($Server -notmatch '^[a-zA-Z0-9.-]+$'){throw 'Provide a host without paths, ports or credentials'}
if(-not(Test-Path -LiteralPath $KeyPath -PathType Leaf)){throw 'Existing SSH key required; do not generate a replacement'}
$folder=[IO.Path]::GetFullPath((Join-Path $Destination 'AnimeOnCraft-private-backups'))
if(Test-Path -LiteralPath $folder){
 if((Get-Item -LiteralPath $folder -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Backup folder cannot be a link'}
}else{New-Item -ItemType Directory -Path $folder -Force | Out-Null}
# Only the current Windows user and SYSTEM may read these server-private archives.
$acl=New-Object System.Security.AccessControl.DirectorySecurity
$acl.SetAccessRuleProtection($true,$false)
$inherit=[System.Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit'
$propagation=[System.Security.AccessControl.PropagationFlags]::None
$rights=[System.Security.AccessControl.FileSystemRights]::FullControl
$allow=[System.Security.AccessControl.AccessControlType]::Allow
foreach($sid in @([System.Security.Principal.WindowsIdentity]::GetCurrent().User,(New-Object System.Security.Principal.SecurityIdentifier 'S-1-5-18'))){
 $acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($sid,$rights,$inherit,$propagation,$allow)))
}
Set-Acl -LiteralPath $folder -AclObject $acl
$options=@('-i',[IO.Path]::GetFullPath($KeyPath),'-o','IdentitiesOnly=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=15')
$json=(& ssh @options "root@$Server" 'cat /srv/animeoncraft/.tools/backup-exports/latest.json') -join "`n"
if($LASTEXITCODE -ne 0){throw 'Could not read remote backup metadata; check SSH/VPN and enter key passphrase locally'}
$report=$json | ConvertFrom-Json
if($report.format_version -ne 1 -or $report.filename -notmatch '^friends-[0-9TZ_-]+-[a-f0-9]{8}\.tar\.gz$' -or $report.sha256 -notmatch '^[a-f0-9]{64}$' -or $report.bytes -le 0){throw 'Invalid backup metadata'}
$final=Join-Path $folder $report.filename
if(Test-Path -LiteralPath $final){
 if((Get-Item -LiteralPath $final -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Backup file cannot be a link'}
 if((Get-FileHash -LiteralPath $final -Algorithm SHA256).Hash.ToLowerInvariant() -ne $report.sha256){throw 'Existing local archive differs; preserve it for inspection'}
 Write-Output "Verified backup already exists: $final"
 exit 0
}
$partial=$final+'.'+[Guid]::NewGuid().ToString('N')+'.part'
& scp @options "root@${Server}:/srv/animeoncraft/.tools/backup-exports/$($report.filename)" $partial
if($LASTEXITCODE -ne 0){throw 'Backup download interrupted; partial file preserved; server unchanged'}
if((Get-Item -LiteralPath $partial).Length -ne $report.bytes -or (Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash.ToLowerInvariant() -ne $report.sha256){throw 'Downloaded backup checksum mismatch; partial file preserved'}
if(Test-Path -LiteralPath $final){throw 'Archive appeared during download; preserve both files'}
Move-Item -LiteralPath $partial -Destination $final
[IO.File]::WriteAllText($final+'.json',($report | ConvertTo-Json -Depth 4),(New-Object Text.UTF8Encoding($false)))
Write-Output "AOC_OFFSITE_BACKUP_OK: $final"
Write-Output 'Keep this archive private: it contains account database, world and server secrets. Download again periodically; this script is manual.'
