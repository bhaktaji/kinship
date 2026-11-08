# ---------------------------------------------------------------------------
# Kinship local startup (called by startup.bat).
#
# Postgres + Redis run in Docker via the existing compose file. The API and web
# app run natively with Maven and Next.js, which is the project's documented
# local workflow. Services that are already healthy are reused, never restarted.
#
# Never: docker compose down, volume prune, dropdb, truncate, reset.
# ---------------------------------------------------------------------------

$ErrorActionPreference = 'Continue'

# --- paths -----------------------------------------------------------------
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root = Split-Path -Parent $ScriptDir
$EnvFile = Join-Path $Root '.env'
$EnvExample = Join-Path $Root '.env.example'
$ComposeFile = Join-Path $Root 'infra\docker-compose.yml'
$LogDir = Join-Path $Root 'logs'
$BackendLog = Join-Path $LogDir 'backend.log'
$BackendErrLog = Join-Path $LogDir 'backend.err.log'
$FrontendLog = Join-Path $LogDir 'frontend.log'
$FrontendErrLog = Join-Path $LogDir 'frontend.err.log'
$BackendPidFile = Join-Path $LogDir 'backend.pid'
$FrontendPidFile = Join-Path $LogDir 'frontend.pid'

$script:Step = 'initialisation'
$script:Started = @()

function Write-Step([string]$text) {
  Write-Host ""
  Write-Host "==> $text" -ForegroundColor Cyan
}
function Write-Ok([string]$text) { Write-Host "    [OK]   $text" -ForegroundColor Green }
function Write-Info([string]$text) { Write-Host "    [..]   $text" }
function Write-Warn([string]$text) { Write-Host "    [WARN] $text" -ForegroundColor Yellow }

function Fail([string]$message, [string]$logHint) {
  Write-Host ""
  Write-Host "    [FAIL] $message" -ForegroundColor Red
  if ($logHint -and (Test-Path $logHint)) {
    Write-Host "    ---- last lines of $logHint ----" -ForegroundColor DarkGray
    Get-Content -Path $logHint -Tail 30 -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "    $_" }
    Write-Host "    --------------------------------" -ForegroundColor DarkGray
  }
  # Stop only what THIS run started, so a failed attempt leaves no half stack.
  Stop-StartedProcesses
  Write-Host ""
  Write-Host "Startup stopped at step: $($script:Step)" -ForegroundColor Red
  exit 1
}

function Stop-StartedProcesses {
  foreach ($process in $script:Started) {
    try {
      if ($process -and -not $process.HasExited) {
        Write-Info "Stopping process $($process.Id) started by this run"
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
      }
    } catch { }
  }
  $script:Started = @()
}

# --- process / port / http helpers -----------------------------------------
function Test-PortListening([int]$port) {
  # Get-NetTCPConnection can miss listeners owned by other sessions on some
  # Windows builds, so fall back to a direct TCP connect on both stacks.
  $connections = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
  if ($connections) { return $true }
  foreach ($target in @('127.0.0.1', '::1')) {
    $client = $null
    try {
      $client = New-Object System.Net.Sockets.TcpClient
      $async = $client.BeginConnect($target, $port, $null, $null)
      if ($async.AsyncWaitHandle.WaitOne(400) -and $client.Connected) { return $true }
    } catch { } finally {
      if ($client) { $client.Close() }
    }
  }
  return $false
}

function Get-HttpStatus([string]$url) {
  try {
    $response = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
    return [int]$response.StatusCode
  } catch {
    if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
    return 0
  }
}

function Wait-HttpOk([string]$url, [int]$timeoutSeconds) {
  $deadline = (Get-Date).AddSeconds($timeoutSeconds)
  while ((Get-Date) -lt $deadline) {
    if ((Get-HttpStatus $url) -eq 200) { return $true }
    Start-Sleep -Seconds 2
  }
  return $false
}

function Get-EnvMap([string]$path) {
  $map = @{}
  if (-not (Test-Path $path)) { return $map }
  foreach ($raw in Get-Content -Path $path) {
    $line = $raw.Trim()
    if (-not $line -or $line.StartsWith('#') -or -not $line.Contains('=')) { continue }
    $index = $line.IndexOf('=')
    $key = $line.Substring(0, $index).Trim()
    $value = $line.Substring($index + 1).Trim()
    if ($value.Length -ge 2) {
      $first = $value.Substring(0, 1)
      if (($first -eq '"' -or $first -eq "'") -and $value.EndsWith($first)) {
        $value = $value.Substring(1, $value.Length - 2)
      }
    }
    $map[$key] = $value
  }
  return $map
}

# --- 1. prerequisites ------------------------------------------------------
$script:Step = 'prerequisite checks'
Write-Step 'Checking prerequisites (Docker, Java 21, Maven, Node.js)'

foreach ($tool in @('docker', 'java', 'mvn', 'node', 'npm')) {
  if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
    Fail "'$tool' is not on PATH. Install it or add it to PATH, then re-run." $null
  }
}
Write-Ok 'docker, java, mvn, node and npm are on PATH'

$javaVersionLine = (& java -version 2>&1 | Select-Object -First 1)
if ($javaVersionLine -notmatch '"(\d+)') {
  Fail "Could not determine the Java version: $javaVersionLine" $null
}
$javaMajor = [int]$Matches[1]
if ($javaMajor -lt 21) {
  Fail "Java 21+ is required, found major version $javaMajor." $null
}
Write-Ok "Java $javaMajor detected"

$nodeVersion = (& node -v).Trim()
$nodeMajor = [int](($nodeVersion.TrimStart('v') -split '\.')[0])
if ($nodeMajor -lt 20) {
  Fail "Node.js 20+ is required, found $nodeVersion." $null
}
Write-Ok "Node.js $nodeVersion detected"

# --- 2. environment file ---------------------------------------------------
$script:Step = 'environment file'
Write-Step 'Loading .env'

if (-not (Test-Path $EnvFile)) {
  if (-not (Test-Path $EnvExample)) {
    Fail "Neither .env nor .env.example exists at $Root." $null
  }
  Copy-Item $EnvExample $EnvFile
  # Generate a local secret instead of hardcoding one in the script.
  $bytes = New-Object byte[] 48
  [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
  $secret = [Convert]::ToBase64String($bytes)
  $content = Get-Content $EnvFile | ForEach-Object {
    if ($_ -match '^KINSHIP_JWT_SECRET=') { "KINSHIP_JWT_SECRET=$secret" } else { $_ }
  }
  Set-Content -Path $EnvFile -Value $content -Encoding ASCII
  Write-Ok 'Created .env from .env.example with a generated JWT secret'
  Write-Warn 'Review .env if you want different local settings.'
} else {
  Write-Ok '.env found (values are not printed)'
}

$envMap = Get-EnvMap $EnvFile
$jwt = $envMap['KINSHIP_JWT_SECRET']
if (-not $jwt -or $jwt.Length -lt 32 -or $jwt -match 'replace-with') {
  Fail 'KINSHIP_JWT_SECRET in .env must be at least 32 characters (see .env.example).' $null
}
$dbName = if ($envMap['KINSHIP_DB_NAME']) { $envMap['KINSHIP_DB_NAME'] } else { 'kinship' }
$dbUser = if ($envMap['KINSHIP_DB_USER']) { $envMap['KINSHIP_DB_USER'] } else { 'kinship' }
$dbPassword = if ($envMap['KINSHIP_DB_PASSWORD']) { $envMap['KINSHIP_DB_PASSWORD'] } else { 'kinship' }
$corsOrigins = if ($envMap['KINSHIP_CORS_ORIGINS']) { $envMap['KINSHIP_CORS_ORIGINS'] } else { 'http://localhost:3000' }
$seed = if ($envMap['KINSHIP_SEED']) { $envMap['KINSHIP_SEED'] } else { 'true' }
$apiBase = if ($envMap['NEXT_PUBLIC_API_BASE_URL']) { $envMap['NEXT_PUBLIC_API_BASE_URL'] } else { 'http://localhost:8080' }

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

# --- 3. Docker engine ------------------------------------------------------
$script:Step = 'Docker engine'
Write-Step 'Checking Docker engine'

docker info *> $null
if ($LASTEXITCODE -ne 0) {
  $dockerDesktop = Join-Path ${env:ProgramFiles} 'Docker\Docker\Docker Desktop.exe'
  if (-not (Test-Path $dockerDesktop)) {
    Fail 'Docker Desktop is not running and was not found at the standard path.' $null
  }
  Write-Info 'Docker Desktop is not running; launching it'
  Start-Process -FilePath $dockerDesktop | Out-Null
  $deadline = (Get-Date).AddSeconds(240)
  $ready = $false
  while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 5
    docker info *> $null
    if ($LASTEXITCODE -eq 0) { $ready = $true; break }
  }
  if (-not $ready) {
    Fail 'Docker engine did not become ready within 240 seconds after launching Docker Desktop.' $null
  }
}
Write-Ok 'Docker engine is responding'

$composeVersion = (& docker compose version 2>&1)
if ($LASTEXITCODE -ne 0) {
  Fail "docker compose is unavailable: $composeVersion" $null
}
Write-Ok 'Docker Compose is available'

# --- 4. infrastructure (postgres + redis only) -----------------------------
$script:Step = 'infrastructure start'
Write-Step 'Starting PostgreSQL/PostGIS and Redis (existing volumes are reused)'

# Absolute -f/--env-file paths make this independent of the current directory.
$composeArgs = @('compose', '--env-file', $EnvFile, '-f', $ComposeFile)
$upOutput = (& docker @composeArgs up -d postgres redis 2>&1)
if ($LASTEXITCODE -ne 0) {
  Write-Host ($upOutput | Out-String)
  Fail 'docker compose could not start postgres/redis.' $null
}
Write-Ok 'compose accepted postgres and redis'

Write-Info 'Waiting for container health checks'
$deadline = (Get-Date).AddSeconds(120)
$healthy = $false
while ((Get-Date) -lt $deadline) {
  $allHealthy = $true
  foreach ($service in @('postgres', 'redis')) {
    $id = (& docker @composeArgs ps -q $service 2>$null)
    if (-not $id) { $allHealthy = $false; break }
    $status = (& docker inspect -f '{{.State.Health.Status}}' $id 2>$null)
    if ($status -ne 'healthy') { $allHealthy = $false; break }
  }
  if ($allHealthy) { $healthy = $true; break }
  Start-Sleep -Seconds 3
}
if (-not $healthy) {
  Fail 'PostgreSQL and Redis did not both report healthy within 120 seconds.' $null
}
Write-Ok 'PostgreSQL and Redis are healthy'

# --- 5. backend ------------------------------------------------------------
$script:Step = 'backend start'
Write-Step 'Starting the Spring Boot API'

$backendUrl = 'http://localhost:8080'
if ((Get-HttpStatus "$backendUrl/readyz") -eq 200) {
  Write-Ok 'API already answers /readyz on :8080 - reusing it (nothing started)'
  $script:BackendReused = $true
} else {
  $script:BackendReused = $false
  # Any response at all (even 503) proves something owns the port, so never
  # start a second API on top of it.
  if ((Test-PortListening 8080) -or ((Get-HttpStatus $backendUrl) -ne 0)) {
    Fail 'Port 8080 is in use but /readyz is not healthy. If the compose api container owns it, run: docker compose --env-file .env -f infra/docker-compose.yml stop api' $null
  }

  # Native API points at the compose host port 5433, never the native 5432.
  $env:KINSHIP_DB_URL = "jdbc:postgresql://localhost:5433/$dbName"
  $env:KINSHIP_DB_USER = $dbUser
  $env:KINSHIP_DB_PASSWORD = $dbPassword
  $env:KINSHIP_REDIS_HOST = 'localhost'
  $env:KINSHIP_REDIS_PORT = '6379'
  $env:KINSHIP_JWT_SECRET = $jwt
  $env:KINSHIP_CORS_ORIGINS = $corsOrigins
  $env:KINSHIP_COOKIE_SECURE = 'false'
  $env:KINSHIP_SEED = $seed
  if ($envMap['KINSHIP_METRICS_TOKEN']) { $env:KINSHIP_METRICS_TOKEN = $envMap['KINSHIP_METRICS_TOKEN'] }

  $mvn = (Get-Command mvn).Source
  $backend = Start-Process -FilePath $mvn `
    -ArgumentList @('-B', '-ntp', 'spring-boot:run') `
    -WorkingDirectory (Join-Path $Root 'api') `
    -RedirectStandardOutput $BackendLog -RedirectStandardError $BackendErrLog `
    -WindowStyle Hidden -PassThru
  $script:Started += $backend
  Set-Content -Path $BackendPidFile -Value $backend.Id -Encoding ASCII
  Write-Info "API process $($backend.Id); log: logs\backend.log"
}

Write-Info 'Waiting for /readyz (Flyway + DB + Redis)'
if (-not (Wait-HttpOk "$backendUrl/readyz" 240)) {
  Fail 'API did not become ready at http://localhost:8080/readyz within 240 seconds.' $BackendErrLog
}
Write-Ok 'API ready: http://localhost:8080/readyz'

# --- 6. frontend -----------------------------------------------------------
$script:Step = 'frontend start'
Write-Step 'Starting the Next.js frontend'

$frontendUrl = 'http://localhost:3000'
if ((Get-HttpStatus $frontendUrl) -eq 200) {
  Write-Ok 'Frontend already serves on :3000 - reusing it (nothing started)'
  $script:FrontendReused = $true
} else {
  $script:FrontendReused = $false
  if ((Test-PortListening 3000) -or ((Get-HttpStatus $frontendUrl) -ne 0)) {
    Fail 'Port 3000 is in use but the frontend does not answer HTTP. If the compose web container owns it, run: docker compose --env-file .env -f infra/docker-compose.yml stop web' $null
  }

  $webDir = Join-Path $Root 'web'
  if (-not (Test-Path (Join-Path $webDir 'node_modules'))) {
    Write-Info 'web\node_modules is missing; running npm ci (log: logs\npm-ci.log)'
    $ciLog = Join-Path $LogDir 'npm-ci.log'
    Push-Location $webDir
    try { & npm ci *> $ciLog } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) {
      Fail 'npm ci failed in web\. See logs\npm-ci.log.' $ciLog
    }
    Write-Ok 'web dependencies installed'
  }

  $env:NEXT_PUBLIC_API_BASE_URL = $apiBase
  # `npm` can resolve to a .ps1 shim, which Start-Process cannot execute; npm.cmd is.
  $npmCommand = Get-Command npm.cmd -ErrorAction SilentlyContinue
  if (-not $npmCommand) {
    Fail 'npm.cmd was not found on PATH.' $null
  }
  try {
    # Pin the port: without -p, Next silently falls back to a random port when
    # 3000 is briefly taken, and every readiness check below would be wrong.
    $frontend = Start-Process -FilePath $npmCommand.Source `
      -ArgumentList @('run', 'dev', '--', '-p', '3000') `
      -WorkingDirectory $webDir `
      -RedirectStandardOutput $FrontendLog -RedirectStandardError $FrontendErrLog `
      -WindowStyle Hidden -PassThru
  } catch {
    Fail "Could not start 'npm run dev': $($_.Exception.Message)" $FrontendErrLog
  }
  $script:Started += $frontend
  Set-Content -Path $FrontendPidFile -Value $frontend.Id -Encoding ASCII
  Write-Info "Web process $($frontend.Id); log: logs\frontend.log"
}

Write-Info 'Waiting for the first page render (Next compiles on demand)'
if (-not (Wait-HttpOk $frontendUrl 240)) {
  Fail 'Frontend did not answer http://localhost:3000 within 240 seconds.' $FrontendLog
}
Write-Ok 'Frontend ready: http://localhost:3000'

# --- 7. summary ------------------------------------------------------------
$backendHealth = Get-HttpStatus "$backendUrl/readyz"
$webHealth = Get-HttpStatus $frontendUrl

Write-Host ""
Write-Host "==========================================================" -ForegroundColor Green
Write-Host " Kinship is running" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "  Backend   : $backendUrl   (/readyz -> $backendHealth)"
Write-Host "  Frontend  : $frontendUrl         (HTTP -> $webHealth)"
Write-Host "  Database  : localhost:5433 (PostGIS container; volume preserved)"
Write-Host "  Redis     : localhost:6379 (container)"
Write-Host ""
if ($script:BackendReused) { Write-Host '  API: reused an instance that was already healthy.' } else { Write-Host '  API: started by this script (logs\backend.log).' }
if ($script:FrontendReused) { Write-Host '  Web: reused an instance that was already serving.' } else { Write-Host '  Web: started by this script (logs\frontend.log).' }
Write-Host ""
Write-Host '  Demo login (seeded data, from README):'
Write-Host '    demo@kinship.local / Demo12345!'
Write-Host ""
Write-Host '  These processes keep running after this window closes.'
Write-Host '  To stop the ones this script started:'
Write-Host '    Get-Content logs\backend.pid  | ForEach-Object { Stop-Process -Id $_ -Force }'
Write-Host '    Get-Content logs\frontend.pid | ForEach-Object { Stop-Process -Id $_ -Force }'
Write-Host '  Please do not use "docker compose down -v"; it would delete the database volume.'
Write-Host "==========================================================" -ForegroundColor Green

exit 0
