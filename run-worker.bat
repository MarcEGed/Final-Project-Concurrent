@echo off
setlocal EnableDelayedExpansion

rem ── Paths relative to this script ────────────────────────────────────────────
set "BASE=%~dp0"
set "API_DIR=%BASE%code\converter\backend-java"
set "WORKER_DIR=%BASE%code\converter\backend-worker"
set "JAR=%WORKER_DIR%\build\libs\threadconv-worker.jar"

rem ── Find Java ────────────────────────────────────────────────────────────────
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

rem ── Build worker JAR if missing ───────────────────────────────────────────────
if not exist "%JAR%" (
    echo [THREADCONV-WORKER] No JAR found. Building now ^(first run only^)...
    echo.
    cd /d "%API_DIR%"
    call gradlew.bat -p "%WORKER_DIR%" bootJar --no-daemon
    if errorlevel 1 (
        echo.
        echo [ERROR] Build failed. Make sure Java 17+ is installed and JAVA_HOME is set.
        pause
        exit /b 1
    )
    echo.
)

rem ── Start worker ──────────────────────────────────────────────────────────────
echo [THREADCONV-WORKER] Starting worker service...
echo   Conversion Worker  ^>  http://localhost:3003
echo   Press Ctrl+C to stop.
echo.

cd /d "%WORKER_DIR%"
"%JAVA_EXE%" -jar "%JAR%"
