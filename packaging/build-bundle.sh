#!/bin/bash
# ============================================================================
#  build-bundle.sh — assemble a self-contained TraceGuard bundle for macOS/Linux.
#
#  Produces  dist/TraceGuard-<os>/  (unzip-and-run: no install, no admin) with:
#      TraceGuard.command    the launcher (double-click on macOS)
#      app/traceguard.jar    the Spring Boot fat jar
#      jre/                  a trimmed Java runtime (jlink) — target needs no Java
#  ...and a dist/TraceGuard-<os>.zip.
#
#  Run on a BUILD machine with a full JDK 21 (jlink) and, unless --skip-build,
#  npm on PATH (Maven compiles the React frontend). The target needs none of that.
#  jlink builds a runtime for the OS it runs on — build the mac bundle on a Mac,
#  the linux bundle on Linux.
#
#  Usage:  ./packaging/build-bundle.sh [--skip-build] [--full]
# ============================================================================
set -euo pipefail
SKIP_BUILD=0; FULL=0
for a in "$@"; do
  case "$a" in
    --skip-build) SKIP_BUILD=1 ;;
    --full) FULL=1 ;;
    *) echo "Unknown arg: $a"; exit 2 ;;
  esac
done

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
case "$(uname -s)" in Darwin) OS=mac ;; Linux) OS=linux ;; *) OS=unix ;; esac
DIST="$ROOT/dist/TraceGuard-$OS"
echo "==> Repo root: $ROOT"

# --- Resolve a JDK with jlink -------------------------------------------------
JH="${JAVA_HOME:-}"
if [ -z "$JH" ] || [ ! -x "$JH/bin/jlink" ]; then
  JH="$(java -XshowSettings:properties 2>&1 | awk -F= '/java.home/{gsub(/ /,"",$2);print $2;exit}')"
fi
[ -x "$JH/bin/jlink" ] || { echo "No JDK with jlink found. Set JAVA_HOME to a JDK 21."; exit 1; }
echo "==> JDK: $JH"

# --- Build the fat jar --------------------------------------------------------
if [ "$SKIP_BUILD" -eq 0 ]; then
  command -v mvn >/dev/null 2>&1 || { echo "Maven (mvn) not on PATH. Install it or use --skip-build."; exit 1; }
  SETTINGS="$HOME/.traceguard/settings.json"
  if [ -f "$SETTINGS" ]; then
    NPM="$(grep -o '"npmToken"[^,}]*' "$SETTINGS" | sed 's/.*: *"//;s/".*//' || true)"
    if [ -n "${NPM:-}" ]; then export NPM_TOKEN="$NPM"; echo "==> npm token loaded from $SETTINGS (hidden)"; fi
  fi
  echo "==> mvn package"
  mvn -f "$ROOT/pom.xml" -DskipTests clean package
fi

JAR="$(ls -t "$ROOT"/target/*.jar 2>/dev/null | grep -Ev '\.original$|sources|javadoc' | head -1 || true)"
[ -n "$JAR" ] || { echo "No jar in target/. Run without --skip-build first."; exit 1; }
echo "==> Jar: $(basename "$JAR")"

# --- Fresh dist ---------------------------------------------------------------
rm -rf "$DIST"; mkdir -p "$DIST/app"

# --- jlink a trimmed runtime --------------------------------------------------
if [ "$FULL" -eq 1 ]; then
  MODS="ALL-MODULE-PATH"; MODPATH=(--module-path "$JH/jmods")
else
  MODS="java.base,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.transaction.xa,java.xml,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.unsupported,jdk.zipfs,jdk.jdwp.agent"
  MODPATH=()
fi
echo "==> jlink -> $DIST/jre"
# compress=zip-0 (uncompressed): larger on disk but classes load as fast as a full JDK.
"$JH/bin/jlink" "${MODPATH[@]}" --add-modules "$MODS" --strip-debug --no-header-files --no-man-pages --compress=zip-0 --output "$DIST/jre"
echo "==> generating CDS archive (faster class loading)"
"$DIST/jre/bin/java" -Xshare:dump >/dev/null 2>&1 || true

# --- Assemble -----------------------------------------------------------------
cp "$JAR" "$DIST/app/traceguard.jar"
cp "$HERE/launcher/TraceGuard.command" "$DIST/"
chmod +x "$DIST/TraceGuard.command"

# --- Zip ----------------------------------------------------------------------
( cd "$ROOT/dist" && rm -f "TraceGuard-$OS.zip" && zip -qr "TraceGuard-$OS.zip" "TraceGuard-$OS" )
SIZE="$(du -sh "$DIST" | cut -f1)"
echo ""
echo "==> Done. Bundle: $DIST  ($SIZE)"
echo "==> Zip:    $ROOT/dist/TraceGuard-$OS.zip"
echo "    On the target: unzip, then double-click TraceGuard.command (macOS: right-click > Open the first time)."
