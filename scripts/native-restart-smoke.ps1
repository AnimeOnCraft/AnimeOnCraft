param([switch]$Visual)
$ErrorActionPreference = 'Stop'
$aocProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$aocSmokeScript = Join-Path $PSScriptRoot 'native-smoke.ps1'

function Read-AocLocalMetadata {
  $aocLocalRoot = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'AnimeOnCraft.InstallDevelopment.v1'
  foreach ($aocDirectory in @($aocLocalRoot, (Join-Path $aocLocalRoot 'control'), (Join-Path $aocLocalRoot 'instance'), (Join-Path $aocLocalRoot 'cache'))) {
    $aocItem = Get-Item -LiteralPath $aocDirectory -Force
    if (-not $aocItem.PSIsContainer -or ($aocItem.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
      throw 'Local instance metadata directory is redirected or has the wrong kind.'
    }
  }
  $aocNames = @('aoc-instance.json', 'namespace.json', 'lease.lock', 'ready.json', 'control\aoc-instance.json', 'instance\aoc-instance.json', 'cache\aoc-instance.json')
  foreach ($aocName in $aocNames) {
    $aocFile = Join-Path $aocLocalRoot $aocName
    $aocItem = Get-Item -LiteralPath $aocFile -Force
    if ($aocItem.PSIsContainer -or ($aocItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -or $aocItem.Length -gt 16384) {
      throw 'Local instance metadata is redirected, too large or has the wrong kind.'
    }
    $aocDigest = (Get-FileHash -LiteralPath $aocFile -Algorithm SHA256).Hash
    "$aocName=$aocDigest"
  }
}

Push-Location -LiteralPath $aocProjectRoot
try {
  # Each smoke starts the ordinary executable, requires local Ready, and closes its own process.
  & $aocSmokeScript -SkipAccount -Visual:$Visual
  if ($LASTEXITCODE -ne 0) { throw 'First native smoke failed.' }
  $aocBefore = @(Read-AocLocalMetadata)
  & $aocSmokeScript -SkipAccount -Visual:$Visual
  if ($LASTEXITCODE -ne 0) { throw 'Restart native smoke failed.' }
  $aocAfter = @(Read-AocLocalMetadata)
  if ($aocBefore.Count -ne 7 -or $aocAfter.Count -ne 7 -or (Compare-Object $aocBefore $aocAfter)) {
    throw 'Local instance metadata changed across restart.'
  }
  Write-Output 'Native restart smoke: two Ready runs; seven immutable metadata hashes unchanged; account skipped.'
} finally {
  Pop-Location
}
