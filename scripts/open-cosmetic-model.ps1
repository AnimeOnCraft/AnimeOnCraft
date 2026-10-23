param([ValidateSet('fox-ears','oni-mask','cyan-visor','shard-crown','orbit-halo','rift-lantern','star-chain','rift-mantle')][string]$Model='fox-ears')
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskEditor='F:/BlockBench/Blockbench.exe'
$taskModel=Join-Path $taskRoot "minecraft/cosmetics/$Model/$Model.bbmodel"
$taskProfile=Join-Path $taskRoot '.tools/blockbench-profile'
$taskTemp=Join-Path $taskRoot '.tools/work-temp'
foreach($taskFile in @($taskEditor,$taskModel)){
 if(-not(Test-Path -LiteralPath $taskFile -PathType Leaf)){throw "Required file missing: $taskFile"}
}
New-Item -ItemType Directory -Force -Path $taskProfile,$taskTemp | Out-Null
$taskOldTemp=$env:TEMP
$taskOldTmp=$env:TMP
try {
 $env:TEMP=$taskTemp
 $env:TMP=$taskTemp
 $taskProfileArgument='--user-data-dir="' + $taskProfile + '"'
 $taskModelArgument='"' + $taskModel + '"'
 Start-Process -FilePath $taskEditor -ArgumentList @($taskProfileArgument,$taskModelArgument) -WindowStyle Normal
} finally {
 $env:TEMP=$taskOldTemp
 $env:TMP=$taskOldTmp
}
