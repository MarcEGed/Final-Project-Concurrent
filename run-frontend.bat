@echo off
setlocal

rem ── Navigate to frontend relative to this script ─────────────────────────────
cd /d "%~dp0code\converter\frontend"

echo [THREADCONV] Installing frontend dependencies (skipped if already done)...
call npm install

echo.
echo [THREADCONV] Starting frontend...
echo   Open ^> http://localhost:3000
echo   Press Ctrl+C to stop.
echo.
call npm start
