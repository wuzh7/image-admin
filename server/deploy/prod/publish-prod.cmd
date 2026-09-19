@echo off
chcp 65001 >nul
cd /d "%~dp0"
title Image Admin - ECS Publish
echo [%date% %time%] Starting main-site publish. This window shows build and deployment progress.
powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0publish-prod.ps1" %*
set "exitCode=%ERRORLEVEL%"
echo.
if not "%exitCode%"=="0" echo Publish failed. Exit code: %exitCode%
pause
exit /b %exitCode%
