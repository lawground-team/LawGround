param(
    [ValidateSet('embed', 'rerank', 'all')][string]$Mode = 'embed',
    [string]$PythonPath = '',
    [string]$ModelCache = '',
    [ValidateRange(1024, 65535)][int]$Port = 8001
)
$ErrorActionPreference = 'Stop'
$lawgroundRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if (-not $PythonPath) {
    $PythonPath = Join-Path $lawgroundRoot 'ai-service/.venv/Scripts/python.exe'
}
$lawgroundPython = (Resolve-Path -LiteralPath $PythonPath).Path
$lawgroundEnvFile = Join-Path $lawgroundRoot '.env'
if (-not (Test-Path -LiteralPath $lawgroundEnvFile)) { throw 'Prepare the local .env first.' }
$lawgroundValues = @{}
foreach ($lawgroundLine in [IO.File]::ReadAllLines($lawgroundEnvFile)) {
    if ($lawgroundLine -match '^\s*([A-Z][A-Z0-9_]*)\s*=(.*)$') {
        $lawgroundValues[$matches[1]] = $matches[2]
    }
}
if (-not $lawgroundValues['AI_SERVICE_API_KEY']) { throw 'Missing local setting: AI_SERVICE_API_KEY' }
if ($lawgroundValues['AI_SERVICE_API_KEY'] -notmatch '^[A-Za-z0-9._~-]{16,256}$') {
    throw 'Use an unquoted URL-safe local service credential (16-256 characters), without whitespace or escapes.'
}
$lawgroundRequiredPins = @()
if ($Mode -in @('embed', 'all')) { $lawgroundRequiredPins += 'AI_EMBEDDING_MODEL_REVISION' }
if ($Mode -in @('rerank', 'all')) { $lawgroundRequiredPins += 'AI_RERANKER_MODEL_REVISION' }
foreach ($lawgroundName in $lawgroundRequiredPins) {
    if (-not $lawgroundValues[$lawgroundName]) { throw "Missing local setting: $lawgroundName" }
}
foreach ($lawgroundName in $lawgroundRequiredPins) {
    if ($lawgroundValues[$lawgroundName] -notmatch '^[0-9a-f]{40}$') { throw 'Model revision must be a pinned commit.' }
}
if ($lawgroundValues['INTERNAL_API_TOKEN'] -and $lawgroundValues['INTERNAL_API_TOKEN'] -ne $lawgroundValues['AI_SERVICE_API_KEY']) {
    throw 'The Spring and Python local service credentials must match.'
}
$lawgroundProcessEnv = @{
    INTERNAL_API_TOKEN = $lawgroundValues['AI_SERVICE_API_KEY']
    EMBED_BACKEND = $(if ($Mode -in @('embed', 'all')) { 'bge-m3' } else { 'off' })
    RERANK_BACKEND = $(if ($Mode -in @('rerank', 'all')) { 'bge-reranker' } else { 'off' })
    HYDE_BACKEND = 'off'
    VERIFY_BACKEND = 'off'
    EMBED_MODEL_REVISION = $lawgroundValues['AI_EMBEDDING_MODEL_REVISION']
    RERANK_MODEL_REVISION = $lawgroundValues['AI_RERANKER_MODEL_REVISION']
    OMP_NUM_THREADS = '2'
    MKL_NUM_THREADS = '2'
    TOKENIZERS_PARALLELISM = 'false'
    HF_HUB_DISABLE_TELEMETRY = '1'
    PYTHONDONTWRITEBYTECODE = '1'
}
if ($ModelCache) { $lawgroundProcessEnv['HF_HOME'] = [IO.Path]::GetFullPath($ModelCache) }
$lawgroundPrevious = @{}
$lawgroundDirectory = Get-Location
try {
    foreach ($lawgroundName in $lawgroundProcessEnv.Keys) {
        $lawgroundPrevious[$lawgroundName] = [Environment]::GetEnvironmentVariable($lawgroundName, 'Process')
        [Environment]::SetEnvironmentVariable($lawgroundName, $lawgroundProcessEnv[$lawgroundName], 'Process')
    }
    Set-Location -LiteralPath (Join-Path $lawgroundRoot 'ai-service')
    Write-Output "Starting real AI service: mode=$Mode, loopback port=$Port. Credentials omitted."
    & $lawgroundPython -m uvicorn app.main:app --host 127.0.0.1 --port $Port --no-access-log
    if ($LASTEXITCODE -ne 0) { throw 'Python AI service exited unsuccessfully.' }
} finally {
    Set-Location -LiteralPath $lawgroundDirectory.Path
    foreach ($lawgroundName in $lawgroundPrevious.Keys) {
        [Environment]::SetEnvironmentVariable($lawgroundName, $lawgroundPrevious[$lawgroundName], 'Process')
    }
}
