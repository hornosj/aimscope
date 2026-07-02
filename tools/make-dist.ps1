# Empacota um zip distribuível: exes + sidecar (fonte) + coach + setup.
# Uso:  powershell -ExecutionPolicy Bypass -File tools\make-dist.ps1
# Requisitos NA MAQUINA DE DESTINO: Python 3.11+ (sidecar) e, opcional,
# Clojure CLI + JDK (coach). O setup.ps1 incluso cria o venv na 1a execucao.
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$ver  = "0.2.0"
$out  = Join-Path $repo "dist\aimscope-v$ver"

Set-Location $repo
cargo build --release
if ($LASTEXITCODE -ne 0) { throw "cargo build falhou" }

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force "$out\bin" | Out-Null

Copy-Item "target\release\aimscope-ui.exe" "$out\bin\"
Copy-Item "target\release\aimscope.exe"    "$out\bin\"

# sidecar: fonte + pyproject (venv criado no destino pelo setup.ps1)
robocopy "sidecar" "$out\sidecar" /E /XD .venv __pycache__ .pytest_cache /XF *.pyc /NFL /NDL /NJH /NJS | Out-Null
# coach: fonte + catalogo (deps baixam do Maven na 1a execucao)
robocopy "coach" "$out\coach" /E /XD .cpcache /NFL /NDL /NJH /NJS | Out-Null
Copy-Item "README.md","CONTEXT.md" $out
robocopy "docs" "$out\docs" /E /NFL /NDL /NJH /NJS | Out-Null

@'
# aimscope - setup (rodar UMA vez)
$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
Set-Location "$here\sidecar"
python -m venv .venv
& .\.venv\Scripts\python.exe -m pip install --quiet -e .
$shell = New-Object -ComObject WScript.Shell
$lnk = $shell.CreateShortcut((Join-Path ([Environment]::GetFolderPath("Desktop")) "aimscope.lnk"))
$lnk.TargetPath = Join-Path $here "bin\aimscope-ui.exe"
$lnk.WorkingDirectory = $here
$lnk.Save()
Write-Host "Pronto! Atalho 'aimscope' criado na area de trabalho."
'@ | Out-File -Encoding utf8 "$out\setup.ps1"

Compress-Archive -Path $out -DestinationPath "$out.zip" -Force
Write-Host "dist: $out.zip"
