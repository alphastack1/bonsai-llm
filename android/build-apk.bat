@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0..\scripts\build-android.ps1" %*
exit /b %errorlevel%
