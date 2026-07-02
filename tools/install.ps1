# Instala o aimscope "de verdade" na máquina: build release + atalhos.
# Uso:  powershell -ExecutionPolicy Bypass -File tools\install.ps1
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot

Write-Host "== build release =="
Set-Location $repo
cargo build --release
if ($LASTEXITCODE -ne 0) { throw "cargo build falhou" }

$exe = Join-Path $repo "target\release\aimscope-ui.exe"
if (-not (Test-Path $exe)) { throw "aimscope-ui.exe nao encontrado" }

Write-Host "== atalhos =="
$shell = New-Object -ComObject WScript.Shell
foreach ($where in @([Environment]::GetFolderPath("Desktop"),
                     (Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs"))) {
    $lnk = $shell.CreateShortcut((Join-Path $where "aimscope.lnk"))
    $lnk.TargetPath = $exe
    $lnk.WorkingDirectory = $repo   # p/ achar sidecar\.venv e coach\
    $lnk.Description = "aimscope - analise de mecanica de mira"
    $lnk.Save()
    Write-Host "  -> $(Join-Path $where 'aimscope.lnk')"
}

# venv do sidecar (se ainda nao existir)
$venv = Join-Path $repo "sidecar\.venv\Scripts\python.exe"
if (-not (Test-Path $venv)) {
    Write-Host "== sidecar venv =="
    Set-Location (Join-Path $repo "sidecar")
    python -m venv .venv
    & .\.venv\Scripts\python.exe -m pip install --quiet -e .
}

Write-Host ""
Write-Host "Pronto! Abra 'aimscope' pelo atalho da area de trabalho ou do menu iniciar."
