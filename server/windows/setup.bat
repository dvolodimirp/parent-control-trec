@echo off
cd /d %~dp0..

echo === Parent Control server - setup ===

where py >nul 2>nul
if errorlevel 1 goto nopython

if exist venv goto haveenv
py -3 -m venv venv
:haveenv

call venv\Scripts\activate.bat
python -m pip install --upgrade pip >nul 2>nul
pip install -r requirements.txt

if exist config.bat goto havekey
call :makekey
:havekey

echo.
echo Setup finished. Run start.bat to start the server.
pause
exit /b 0

:nopython
echo Python was not found.
echo Install it from https://www.python.org/downloads/
echo During installation, check the box "Add python.exe to PATH".
pause
exit /b 1

:makekey
setlocal enabledelayedexpansion
echo.
set "KEY="
set /p "KEY=Choose an admin password for the parent panel: "
if "!KEY!"=="" set "KEY=change-me"
(
  echo @echo off
  echo set ADMIN_KEY=!KEY!
) > config.bat
endlocal
exit /b 0
