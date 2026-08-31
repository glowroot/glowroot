# Build UiSandboxMain classpath into demo/runtime/ for the slim Docker image.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

Write-Host "Maven install (:glowroot-agent-ui-sandbox -am)..."
mvn -B -pl :glowroot-agent-ui-sandbox -am install -DskipTests
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$runtime = Join-Path $PSScriptRoot "runtime"
$lib = Join-Path $runtime "lib"
Remove-Item -Recurse -Force $runtime -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $lib | Out-Null

Write-Host "Copying dependencies to demo/runtime/lib..."
mvn -B -pl :glowroot-agent-ui-sandbox dependency:copy-dependencies `
  "-DincludeScope=test" `
  "-DoutputDirectory=$lib"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$sandbox = Join-Path $root "agent\ui-sandbox\target"
Copy-Item -Recurse (Join-Path $sandbox "classes") (Join-Path $runtime "classes")
Copy-Item -Recurse (Join-Path $sandbox "test-classes") (Join-Path $runtime "test-classes")

Write-Host "Ready. From repo root:"
Write-Host "  docker compose -f demo/docker-compose.yml up --build"
