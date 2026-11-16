#!/usr/bin/env bash
source "$HOME/.bashrc"
set -euo pipefail
cd /home/luiz/Projects/td18
exec 9>checker/target/reference-checker.lock
flock -n 9
stamp=$(date -u +%Y%m%dT%H%M%SZ)
run="$PWD/checker/target/reference-verification-$stamp"
mkdir -p "$run/classes" "$run/tmp"
cp -a checker/target/classes/. "$run/classes/"
classpath="$run/classes:$(cat checker/target/jshell-classpath)"
options=(
 -J-Xmx256m -R-Xms512m -R-Xmx6g
 -R-XX:+UseG1GC -R-XX:MaxGCPauseMillis=200
 -R-XX:ActiveProcessorCount=16 -R-XX:ParallelGCThreads=8 -R-XX:ConcGCThreads=2
 -R-Duser.timezone=Europe/Lisbon "-R-Djava.io.tmpdir=$run/tmp"
 -R-Dtd18.checkerWorkers=16 -R-Dtd18.twistedBraceletWorkers=16
 -R-Dtd18.certificateLookupBatchSize=32768 -R-Dtd18.certificateLookupPipelines=4
 -R-Dtd18.reconstructionWriters=4 -R-Dtd18.reconstructionBufferKeys=1048576
 -R-Dtd18.reconstructionQueueBatches=64 -R-Dtd18.reconstructionGeneratedBatchKeys=8192
 -R-Dtd18.reconstructionScanBatchKeys=4096 -R-Dtd18.dbWriteBatchEntries=262144
 -R-Dtd18.checkerStatsPeriodSeconds=10
 "-R-Xlog:gc:file=$PWD/logs/reference/checker-gc.log:time,uptime,level,tags"
)
test ! -e logs/reference/checker-settings.txt
{
 printf 'format=td18-reference-checker-v1\nstatus=running\nstartedUtc=%s\n' "$(date -u --iso-8601=ns)"
 printf 'sourceRevision=%s\nwork=%s\ncertificateDatabase=%s\ntests=skipped by user request\n' "$(git rev-parse HEAD)" "$run/obligations" "$PWD/certificates"
 printf 'jshellOptions='; printf '%q ' "${options[@]}"; printf '\n'
 uname -a
 lscpu
 free -b
 java -version 2>&1
 taskset -pc $$
} > logs/reference/checker-settings.txt
cat > "$run/verify.jsh" <<EOF
int status=1;
try { br.unb.cic.td18.CertificateChecker.main(new String[]{"--phase=all","--database=$PWD/certificates","--work=$run/obligations"}); status=0; } catch(Throwable error) { error.printStackTrace(); }
/exit status
EOF
start=$SECONDS
result=0
jshell --feedback concise --class-path "$classpath" "${options[@]}" "$run/verify.jsh" > logs/reference/checker.log 2>&1 &
checker_pid=$!
printf '%s\n' "$checker_pid" > checker/target/reference-checker.pid
(
 while kill -0 "$checker_pid" 2>/dev/null; do
  date -u --iso-8601=seconds
  ps -p "$checker_pid" --ppid "$checker_pid" -o pid,ppid,pcpu,rss,comm
  free -b
  cat /proc/pressure/memory
  sleep 30
 done
) > logs/reference/checker-resources.log 2>&1 &
monitor_pid=$!
wait "$checker_pid" || result=$?
kill "$monitor_pid" 2>/dev/null || true
wait "$monitor_pid" 2>/dev/null || true
if ((result==0)) && ! rg -Fq 'All regenerated obligations in the complete joint-selection scope are verified.' logs/reference/checker.log; then result=1; fi
{
 printf 'finishedUtc=%s\nelapsedSeconds=%s\nexitCode=%s\nstatus=%s\n' "$(date -u --iso-8601=ns)" "$((SECONDS-start))" "$result" "$([[ $result == 0 ]] && echo complete || echo failed)"
} >> logs/reference/checker-settings.txt
if ((result==0)); then
 {
  printf '\n## Completed reference verification on the separate machine\n\n'
  printf 'The independent full-scope CPU checker exited 0 after regenerating all obligations with fresh work and replaying the read-only certificate database. Both inverse branches on both endpoints are included.\n\n'
  printf 'Machine: AMD Ryzen 7 8840HS, 16 logical processors, WSL2 Linux; JDK 21.0.13.0.3. JShell used a 6 GiB maximum heap, 16 checker and bracelet workers, 4 lookup pipelines with 32,768 keys per batch, and 4 persistence writers with 1,048,576 buffered keys and 64 queued batches. Tests were skipped at user request.\n\n'
  printf 'Timing and revision:\n\n```text\n'
  rg '^(startedUtc|finishedUtc|elapsedSeconds|sourceRevision|exitCode)=' logs/reference/checker-settings.txt
  printf '```\n\nRegenerated inverse coverage:\n\n```text\n'
  rg 'Eriksson inverse coverage verified:' logs/reference/checker.log
  printf '```\n\nFull machine configuration and execution details are in logs/reference/checker-settings.txt; verification, resource, and GC logs and the launcher are retained alongside it. This completed record supersedes the pending separate-machine verification statements above. All other evidence checklist requirements remain in force.\n'
 } >> docs/REFERENCE_SIMULATION.md
fi
exit "$result"
