@echo off
rem Windows launcher: creates a private Python environment on first run, then starts the server.
cd /d "%~dp0"
if not exist .venv (
  echo First run: setting things up ^(needs internet, takes a minute^)...
  python -m venv .venv || goto :nopython
  .venv\Scripts\python -m pip install --quiet --upgrade pip
  .venv\Scripts\python -m pip install --quiet -r requirements.txt
)
.venv\Scripts\python laptop_remote_server.py %*
goto :eof
:nopython
echo.
echo Python 3.8 or newer is required. Install it from https://www.python.org/downloads/
echo (tick "Add python.exe to PATH" in the installer) and run this file again.
pause
