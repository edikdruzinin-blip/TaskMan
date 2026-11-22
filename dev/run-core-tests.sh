#!/bin/sh
# Re-checks the parsers, the CPU maths and the shell protocol against this machine's /proc.
# Needs a JDK and kotlinc (https://kotlinlang.org/docs/command-line.html).
# Optional: TM_SHELL=/path/to/mksh-wrapper to run the helper script under another shell.
set -e
cd "$(dirname "$0")/.."
JAR="${TMPDIR:-/tmp}/taskman-core-tests.jar"
kotlinc app/src/main/java/com/taskman/root/core/*.kt dev/core-tests/Main.kt -include-runtime -d "$JAR"
java -jar "$JAR"
