param([switch]$Verify)
$ErrorActionPreference='Stop'
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'
$env:Path=$env:JAVA_HOME+'\bin;'+$env:Path
$env:GRADLE_USER_HOME='C:\Chacademi\tools\gradle-cache'
$BuildRoot=$PSScriptRoot
Set-Location -LiteralPath $BuildRoot
$tasks=if($Verify){@('verifyServer','assembleServer',':magic-mythic-paper:build',':chacademi-party-velocity:build',':kyudichi-paper:build')}else{@('assembleServer')}
& '.\gradlew.bat' --no-daemon --console=plain --offline --max-workers=1 --continue "-Dorg.gradle.jvmargs=-Xmx1g -Dfile.encoding=UTF-8" -I "C:/Chacademi/artifacts/release-bag-travel-off-20261024-task9/bounded-tests.init.gradle" "-PmythicJar=$BuildRoot/plugins/MythicMobs-5.9.0.jar" "-PmagicCodexBridgeJar=$BuildRoot/magic-codex-paper/build/libs/magic-codex-bridge-0.31.3-integrated-20261020.1-gimmick.1-houses.1-commands.1-followups.1-nameplates.2-titles.1-storych1.1.jar" @tasks
if($LASTEXITCODE -ne 0){throw 'Remote server plugin build failed'}
$dest=Join-Path $BuildRoot 'verification/artifacts'
New-Item -ItemType Directory -Path $dest -Force|Out-Null
foreach($project in @('magic-codex-paper','magic-discovery-paper','creature-spawns-paper','tornado-event-paper')){
    Get-ChildItem -LiteralPath "$project\build\libs" -File -Filter '*.jar'|Where-Object {$_.Name -notmatch '-sources|-javadoc'}|Copy-Item -Destination $dest -Force
}
Get-ChildItem -LiteralPath $dest -File -Filter '*.jar'|ForEach-Object {[pscustomobject]@{Name=$_.Name;Bytes=$_.Length;SHA256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}}|ConvertTo-Json
