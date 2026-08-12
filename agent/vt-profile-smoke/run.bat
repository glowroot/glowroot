@echo off
setlocal
rem Smoke app for Glowroot #1125 (Jetty + virtual threads)

set "JDK21="
for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-21*") do set "JDK21=%%~fD"
if not defined JDK21 if defined JAVA_HOME set "JDK21=%JAVA_HOME%"
if not defined JDK21 (
  echo Set JAVA_HOME to a JDK 21+ install, or install Temurin 21.
  exit /b 1
)
set "JAVA_EXE=%JDK21%\bin\java.exe"
if not exist "%JAVA_EXE%" (
  echo java.exe not found under %JDK21%
  exit /b 1
)

"%JAVA_EXE%" -version 2>&1 | findstr /C:"version \"21" /C:"version \"22" /C:"version \"23" /C:"version \"24" /C:"version \"25" >nul
if errorlevel 1 (
  echo Need JDK 21+ for virtual threads. Current:
  "%JAVA_EXE%" -version
  exit /b 1
)

set "ROOT=%~dp0"
set "APP_JAR=%ROOT%target\vt-profile-smoke-1.0-SNAPSHOT.jar"
set "AGENT_DIR=%ROOT%glowroot-agent"
set "AGENT_JAR=%AGENT_DIR%\glowroot.jar"

if not exist "%APP_JAR%" (
  echo Building smoke app...
  pushd "%ROOT%"
  call mvn -q -DskipTests package
  if errorlevel 1 exit /b 1
  popd
)

if not exist "%AGENT_JAR%" (
  echo Unpack agent dist first: setup-agent.bat
  exit /b 1
)

echo Using JAVA: %JAVA_EXE%
"%JAVA_EXE%" -version
echo Agent: %AGENT_JAR%
echo App:   %APP_JAR%
echo.
echo Glowroot UI: http://127.0.0.1:4001
echo Hit:         http://127.0.0.1:8088/slow?ms=2500
echo.

"%JAVA_EXE%" -javaagent:"%AGENT_JAR%" -jar "%APP_JAR%" 8088
