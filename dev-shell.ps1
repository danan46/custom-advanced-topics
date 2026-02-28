$ErrorActionPreference = 'Stop'

$env:NVM_HOME = 'C:\Users\user\AppData\Local\nvm'
$env:NVM_SYMLINK = 'C:\nvm4w\nodejs'

if ($env:Path -notlike "*$env:NVM_HOME*") {
  $env:Path = "$env:NVM_HOME;$env:Path"
}
if ($env:Path -notlike "*$env:NVM_SYMLINK*") {
  $env:Path = "$env:NVM_SYMLINK;$env:Path"
}

$nvm = Join-Path $env:NVM_HOME 'nvm.exe'
if (-not (Test-Path $nvm)) {
  throw "nvm.exe non trovato in $env:NVM_HOME"
}

$desiredNode = (Get-Content .nvmrc -Raw).Trim()
if (-not $desiredNode) {
  $desiredNode = 'lts'
}

& $nvm install $desiredNode | Out-Null
& $nvm use $desiredNode | Out-Null

$venvActivate = Join-Path $PSScriptRoot '.venv311\Scripts\Activate.ps1'
if (-not (Test-Path $venvActivate)) {
  throw "Virtualenv non trovato: $venvActivate"
}

. $venvActivate

Write-Host "Node: $(node -v)"
Write-Host "NPM:  $(npm -v)"
Write-Host "Python: $(python --version)"
