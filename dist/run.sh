#!/usr/bin/env bash
# Launch script for the prebuilt JarRemapper distribution.
# Usage:  ./run.sh   (Linux/macOS)   or   run.bat   (Windows)
#
# Requires JDK 21 installed and JAVA_HOME set (or java on PATH).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB_DIR="$SCRIPT_DIR/lib"
APP_DIR="$SCRIPT_DIR/app"

# Find java
if [ -n "${JAVA_HOME:-}" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
else
  JAVA_BIN="$(command -v java)"
fi
if [ -z "$JAVA_BIN" ]; then
  echo "ERROR: java not found. Install JDK 21 or set JAVA_HOME." >&2
  exit 1
fi

# Build module path from the lib/ jars (JavaFX jars)
JAVAFX_MODULES="$(ls "$LIB_DIR"/javafx-*-linux.jar 2>/dev/null | tr '\n' ':' | sed 's/:$//')"
# macOS uses javafx-*-mac.jar; Windows uses javafx-*-win.jar
if [ -z "$JAVAFX_MODULES" ]; then
  JAVAFX_MODULES="$(ls "$LIB_DIR"/javafx-*-mac.jar 2>/dev/null | tr '\n' ':' | sed 's/:$//')"
fi

# Application classpath: app/ (our classes + resources) + lib/* (dependencies)
APP_CP="$APP_DIR:$LIB_DIR/*"

# JavaFX requires --module-path; we add the javafx jars as named modules.
exec "$JAVA_BIN" \
  -Dfile.encoding=UTF-8 \
  -Xmx1g \
  ${JAVAFX_MODULES:+--module-path "$JAVAFX_MODULES" --add-modules javafx.controls,javafx.fxml,javafx.graphics} \
  -cp "$APP_CP" \
  io.github.jarremapper.Main "$@"
