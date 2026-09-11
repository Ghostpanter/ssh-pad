#!/bin/sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DEFAULT_JVM_OPTS='"-Xmx2048m" "-Xms256m" "-Dfile.encoding=UTF-8"'
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
exec "$JAVACMD" $DEFAULT_JVM_OPTS ${JAVA_OPTS-} ${GRADLE_OPTS-} \
  -classpath "$WRAPPER" org.gradle.wrapper.GradleWrapperMain "$@"
