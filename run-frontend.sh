#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR/code/converter/frontend"

echo "[THREADCONV] Installing frontend dependencies (skipped if already done)..."
npm install

echo ""
echo "[THREADCONV] Starting frontend..."
echo "  Open > http://localhost:3000"
echo "  Press Ctrl+C to stop."
echo ""
npm start
