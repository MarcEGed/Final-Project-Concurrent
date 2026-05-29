@echo off
setlocal EnableDelayedExpansion

rem ── Navigate to backend relative to this script ──────────────────────────────
cd /d "%~dp0code\converter\backend-java"

rem ── Find Java ────────────────────────────────────────────────────────────────
rem Priority: JAVA_HOME env var → java on PATH
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" (
        set JAVA_EXE=%JAVA_HOME%\bin\java.exe
    ) else (
        echo [WARN] JAVA_HOME is set but %JAVA_HOME%\bin\java.exe not found. Falling back to PATH.
        set JAVA_EXE=java
    )
) else (
    set JAVA_EXE=java
)

rem ── Build if JAR is missing ───────────────────────────────────────────────────
if not exist "build\libs\threadconv.jar" (
    echo [THREADCONV] No JAR found. Building now ^(first run only^)...
    echo.
    call gradlew.bat bootJar --no-daemon
    if errorlevel 1 (
        echo.
        echo [ERROR] Build failed. Make sure Java 17+ is installed and JAVA_HOME is set.
        pause
        exit /b 1
    )
    echo.
)

rem ── Start backend ─────────────────────────────────────────────────────────────
echo [THREADCONV] Starting backend...
echo   REST API  ^>  http://localhost:3001
echo   Socket.IO ^>  http://localhost:3002
echo   Press Ctrl+C to stop.
echo.
"%JAVA_EXE%" -jar build\libs\threadconv.jar
