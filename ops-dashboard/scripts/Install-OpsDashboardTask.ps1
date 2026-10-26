# 운영 대시보드를 Windows 작업 스케줄러에 등록 (PC 켜지면 자동 시작, 로그온 안 해도 실행). 메모 #92.
# 실행: pwsh -NoProfile -File Install-OpsDashboardTask.ps1   (게임 서버 예약 작업 Chacademi-Network 와 같은 계정 권장)
# 계정 비밀번호를 한 번 묻는다 (작업 스케줄러에 저장, 이 스크립트·저장소에는 남지 않음).
param(
    [string]$Root = 'C:\Chacademi\ops-dashboard',
    [string]$User = "$env:USERDOMAIN\$env:USERNAME",
    [string]$TaskName = 'Chacademi-OpsDashboard',
    [string]$Pwsh = 'C:\Program Files\PowerShell\7\pwsh.exe'
)
$ErrorActionPreference = 'Stop'
$script = Join-Path $Root 'Start-OpsDashboard.ps1'
if (-not (Test-Path -LiteralPath $script)) { throw "먼저 $script 를 복사하세요" }
$action = New-ScheduledTaskAction -Execute $Pwsh -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$script`" -Root `"$Root`"" -WorkingDirectory $Root
$trigger = New-ScheduledTaskTrigger -AtStartup
$trigger.Delay = 'PT30S'
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew
$settings.Priority = 6   # 작업 스케줄러 기본값 7(보통 미만)은 자식 프로세스(시작 버튼으로 띄우는 것)에 물려줄 수 있어 보통(6)으로
$cred = Get-Credential -UserName $User -Message '대시보드를 실행할 Windows 계정 비밀번호'
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings `
    -User $cred.UserName -Password $cred.GetNetworkCredential().Password -RunLevel Limited -Force | Out-Null
Write-Host "등록됨: $TaskName ($($cred.UserName)). 지금 시작: Start-ScheduledTask -TaskName $TaskName"
