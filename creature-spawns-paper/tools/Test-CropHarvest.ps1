# Run with PowerShell 7 on chacademi-host after the integrated deployment.
# Callbacks must throw on command/test failure. SendCommand must wait for console acknowledgement.
[CmdletBinding(DefaultParameterSetName='Run')]
param(
    [string]$ConfigPath='C:\Chacademi\network\wild\plugins\ChAcademiaWildlife\config.yml',
    [string]$BackupPath,
    [Parameter(Mandatory=$true)][scriptblock]$SendCommand,
    [Parameter(Mandatory=$true,ParameterSetName='Run')][scriptblock]$HarvestTest,
    [Parameter(Mandatory=$true,ParameterSetName='Restore')][switch]$RestoreOnly,
    [string]$Python='C:\Chacademi\tools\python\python.exe'
)
$ErrorActionPreference='Stop'
$helper=Join-Path $PSScriptRoot 'crop_chance.py'
$defaults=Join-Path $PSScriptRoot '..\src\main\resources\config.yml'
if(!$BackupPath){
    if($RestoreOnly){throw 'RestoreOnly requires the original BackupPath'}
    $BackupPath=Join-Path $PSScriptRoot ('crop-test-backups\config-'+[guid]::NewGuid().ToString('N')+'.original')
}
function Restore-CropChance {
    & $Python $helper restore --config $ConfigPath --backup $BackupPath
    if($LASTEXITCODE){throw 'Original config restore failed; retain backup and stop testing'}
    & $SendCommand 'wildlifeadmin reload'
    if($LASTEXITCODE){throw 'Config restored; console reload failed. Run wildlifeadmin reload before continuing.'}
}
if($RestoreOnly){Restore-CropChance; return}
try {
    Write-Output ('Recovery backup: '+$BackupPath)
    & $Python $helper prepare --config $ConfigPath --backup $BackupPath --defaults $defaults
    if($LASTEXITCODE){throw 'Temporary chance preparation failed'}
    & $SendCommand 'wildlifeadmin reload'
    if($LASTEXITCODE){throw 'Temporary config reload failed'}
    & $HarvestTest
    if($LASTEXITCODE){throw 'Harvest callback failed'}
} finally {
    if(Test-Path -LiteralPath $BackupPath){Restore-CropChance}
}
