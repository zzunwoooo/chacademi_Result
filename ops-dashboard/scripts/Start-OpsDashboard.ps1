# 차카데미 운영 대시보드 실행 (메모 #92). 작업 스케줄러가 이 스크립트를 부른다.
# - 대시보드가 죽으면 10초 뒤 다시 켠다 (게임 서버와 무관).
# - 우선순위는 일부러 "보통" 그대로: Windows 는 "보통 미만" 부모가 띄운 프로그램에 그 우선순위를 물려주므로
#   (시작 버튼 → 스크립트 → 서버) 게임 서버까지 느려질 수 있다. 대시보드는 대부분 잠자고 있어 CPU 를 거의 안 쓴다.
param(
    [string]$Root = 'C:\Chacademi\ops-dashboard',
    [string]$Java = 'C:\Program Files\Java\jdk-21.0.10\bin\java.exe'
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $Root
$config = Join-Path $Root 'ops-dashboard.properties'
$logDir = Join-Path $Root 'data'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
while ($true) {
    $jar = Get-ChildItem -LiteralPath $Root -Filter 'chacademi-ops-dashboard-*.jar' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) { throw "jar 없음: $Root\chacademi-ops-dashboard-*.jar" }
    $stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    Add-Content -LiteralPath (Join-Path $logDir 'dashboard-run.log') -Value "$stamp 시작 $($jar.Name)" -Encoding utf8
    $javaArgs = @('-Xms32m', '-Xmx256m', '-XX:+UseSerialGC', '-Dfile.encoding=UTF-8', '-jar', $jar.FullName, $config)
    $p = Start-Process -FilePath $Java -ArgumentList $javaArgs -WorkingDirectory $Root -NoNewWindow -PassThru `
        -RedirectStandardOutput (Join-Path $logDir 'dashboard-out.log') -RedirectStandardError (Join-Path $logDir 'dashboard-err.log')
    $p.WaitForExit()
    $stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    Add-Content -LiteralPath (Join-Path $logDir 'dashboard-run.log') -Value "$stamp 종료 코드 $($p.ExitCode) — 10초 뒤 다시 시작" -Encoding utf8
    if ($p.ExitCode -eq 2) { break } # 설정 파일 없음 → 반복하지 않음
    Start-Sleep -Seconds 10
}
