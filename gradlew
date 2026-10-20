#!/bin/sh
# Gradle wrapper bootstrap script for Catch
set -e

APP_HOME=$(cd "$(dirname "$0")" && pwd)
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ -f "$WRAPPER_JAR" ]; then
  exec java -Xmx64m -Xms64m -jar "$WRAPPER_JAR" "$@"
elif command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
else
  echo "Error: Neither gradle-wrapper.jar nor a system 'gradle' installation was found." >&2
  exit 1
fi
