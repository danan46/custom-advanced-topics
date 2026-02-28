# Setup ambiente

## Prerequisiti
- Python 3.11 installato
- nvm-windows installato

## Node (nvm)
1. `nvm install`
2. `nvm use`
3. `node -v`

## Python (venv311)
1. `./.venv311/Scripts/Activate.ps1`
2. `python --version`
3. `pip --version`

## Flusso rapido (PowerShell)
```powershell
nvm use
./.venv311/Scripts/Activate.ps1
```

## Flusso automatico (consigliato)
```powershell
powershell -ExecutionPolicy Bypass -File .\dev-shell.ps1
```
