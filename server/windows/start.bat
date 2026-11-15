@echo off
cd /d %~dp0..

if not exist venv (
  echo Run setup.bat first.
  pause
  exit /b 1
)

call venv\Scripts\activate.bat
call config.bat

echo.
echo Parent panel address:
echo   http://localhost:8000   (on this computer)
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /c:"IPv4"') do echo   http://%%a:8000   (from a phone on the same Wi-Fi)
echo.
echo On first run, Windows Defender Firewall may ask for permission.
echo Click "Allow access" for private networks.
echo.

python -m uvicorn main:app --host 0.0.0.0 --port 8000
pause
