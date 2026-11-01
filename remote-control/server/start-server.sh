#!/usr/bin/env bash
# Mac / Linux launcher: creates a private Python environment on first run, then starts the server.
set -e
cd "$(dirname "$0")"
if [ ! -d .venv ]; then
  echo "First run: setting things up (needs internet, takes a minute)..."
  python3 -m venv .venv
  .venv/bin/pip install --quiet --upgrade pip
  .venv/bin/pip install --quiet -r requirements.txt
fi
exec .venv/bin/python laptop_remote_server.py "$@"
