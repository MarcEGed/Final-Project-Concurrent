#!/usr/bin/env bash
set -e

# ── Navigate to backend relative to this script ──────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR/code/converter/backend-java"

# ── Find Java 17+ ─────────────────────────────────────────────────────────────
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_EXE="$JAVA_HOME/bin/java"
else
    JAVA_EXE="java"
fi

MAJOR=$("$JAVA_EXE" -version 2>&1 | awk -F'"' '/version/ {print $2}' | awk -F'.' '{print $1}')
if [ -z "$MAJOR" ] || [ "$MAJOR" -lt 17 ]; then
    echo ""
    echo "[ERROR] Java $MAJOR detected. Java 17 or newer is required."
    echo "        Install a JDK 17/21/23 and either:"
    echo "          - Put it first on your PATH, or"
    echo "          - Set JAVA_HOME before running this script."
    echo ""
    exit 1
fi

# ── Build if JAR is missing ───────────────────────────────────────────────────
if [ ! -f "build/libs/threadconv.jar" ]; then
    echo "[THREADCONV] No JAR found. Building now (first run only)..."
    echo ""
    chmod +x gradlew
    ./gradlew bootJar --no-daemon
    echo ""
fi

# ── Start backend ─────────────────────────────────────────────────────────────
echo "[THREADCONV] Starting backend..."
echo "  REST API  >  http://localhost:3001"
echo "  Socket.IO >  http://localhost:3002"
echo "  Press Ctrl+C to stop."
echo ""
"$JAVA_EXE" -jar build/libs/threadconv.jar
