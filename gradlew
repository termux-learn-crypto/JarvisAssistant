#!/bin/bash
APP_HOME=/data/data/com.termux/files/home/JarvisAssistant
JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk
JAVACMD="$JAVA_HOME/bin/java"
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
exec "$JAVACMD" -Xmx2048m -Xms256m -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
