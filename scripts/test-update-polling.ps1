$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$fixture=Join-Path $workspace ('.tools/installer-acceptance-'+[guid]::NewGuid().ToString('N'))
$product='AnimeOnCraft Poll Test '+[IO.Path]::GetFileName($fixture).Substring(21)
$registry="HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\$product"
$env:TEMP=Join-Path $workspace '.tools/work-temp';$env:TMP=$env:TEMP
$env:AOC_API_ORIGIN='https://193.178.158.117';$env:AOC_PUBLIC_CLIENT_CONFIG=Join-Path $workspace '.tools/client-public.json'
New-Item -ItemType Directory -Path (Join-Path $fixture 'install'),(Join-Path $fixture 'state/settings') -Force | Out-Null
if(Test-Path -LiteralPath $registry){throw 'Own polling registry already exists'}
$overlay=@{productName=$product;identifier=('ru.animeoncraft.poll.'+[guid]::NewGuid().ToString('N'));app=@{windows=@(@{label='main';title=$product;width=1180;height=760;visible=$false})}}
[IO.File]::WriteAllText((Join-Path $fixture 'overlay.json'),($overlay|ConvertTo-Json -Depth 6))
$config=@{endpoint='https://localhost:39447/aoc/latest.json';public_key=([IO.File]::ReadAllText('F:/animeoncraft-updater.key.pub').Trim())}
[IO.File]::WriteAllText((Join-Path $fixture 'simple-update.json'),($config|ConvertTo-Json))
Push-Location $workspace
try{
 & (Get-Command pwsh).Source -NoProfile -File scripts/test-pack-certificate.ps1 -OutputDirectory $fixture
 if($LASTEXITCODE -ne 0){throw 'Fixture certificate failed'}
 Copy-Item -LiteralPath (Join-Path $fixture 'certificate.pem') -Destination (Join-Path $fixture 'cert.pem')
 corepack pnpm --filter @animeoncraft/launcher run tauri:build --debug --no-bundle --features simple-update-acceptance --config (Join-Path $fixture 'overlay.json') --ci
 if($LASTEXITCODE -ne 0){throw 'Polling fixture build failed'}
 Copy-Item -LiteralPath (Join-Path $workspace 'apps/launcher/src-tauri/target/debug/animeoncraft-launcher.exe') -Destination (Join-Path $fixture 'install/animeoncraft-launcher.exe')
 New-Item -Path $registry -Force | Out-Null
 New-ItemProperty -Path $registry -Name InstallLocation -PropertyType String -Value (Join-Path $fixture 'install') -Force | Out-Null
 node scripts/native-update-polling.mjs $fixture
 if($LASTEXITCODE -ne 0){throw 'Native polling verification failed'}
}finally{
 if(Test-Path -LiteralPath $registry){Remove-Item -LiteralPath $registry}
 Pop-Location
}
