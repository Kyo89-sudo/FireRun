@echo off
rem Doppio clic: chiede l'IP del Fire Stick e lancia deploy.ps1 (installa/aggiorna FireRun).
set /p FSIP=Indirizzo IP del Fire Stick:
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy.ps1" -FireStick %FSIP% -AttivaTastoHome
pause
