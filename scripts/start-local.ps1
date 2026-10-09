# 이 PC에 준비된 MySQL·Memurai·JAR를 루프백 주소로 실행하는 개발용 진입점.
# 비밀 값은 .runtime/local-secrets.json에서 읽고 자식 프로세스에 전달한 뒤 현재 환경에서 제거한다.
# 기존 DB 계정은 시작 환경 변수로 덮어쓰지 않으며, 관리 화면에서 변경한 계정 정보가 우선한다.
param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runtime = Join-Path $workspace '.runtime'
$secretFile = Join-Path $runtime 'local-secrets.json'
if (!(Test-Path -LiteralPath $secretFile)) { throw '로컬 실행 구성이 없습니다. deploy/DEPLOYMENT.md의 Compose 방법을 사용하세요.' }
$settings = Get-Content -LiteralPath $secretFile -Raw | ConvertFrom-Json
$mysql = Join-Path $runtime 'mysql/mysql-8.4.11-winx64/bin/mysqld.exe'
$redis = Join-Path $runtime 'memurai-extracted/Memurai/memurai.exe'
$jar = Join-Path $workspace 'release/gyuhwan-admin-1.0.0.jar'
$java = (Get-Command java -ErrorAction SilentlyContinue).Source
if (!$java -and (Test-Path 'C:/Program Files/Java/jdk-17.0.20.1/bin/java.exe')) { $java = 'C:/Program Files/Java/jdk-17.0.20.1/bin/java.exe' }
foreach ($file in @($mysql,$redis,$jar,$java)) { if (!$file -or !(Test-Path -LiteralPath $file)) { throw "실행 파일을 찾을 수 없습니다: $file" } }
# 같은 포트의 다른 프로그램을 중단하거나 재사용하지 않도록 실행 경로까지 확인한다.
function Has-OwnedListener([int]$ListeningPort,[string]$ExpectedPath) {
 $connection = Get-NetTCPConnection -LocalPort $ListeningPort -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
 if (!$connection) { return $false }
 $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($connection.OwningProcess)"
 if ($process.CommandLine.Replace('/','\').IndexOf($ExpectedPath.Replace('/','\'),[StringComparison]::OrdinalIgnoreCase) -lt 0) { throw "포트 $ListeningPort 을 다른 프로그램이 사용합니다. 해당 프로그램을 확인하세요." }
 return $true
}
if (!(Has-OwnedListener 3307 $mysql)) {
 Start-Process -FilePath $mysql -ArgumentList @("--basedir=$runtime/mysql/mysql-8.4.11-winx64","--datadir=$runtime/mysql-data",'--bind-address=127.0.0.1','--port=3307','--mysqlx=OFF','--console') -WorkingDirectory $workspace -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtime 'mysql-local.log') -RedirectStandardError (Join-Path $runtime 'mysql-local-error.log') | Out-Null
}
if (!(Has-OwnedListener 6380 $redis)) {
 Start-Process -FilePath $redis -ArgumentList (Join-Path $runtime 'memurai-local.conf') -WorkingDirectory $workspace -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtime 'redis-local.log') -RedirectStandardError (Join-Path $runtime 'redis-local-error.log') | Out-Null
}
if (Has-OwnedListener $Port $jar) { Write-Output "이미 실행 중입니다: http://127.0.0.1:$Port/admin"; exit 0 }
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3307/gyuhwan?connectionTimeZone=Asia/Seoul&characterEncoding=UTF-8'
$env:DB_USERNAME = 'gyuhwan'
$env:DB_PASSWORD = $settings.dbPassword
$env:REDIS_PORT = '6380'
$env:REDIS_PASSWORD = $settings.redisPassword
$env:JWT_SECRET = $settings.jwtSecret
$env:PORT = "$Port"
$env:BIND_ADDRESS = '127.0.0.1'
# 루프백 HTTP 확인용 설정. Cloudflare 등 공개 HTTPS에서는 Secure 쿠키를 활성화한다.
$env:COOKIE_SECURE = 'false'
$env:MEDIA_PATH = Join-Path $workspace 'data/media'
$env:ADMIN_USERNAME = $settings.adminUsername
$env:ADMIN_PASSWORD = $settings.adminPassword
$env:IMPORT_PATH = ''
$env:RETENTION_CRON = '-'
Start-Process -FilePath $java -ArgumentList @('-jar',('"'+$jar+'"')) -WorkingDirectory $workspace -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtime 'app-local.log') -RedirectStandardError (Join-Path $runtime 'app-local-error.log') | Out-Null
foreach ($name in @('DB_PASSWORD','REDIS_PASSWORD','JWT_SECRET','ADMIN_PASSWORD')) { Remove-Item "Env:$name" -ErrorAction SilentlyContinue }
Write-Output "시작했습니다: http://127.0.0.1:$Port/admin_login"
