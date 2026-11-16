#!/usr/bin/env bash
set -euo pipefail
cd /home/luiz/Projects/others/td18
exec 9>logs/emitter-local.lock
flock -n 9
td18_stamp=$(date -u +%Y%m%dT%H%M%SZ)-$$
td18_run=$PWD/emitter/target/local-emission-$td18_stamp
td18_log=$PWD/logs/emitter-local-$td18_stamp.log
td18_settings=$PWD/logs/emitter-local-$td18_stamp.json
test ! -e certificates
mkdir -p "$td18_run/classes" "$td18_run/tmp" "$td18_run/native"
cp emitter/target/native/libtd18_jni.so "$td18_run/native/"
cp -a emitter/target/classes/. "$td18_run/classes/"
td18_classpath="$td18_run/classes:$(cat emitter/target/jshell-classpath)"
td18_database=$PWD/certificates
td18_work=$td18_run/obligations
td18_jvm=(
    -J-Xmx512m -R-Xms2g -R-Xmx16g
    -R-XX:+UseG1GC -R-XX:+ParallelRefProcEnabled -R-XX:MaxGCPauseMillis=200
    -R-XX:ActiveProcessorCount=22 -R-XX:ParallelGCThreads=8 -R-XX:ConcGCThreads=2
    -R-Duser.timezone=Europe/Lisbon "-R-Djava.io.tmpdir=$td18_run/tmp"
    -R-Dtd18.workers=22 -R-Dtd18.twistedBraceletWorkers=22
    -R-Dtd18.certificateLookupBatchSize=32768 -R-Dtd18.certificateLookupPipelines=4
    -R-Dtd18.reconstructionWriters=8 -R-Dtd18.reconstructionBufferKeys=4194304
    -R-Dtd18.reconstructionQueueBatches=512 -R-Dtd18.reconstructionGeneratedBatchKeys=65536
    -R-Dtd18.reconstructionScanBatchKeys=4096 -R-Dtd18.dbWriteBatchEntries=524288
    "-R-Djava.library.path=$td18_run/native"
    -R-Dtd18.gpuDevices=0 -R-Dtd18.gpuSlots=8 -R-Dtd18.gpuBudgetMB=768
    -R-Dtd18.gpuQueueCapacity=16 -R-Dtd18.reconstructionGpuWorkers=32
    -R-Dtd18.reconstructionInverseWorkers=22 -R-Dtd18.reconstructionGpuMillis=0
    -R-Dtd18.cpuPrefixMaxTriples=8192 -R-Dtd18.reconstructionCpuPrefixMillis=10
    -R-Dtd18.certificateWriters=8 -R-Dtd18.certificateQueueCapacity=262144
    -R-Dtd18.rootPrefetchWorkers=32 -R-Dtd18.rootPrefetchPreparationWorkers=4
    -R-Dtd18.rootPrefetchPreparedQueueBatches=16 -R-Dtd18.rootPrefetchQueueBatches=32
    -R-Dtd18.rootPrefetchSeenKeys=262144 -R-Dtd18.rootPrefetchGpuMillis=100
    -R-Dtd18.gpuStatsPeriodSeconds=10 -R-Dtd18.cpuStatsPeriodSeconds=10
    "-R-Xlog:gc*:file=$PWD/logs/emitter-local-$td18_stamp-gc.log:time,uptime,level,tags:filecount=3,filesize=32M"
)
touch "$td18_log"
ln -sfn "$(basename "$td18_log")" logs/emitter-local-latest.log
printf '%s\n' "$$" > logs/emitter-local.pid
python3 - "$td18_settings" "$td18_run" "$td18_database" "$td18_work" "$$" "${td18_jvm[@]}" <<'PY'
import datetime,hashlib,json,os,pathlib,subprocess,sys
classes=pathlib.Path(sys.argv[2])/'classes'
d=dict(format='td18-local-emitter-run-v1',status='running',machine='Intel Core Ultra 9 185H / WSL2',launcherPid=int(sys.argv[5]),database=sys.argv[3],work=sys.argv[4],phase='all',startedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),revision=subprocess.check_output(['git','rev-parse','HEAD']).decode().strip(),sourceStatus=subprocess.check_output(['git','status','--porcelain']).decode(),cpuWorkers=22,gpu='RTX 4070 Laptop',gpuSlots=8,gpuQueueMiBPerSlot=768,heapGiB=16,initialHeapGiB=2,writers=8,bufferKeys=4194304,queueBatches=512,lookupBatchKeys=32768,lookupPipelines=4,walEnabled=False,certificateReadOnly=False,cpuAffinity=sorted(os.sched_getaffinity(0)),jshellOptions=sys.argv[6:],javaClassSha256={str(p.relative_to(classes)):hashlib.sha256(p.read_bytes()).hexdigest() for p in classes.rglob('*.class')})
pathlib.Path(sys.argv[1]).write_text(json.dumps(d,indent=2)+'\n')
PY
python3 - "$td18_database" "$td18_work" > "$td18_run/verify.jsh" <<'PY'
import json,sys
args=','.join(json.dumps(x) for x in ('--phase=all','--database='+sys.argv[1],'--work='+sys.argv[2]))
print('int status=1;')
print('try { br.unb.cic.td18.WitnessEmitter.main(new String[]{'+args+'}); status=0; } catch(Throwable error) { error.printStackTrace(); }')
print('/exit status')
PY
printf 'INFO local full emitter: workers=22 heap=16GiB writers=8 bufferKeys=4194304 queueBatches=512; database=%s; work=%s\n' "$td18_database" "$td18_work" >> "$td18_log"
td18_result=0
jshell --feedback concise --class-path "$td18_classpath" "${td18_jvm[@]}" "$td18_run/verify.jsh" >> "$td18_log" 2>&1 || td18_result=$?
if (( td18_result==0 )) && ! grep -Fq 'Requested emission scope completed:' "$td18_log"; then td18_result=1; fi
python3 - "$td18_settings" "$td18_result" <<'PY'
import datetime,json,pathlib,sys
p=pathlib.Path(sys.argv[1]);d=json.loads(p.read_text());d.update(exitCode=int(sys.argv[2]),status='complete' if sys.argv[2]=='0' else 'failed',finishedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat());p.write_text(json.dumps(d,indent=2)+'\n')
PY
printf 'INFO emitter exited: code=%s\n' "$td18_result" >> "$td18_log"
exit "$td18_result"
