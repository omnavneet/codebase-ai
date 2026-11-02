#!/usr/bin/env bash
# Usage: scripts/test.sh [service_dir] [extra test args]
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="${1:-.}"; [ $# -gt 0 ] && shift
cd "$ROOT/$DIR" || exit 2
LOG="$ROOT/.ai/test.log"; mkdir -p "$ROOT/.ai"
if [ -f pom.xml ]; then CMD=(./mvnw -q test); [ -x ./mvnw ] || CMD=(mvn -q test)
elif [ -f gradlew ]; then CMD=(./gradlew test --console=plain)
elif [ -f package.json ]; then CMD=(npm test --silent --)
elif [ -f pyproject.toml ] || [ -f pytest.ini ] || [ -f requirements.txt ]; then CMD=(python -m pytest -q -x --tb=short)
elif [ -f go.mod ]; then CMD=(go test ./...)
elif [ -f Cargo.toml ]; then CMD=(cargo test --quiet)
else echo "No test runner detected in $DIR"; exit 2; fi
"${CMD[@]}" "$@" > "$LOG" 2>&1
STATUS=$?
echo "exit=$STATUS  full log: .ai/test.log"
grep -E "FAIL|ERROR|Error|failed|passed|Tests run:|BUILD" "$LOG" | head -40
[ $STATUS -ne 0 ] && tail -20 "$LOG"
exit $STATUS
