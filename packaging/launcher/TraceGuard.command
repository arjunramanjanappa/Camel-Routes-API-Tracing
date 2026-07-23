#!/bin/bash
# ============================================================================
#  TraceGuard standalone launcher (macOS / Linux).
#  Double-click (macOS: this .command opens in Terminal) or run from a shell.
#  Starts the bundled server; the app opens your browser itself when ready.
#  No install, no admin. Close this window / press Ctrl-C to stop TraceGuard.
# ============================================================================
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
PORT=8080
URL="http://localhost:${PORT}/"

# --- Locate a Java runtime: bundled JRE first, then JAVA_HOME, then PATH ---
JAVA="$HERE/jre/bin/java"
if [ ! -x "$JAVA" ] && [ -n "$JAVA_HOME" ]; then JAVA="$JAVA_HOME/bin/java"; fi
if [ ! -x "$JAVA" ]; then JAVA="$(command -v java || true)"; fi
if [ -z "$JAVA" ] || { [ ! -x "$JAVA" ] && ! command -v "$JAVA" >/dev/null 2>&1; }; then
  echo "[TraceGuard] No Java runtime found. Expected a bundled ./jre next to this launcher."
  read -r -p "Press Enter to close..." _ || true
  exit 1
fi

# --- Locate the application jar ---
JAR="$HERE/app/traceguard.jar"
if [ ! -f "$JAR" ]; then JAR="$(ls "$HERE"/app/*.jar 2>/dev/null | head -1 || true)"; fi
if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
  echo "[TraceGuard] Could not find the application jar under $HERE/app/."
  read -r -p "Press Enter to close..." _ || true
  exit 1
fi

open_url() {
  if command -v open >/dev/null 2>&1; then open "$1"
  elif command -v xdg-open >/dev/null 2>&1; then xdg-open "$1"
  else echo "[TraceGuard] Open this in your browser: $1"; fi
}

# --- Already running? Just open the browser ---
if curl -sf -m 2 "$URL" >/dev/null 2>&1; then
  echo "[TraceGuard] Already running - opening $URL"
  open_url "$URL"; exit 0
fi

# --- Start the server; the app opens the browser itself when ready ---
echo "[TraceGuard] Starting... your browser will open automatically when it is ready."
echo "[TraceGuard] Keep this window open while you use TraceGuard. Ctrl-C to stop."
echo
exec "$JAVA" -Dtracer.open-browser=true -jar "$JAR"
