#!/bin/sh
# Runs the gesture-logic tests on a PC (needs JDK 11+). No Android needed.
set -e
cd "$(dirname "$0")/.."
OUT=$(mktemp -d)
java -m jdk.compiler/com.sun.tools.javac.Main -d "$OUT" \
  app/src/main/java/com/airpalm/app/GestureEngine.java test/GestureEngineTest.java
java -cp "$OUT" com.airpalm.app.GestureEngineTest
