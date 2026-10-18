param([switch]$Browser, [switch]$NoWindow, [int]$Port = 7860)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $taskRoot
$taskPython = Join-Path $taskRoot 'venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $taskPython)) {
    python -m venv (Join-Path $taskRoot 'venv')
    if ($LASTEXITCODE -ne 0) { throw 'Install Python 3.11 or newer, then retry.' }
}
& $taskPython -m pip install -r requirements.txt
if ($LASTEXITCODE -ne 0) { throw 'Could not install app dependencies.' }
$taskArguments = @('app.py', '--port', "$Port")
if ($Browser) { $taskArguments += '--browser' }
if ($NoWindow) { $taskArguments += '--no-window' }
& $taskPython @taskArguments
exit $LASTEXITCODE
