@echo off
REM ParentOps one-click launcher (Windows).
cd /d %~dp0
if not exist .venv (
  echo Creating virtual environment...
  py -m venv .venv || python -m venv .venv
)
call .venv\Scripts\activate
pip install -q -r requirements.txt
echo.
echo ParentOps starting at http://localhost:8000
echo (phone on same Wi-Fi: http://YOUR-PC-IP:8000)
echo.
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
