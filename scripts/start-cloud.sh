#!/usr/bin/env bash
set -euo pipefail

if [[ -d /usr/lib/jvm/java-21-openjdk-amd64 ]]; then
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
  export PATH="$JAVA_HOME/bin:/usr/local/bin:$PATH"
fi

for binary in java mvn node npm; do
  if ! command -v "$binary" >/dev/null 2>&1; then
    echo "Cloud Studio 缺少 $binary；请先安装 Java 21、Maven 3.9 和 Node.js 22（含 npm）。" >&2
    exit 1
  fi
done

export PORT="${PORT:-8080}"
export SERVER_ADDRESS=0.0.0.0
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$PROJECT_ROOT/.runtime"
exec > >(tee "$PROJECT_ROOT/.runtime/cloudstudio-start.log") 2>&1
exec bash "$(dirname "${BASH_SOURCE[0]}")/start.sh"
