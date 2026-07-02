#!/usr/bin/env bash
# ParentOps one-click launcher (macOS/Linux).
cd "$(dirname "$0")"
if [ ! -d .venv ]; then
  echo "Creating virtual environment..."
  python3 -m venv .venv
fi
source .venv/bin/activate
pip install -q -r requirements.txt
echo
echo "ParentOps starting at http://localhost:8000"
echo "(phone on same Wi-Fi: http://$(hostname -I 2>/dev/null | awk '{print $1}'):8000)"
echo
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
