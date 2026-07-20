@echo off
setlocal
set "ROOT=%~dp0"
set "DIST_ZIP="
for %%F in ("%ROOT%..\dist\target\glowroot-agent-*-dist.zip") do set "DIST_ZIP=%%~fF"
set "AGENT_DIR=%ROOT%glowroot-agent"

if not defined DIST_ZIP (
  echo Missing agent dist zip under agent\dist\target\
  echo Build first: mvn install -pl :glowroot-agent -am -DskipTests
  exit /b 1
)
if not exist "%DIST_ZIP%" (
  echo Missing %DIST_ZIP%
  echo Build first: mvn install -pl :glowroot-agent -am -DskipTests
  exit /b 1
)

if exist "%AGENT_DIR%" rmdir /s /q "%AGENT_DIR%"
mkdir "%AGENT_DIR%"
mkdir "%AGENT_DIR%\_unpack"

powershell -NoProfile -Command "Expand-Archive -LiteralPath '%DIST_ZIP%' -DestinationPath '%AGENT_DIR%\_unpack' -Force"
if errorlevel 1 exit /b 1

xcopy /E /I /Y "%AGENT_DIR%\_unpack\glowroot\*" "%AGENT_DIR%\" >nul
rmdir /s /q "%AGENT_DIR%\_unpack"

if not exist "%AGENT_DIR%\glowroot.jar" (
  echo Failed to unpack glowroot.jar
  exit /b 1
)

rem Capture every request + dense profiling so Profile tab fills quickly
(
  echo {
  echo   "transactions": {
  echo     "slowThresholdMillis": 0,
  echo     "profilingIntervalMillis": 50
  echo   }
  echo }
) > "%AGENT_DIR%\config.json"

(
  echo {
  echo   "web": {
  echo     "port": 4001,
  echo     "bindAddress": "127.0.0.1",
  echo     "contextPath": "/",
  echo     "sessionTimeoutMinutes": 30,
  echo     "sessionCookieName": "GLOWROOT_SESSION_ID"
  echo   }
  echo }
) > "%AGENT_DIR%\admin.json"

echo Agent ready: %AGENT_DIR%\glowroot.jar
echo From zip: %DIST_ZIP%
echo UI port: 4001  ^(avoids clash with other local Glowroot on 4000^)
echo Config: slowThresholdMillis=0 profilingIntervalMillis=50
