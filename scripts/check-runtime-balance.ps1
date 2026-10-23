$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if(-not $env:JAVA_HOME){throw 'Set JAVA_HOME to Java 21 first.'}
$java=Join-Path $env:JAVA_HOME 'bin\java.exe'
$javac=Join-Path $env:JAVA_HOME 'bin\javac.exe'
$jar=Join-Path $env:JAVA_HOME 'bin\jar.exe'
$connections=@(Get-NetTCPConnection -State Listen -LocalAddress 127.0.0.1 -LocalPort 25565 -ErrorAction Stop)
$processIds=@($connections | Select-Object -ExpandProperty OwningProcess -Unique)
if($processIds.Count -ne 1 -or (Get-Process -Id $processIds[0]).ProcessName -ne 'java'){throw 'Expected exactly one Java preview on 127.0.0.1:25565.'}
$directory=Join-Path $workspace '.tools\balance-runtime-reader'
New-Item -ItemType Directory -Path $directory -Force | Out-Null
& $javac -d $directory (Join-Path $workspace 'scripts\java\AocBalanceRead.java')
if($LASTEXITCODE -ne 0){throw 'Could not compile the read-only inspector.'}
$manifest=Join-Path $directory 'MANIFEST.MF'
[IO.File]::WriteAllText($manifest,"Manifest-Version: 1.0`nAgent-Class: AocBalanceRead`n`n",[Text.Encoding]::ASCII)
$agent=Join-Path $directory "reader-$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()).jar"
& $jar cfm $agent $manifest -C $directory AocBalanceRead.class
if($LASTEXITCODE -ne 0){throw 'Could not package the inspector.'}
$output=Join-Path $workspace '.tools\balance-runtime-result.json'
if(Test-Path -LiteralPath $output){Move-Item -LiteralPath $output -Destination "$output.before-$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"}
& $java --add-modules jdk.attach -cp $directory AocBalanceRead $processIds[0] $agent $output
if($LASTEXITCODE -ne 0 -or -not(Test-Path -LiteralPath $output)){throw 'Runtime balance was not confirmed. Existing config/world were not changed.'}
Get-Content -LiteralPath $output
