#!/bin/sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -n "${JAVA_HOME-}" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD="java"
fi
WRAPPER="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$WRAPPER" ]; then
  echo "Missing gradle-wrapper.jar" >&2
  exit 1
fi
exec "$JAVACMD" -Xmx64m -Xms64m -Dfile.encoding=UTF-8 \
  -classpath "$WRAPPER" org.gradle.wrapper.GradleWrapperMain "$@"