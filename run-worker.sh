#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
API_DIR="$SCRIPT_DIR/code/converter/backend-java"
WORKER_DIR="$SCRIPT_DIR/code/converter/backend-worker"
JAR="$WORKER_DIR/build/libs/threadconv-worker.jar"

if [ -n "${JAVA_HOME:-}" ]; then
    JAVA_EXE="$JAVA_HOME/bin/java"
else
    JAVA_EXE="java"
fi

if [ ! -f "$JAR" ]; then
    echo "[THREADCONV-WORKER] No JAR found. Building now (first run only)..."
    echo
    cd "$API_DIR"
    chmod +x gradlew
    ./gradlew -p "$WORKER_DIR" bootJar --no-daemon
    echo
fi

echo "[THREADCONV-WORKER] Starting worker service..."
echo "  Conversion Worker  >  http://localhost:3003"
echo "  Press Ctrl+C to stop."
echo

cd "$WORKER_DIR"
exec "$JAVA_EXE" -jar "$JAR"
