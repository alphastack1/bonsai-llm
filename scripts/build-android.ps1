$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $taskRoot
$taskSdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
if (-not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { throw 'Set JAVA_HOME to JDK 17 or newer.' }
if (-not (Test-Path -LiteralPath $taskSdk)) { throw 'Install Android SDK 36, or set ANDROID_HOME.' }
$env:ANDROID_HOME = $taskSdk
$taskPython = Join-Path $taskRoot 'venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $taskPython)) {
    python -m venv (Join-Path $taskRoot 'venv')
    if ($LASTEXITCODE -ne 0) { throw 'Python 3.11+ is required.' }
}
& $taskPython -m pip install -r requirements.txt
if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed.' }
& $taskPython scripts\prepare.py --platform android --models 2B
if ($LASTEXITCODE -ne 0) { throw 'Android preparation failed.' }
Set-Location -LiteralPath (Join-Path $taskRoot 'android')
& .\gradlew.bat --no-daemon assembleRelease
if ($LASTEXITCODE -ne 0) { throw 'APK build failed.' }
New-Item -ItemType Directory -Force -Path (Join-Path $taskRoot 'dist') | Out-Null
Copy-Item -LiteralPath 'app\build\outputs\apk\release\app-release.apk' -Destination (Join-Path $taskRoot 'dist\Bonsai-2-Android.apk')
Write-Output (Join-Path $taskRoot 'dist\Bonsai-2-Android.apk')
