#!/usr/bin/env bash
set -euo pipefail

repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo"
mkdir -p logs checker/target
log=${TD18_CHECKER_LOG:-"$repo/logs/checker-verification.log"}
run=$(mktemp -d "$repo/checker/target/verification-run-XXXXXXXX")
exec > >(tee -a "$log") 2>&1
printf '%s\n' "$(date --iso-8601=seconds) CPU-only certificate verification; run=$run"
printf '%s\n' "$$" > "$run/pid"
printf '%s\n' "$run" > "$repo/checker/target/latest-verification-run"

mvn -B -pl checker -am package -DskipTests
mvn -B -pl checker dependency:build-classpath -DincludeScope=runtime \
  -Dmdep.outputFile=target/runtime-classpath.txt
classpath=$(cat checker/target/runtime-classpath.txt)
workers=${TD18_CHECKER_WORKERS:-12}
heap=${TD18_CHECKER_HEAP:-3g}
printf '%s\n' "$(date --iso-8601=seconds) checker starting: workers=$workers heap=$heap progressPeriod=60s certificate=$repo/certificates scratch=$run/work"

set +e
java -Xms256m "-Xmx$heap" -XX:+ExitOnOutOfMemoryError \
  -Dtd18.checkerStatsPeriodSeconds=60 \
  -Dtd18.certificateStatsPeriodSeconds=0 \
  "-Dtd18.checkerWorkers=$workers" \
  "-Dtd18.twistedBraceletWorkers=$workers" \
  -Dtd18.twistedBraceletBatchSize=256 \
  -cp "$repo/checker/target/classes:$classpath" br.unb.cic.td18.CertificateChecker \
  "--database=$repo/certificates" "--work=$run/work" "$@" &
checker_pid=$!
printf '%s\n' "$checker_pid" > "$run/checker.pid"
wait "$checker_pid"
result=$?
set -e
printf '%s\n' "$result" > "$run/exit-code"
printf '%s\n' "$(date --iso-8601=seconds) checker exited: code=$result run=$run"
exit "$result"
