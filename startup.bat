@echo off
REM ---------------------------------------------------------------------------
REM Kinship - one-click local startup.
REM
REM This file is the single entry point. It resolves its own repository root so
REM it can be double-clicked or run from any working directory, then delegates
REM to scripts\startup.ps1 (Windows shell scripting is far more reliable there).
REM
REM Nothing here starts, stops, resets or prunes Docker volumes or databases.
REM ---------------------------------------------------------------------------

setlocal EnableExtensions

set "ROOT=%~dp0"
REM %~dp0 always ends with a backslash; trim it so paths join predictably.
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"

if not exist "%ROOT%\scripts\startup.ps1" (
  echo [FAIL] scripts\startup.ps1 not found next to startup.bat.
  echo        Expected repository root: "%ROOT%"
  exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\scripts\startup.ps1"
set "RC=%ERRORLEVEL%"

if not "%RC%"=="0" (
  echo.
  echo Startup failed with exit code %RC%. See the messages above.
)

exit /b %RC%
