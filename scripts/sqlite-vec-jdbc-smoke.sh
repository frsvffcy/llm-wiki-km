#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <path-to-vec0.so-or-dylib>" >&2
  exit 2
fi

extension_path=$1
if [[ ! -f "$extension_path" ]]; then
  echo "sqlite-vec extension does not exist: $extension_path" >&2
  exit 2
fi

java_feature=$(java -XshowSettings:properties -version 2>&1 \
  | sed -n 's/^ *java.specification.version = //p')
if [[ "$java_feature" != "21" ]]; then
  echo "sqlite-vec smoke requires Java 21; found ${java_feature:-unknown}" >&2
  exit 2
fi

smoke_tmp=$(mktemp -d "${TMPDIR:-/tmp}/sqlite-vec-jdbc-smoke.XXXXXX")
trap 'rm -rf "$smoke_tmp"' EXIT

mvn --batch-mode -q dependency:build-classpath \
  -Dmdep.outputFile="$smoke_tmp/classpath" \
  -Dmdep.includeScope=runtime
javac --release 21 -cp "$(<"$smoke_tmp/classpath")" \
  -d "$smoke_tmp/classes" scripts/sqlite-vec-jdbc-smoke.java
java -cp "$(<"$smoke_tmp/classpath"):$smoke_tmp/classes" \
  SqliteVecJdbcSmoke "$extension_path"
