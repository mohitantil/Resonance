@echo off
setlocal
set "DOTNET_ROOT=%~dp0dotnet_sdk"
set "PATH=%~dp0dotnet_sdk;%PATH%"

echo Starting Local Sync Agent (Windows Agent)...
"%~dp0dotnet_sdk\dotnet.exe" "%~dp0windows\WindowsAgent\bin\Debug\net9.0-windows10.0.19041.0\WindowsAgent.dll" %*

