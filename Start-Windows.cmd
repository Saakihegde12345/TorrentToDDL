@echo off
setlocal
set "APP=%~dp0desktop\build\compose\binaries\main\app\TorrentToDDL\TorrentToDDL.exe"
if not exist "%APP%" (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build-windows.ps1"
    if errorlevel 1 (
        echo Windows build failed. See the error above.
        pause
        exit /b 1
    )
)
start "" "%APP%" %*
