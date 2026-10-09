#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi
cd "$PROJECT_ROOT/frontend"
npm run build
cd "$PROJECT_ROOT/backend"
mvn -Dmaven.repo.local="$PROJECT_ROOT/.m2" test
