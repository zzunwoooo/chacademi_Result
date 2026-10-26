# 차카데미 게임 서버 시작/정지 (운영 대시보드 버튼용). 콘솔 창 없이 돈다: 명령은 RCON, 화면은 logs/latest.log.
#   Server-Control.ps1 -Action start|stop -Server wild1
# 시작: WMI 로 띄워 대시보드/작업 스케줄러가 다시 켜져도 서버가 같이 꺼지지 않게 한다.
# 정지: RCON 'stop' (월드 저장 후 종료) → 프로세스가 끝날 때까지 기다림.
param(
    [Parameter(Mandatory)][ValidateSet('start','stop')][string]$Action,
    [Parameter(Mandatory)][string]$Server,
    [string]$NetworkRoot = 'C:\Chacademi\network',
    [string]$Java = '',
    [int]$StopWaitSeconds = 150
)
$ErrorActionPreference = 'Stop'
if ($Server -notmatch '^[a-z0-9_-]{1,32}$') { throw "서버 이름 형식 오류: $Server" }
if (-not $Java) {
    $Java = @('C:\Program Files\Java\jdk-21.0.12.1\bin\java.exe', 'C:\Program Files\Java\jdk-21.0.10\bin\java.exe') |
        Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (-not $Java) { throw 'Java 21 을 찾지 못함' }
}
$dir = Join-Path $NetworkRoot $Server
if (-not (Test-Path -LiteralPath (Join-Path $dir 'server.jar'))) { throw "서버 폴더 없음: $dir" }

function Get-Props {
    $p = @{}
    foreach ($line in Get-Content -LiteralPath (Join-Path $dir 'server.properties')) {
        if ($line -match '^\s*([^#=]+?)\s*=(.*)$') { $p[$Matches[1]] = $Matches[2].Trim() }
    }
    $p
}
function Get-ServerPid([int]$port) {
    $c = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($c) { $c.OwningProcess } else { $null }
}
function Send-Rcon([string]$rconHost, [int]$port, [string]$password, [string]$command) {
    $client = New-Object Net.Sockets.TcpClient
    $client.Connect($rconHost, $port)
    $stream = $client.GetStream(); $stream.ReadTimeout = 5000
    function Write-Packet([int]$id, [int]$type, [string]$body) {
        $b = [Text.Encoding]::UTF8.GetBytes($body)
        $ms = New-Object IO.MemoryStream
        $w = New-Object IO.BinaryWriter($ms)
        $w.Write([int](10 + $b.Length)); $w.Write($id); $w.Write($type); $w.Write($b); $w.Write([byte]0); $w.Write([byte]0)
        $bytes = $ms.ToArray(); $stream.Write($bytes, 0, $bytes.Length)
    }
    function Read-Packet {
        $r = New-Object IO.BinaryReader($stream)
        $len = $r.ReadInt32(); $id = $r.ReadInt32(); $null = $r.ReadInt32()
        $body = $r.ReadBytes($len - 8)
        @{ id = $id; text = [Text.Encoding]::UTF8.GetString($body, 0, [Math]::Max(0, $body.Length - 2)) }
    }
    try {
        Write-Packet 1 3 $password
        $auth = Read-Packet
        if ($auth.id -eq -1) { throw 'RCON 비밀번호가 맞지 않음' }
        Write-Packet 2 2 $command
        try { (Read-Packet).text } catch { '' }   # stop 은 응답 전에 연결이 끊길 수 있음
    } finally { $client.Close() }
}

$props = Get-Props
$gamePort = [int]$props['server-port']
$running = Get-ServerPid $gamePort

if ($Action -eq 'start') {
    if ($running) { "이미 켜져 있음 ($Server, PID $running)"; exit 0 }
    $cmd = '"' + $Java + '" @jvm.args -jar server.jar nogui'
    try {
        $r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $cmd; CurrentDirectory = $dir } -ErrorAction Stop
        if ($r.ReturnValue -ne 0) { throw "Win32_Process.Create=$($r.ReturnValue)" }
        $newPid = $r.ProcessId
    } catch {
        # 일반 계정은 WMI 실행이 막힐 수 있다 → 보통 방식으로 (대시보드 프로세스 아래에서) 띄운다
        $p = Start-Process -FilePath $Java -ArgumentList '@jvm.args', '-jar', 'server.jar', 'nogui' -WorkingDirectory $dir -WindowStyle Hidden -PassThru
        $newPid = $p.Id
    }
    "시작함: $Server (PID $newPid). 포트가 열릴 때까지 1~2분 걸릴 수 있음"
    exit 0
}

if (-not $running) { "이미 꺼져 있음 ($Server)"; exit 0 }
if ($props['enable-rcon'] -ne 'true') { throw "$Server 는 RCON 이 꺼져 있어 안전하게 정지할 수 없음 (server.properties enable-rcon)" }
$rconHost = if ($props['server-ip']) { $props['server-ip'] } else { '127.0.0.1' }
$null = Send-Rcon $rconHost ([int]$props['rcon.port']) $props['rcon.password'] 'stop'
"정지 명령 보냄: $Server (월드 저장 중)"
$deadline = (Get-Date).AddSeconds($StopWaitSeconds)
while ((Get-Date) -lt $deadline) {
    if (-not (Get-Process -Id $running -ErrorAction SilentlyContinue)) { "꺼짐: $Server"; exit 0 }
    Start-Sleep -Seconds 2
}
throw "$StopWaitSeconds 초 안에 꺼지지 않음 ($Server, PID $running) — 로그 확인 필요"
