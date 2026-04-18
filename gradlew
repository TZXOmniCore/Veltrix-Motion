#!/bin/sh
# Gradle wrapper script para Linux/Mac
# O GitHub Actions usa este arquivo para compilar o projeto

APP_HOME="$(cd "$(dirname "$0")" && pwd)"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
JAVA_OPTS="${JAVA_OPTS:-}"

exec "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
    -jar "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
    "$@"

# Forma correta: invocar via java
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
exec java $JAVA_OPTS -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain "$@"
