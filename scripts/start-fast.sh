#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -f "$PROJECT_ROOT/.env" ]]; then
  set -a
  . "$PROJECT_ROOT/.env"
  set +a
fi
if [[ -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi
JAR="$PROJECT_ROOT/backend/target/cycle-dispatch-0.1.0.jar"
if [[ ! -f "$JAR" ]]; then
  echo "还没有构建产物，先运行 ./scripts/start.sh 构建一次。" >&2
  exit 1
fi
cd "$PROJECT_ROOT/backend"
exec java -jar "$JAR"
