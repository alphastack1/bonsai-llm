param([ValidateSet('vulkan','cuda','cpu')][string]$Backend = 'vulkan')
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $taskRoot
$taskPython = Join-Path $taskRoot 'venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $taskPython)) {
    python -m venv (Join-Path $taskRoot 'venv')
    if ($LASTEXITCODE -ne 0) { throw 'Python 3.11+ is required.' }
}
& $taskPython -m pip install -r requirements.txt -r requirements-build.txt
if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed.' }
& $taskPython scripts\prepare.py --platform windows --backend $Backend --models 2B
if ($LASTEXITCODE -ne 0) { throw 'Artifact preparation failed.' }
& $taskPython -m PyInstaller --noconfirm --clean bonsai-llm.spec
if ($LASTEXITCODE -ne 0) { throw 'EXE build failed.' }
Write-Output (Join-Path $taskRoot 'dist\Bonsai-2-Windows.exe')
