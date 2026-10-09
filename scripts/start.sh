#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi
cd "$PROJECT_ROOT/frontend"
if [[ ! -d node_modules ]]; then npm ci --cache "$PROJECT_ROOT/.npm-cache" --no-audit --no-fund; fi
npm run build
cd "$PROJECT_ROOT/backend"
mvn -Dmaven.repo.local="$PROJECT_ROOT/.m2" -DskipTests package -q
exec java -jar target/cycle-dispatch-0.1.0.jar
