#!/usr/bin/env bash
# Runtime profile for 200.20.15.153. Build Java and CUDA before launching.
project_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
source "$HOME/.bashrc"
set -euo pipefail
cd -- "$project_root"
export JAVA_HOME="${JAVA_HOME:-/home/luiskowada/jdk-21.0.8}"
gcc_root="${TD18_GCC_ROOT:-/home/luiskowada/gcc-9.4}"
cuda_root="${TD18_CUDA_ROOT:-/home/luiskowada/cuda-11.3}"
export PATH="$JAVA_HOME/bin:$gcc_root/bin:$cuda_root/bin:$PATH"
export LD_LIBRARY_PATH="$gcc_root/lib64:$cuda_root/lib64:${LD_LIBRARY_PATH:-}"
cpu_workers=$(getconf _NPROCESSORS_ONLN)
export JAVA_TOOL_OPTIONS="-Xmx128g -XX:+UseG1GC -XX:ActiveProcessorCount=$cpu_workers"
unset CUDA_VISIBLE_DEVICES
mkdir -p logs
exec 9>logs/simulation.lock
flock -n 9
test -f emitter/target/native/libtd18_jni.so
test -f emitter/target/jshell-classpath
run_stamp=$(date -u +%Y%m%dT%H%M%SZ)
run_work="$project_root/emitter/target/full-emission-$run_stamp"
test ! -e "$run_work"
jvm_options=(
    -R-Dtd18.gpuDevices=1,2,3 -R-Dtd18.gpuSlots=16 -R-Dtd18.gpuBudgetMB=896
    -R-Dtd18.gpuQueueCapacity=64 -R-Dtd18.gpuStatsPeriodSeconds=10
    -R-Dtd18.cpuPrefixMaxTriples=8192 -R-Dtd18.reconstructionCpuPrefixMillis=10
    -R-Dtd18.reconstructionGpuMillis=0 -R-Dtd18.cpuStatsPeriodSeconds=10
    "-R-Dtd18.twistedBraceletWorkers=$((cpu_workers + 48 + 64))"
    -R-Dtd18.reconstructionGpuWorkers=160
    "-R-Dtd18.reconstructionInverseWorkers=$cpu_workers"
    "-R-Dtd18.certificateDb=$project_root/certificates"
    -R-Dtd18.certificateFlushSeconds=5 -R-Dtd18.certificateQueueCapacity=1048576
    -R-Dtd18.certificateLookupBatchSize=262144
    -R-Dtd18.reconstructionBufferKeys=67108864 -R-Dtd18.reconstructionWriters=64
    -R-Dtd18.reconstructionQueueBatches=32768 -R-Dtd18.reconstructionGeneratedBatchKeys=65536
    -R-Dtd18.rootPrefetchWorkers=160 -R-Dtd18.rootPrefetchPreparationWorkers=8
    -R-Dtd18.rootPrefetchPreparedQueueBatches=1024
    -R-Dtd18.rootPrefetchQueueBatches=256 -R-Dtd18.rootPrefetchSeenKeys=1048576
    -R-Dtd18.rootPrefetchGpuMillis=100
)
printf -v TD18_JSHELL_OPTIONS '%s ' "${jvm_options[@]}"
export TD18_JSHELL_OPTIONS
printf '%s\n' "$$" > logs/launcher.pid
python3 - "$project_root" "$run_work" "$$" "$cpu_workers" <<'PY'
import datetime, hashlib, json, pathlib, subprocess, sys
root=pathlib.Path(sys.argv[1])
sources=subprocess.check_output(['git','ls-files','-z'],cwd=str(root)).decode().split(chr(0))
settings={
    'format':'td18-full-run-settings-v1',
    'revision':subprocess.check_output(['git','rev-parse','HEAD'],cwd=str(root)).decode().strip(),
    'phase':'all','status':'starting full emission from scratch','work':sys.argv[2],
    'launcherPid':int(sys.argv[3]),'executionPid':None,'cpuCores':int(sys.argv[4]),
    'startedUtc':datetime.datetime.utcnow().isoformat()+'Z',
    'gpuDevices':[1,2,3],'gpuSlots':16,'gpuBudgetMB':896,'gpuQueueCapacity':64,
    'gpuIntermediateDepths':[8,9],'gpuIntermediateMillis':0,'gpuFinalDepth':10,'gpuFinalMillis':0,
    'walEnabled':False,'javaHeapGiB':128,
    'reconstructionWriters':64,'reconstructionBufferKeys':67108864,
    'reconstructionGeneratedBatchKeys':65536,'reconstructionQueueBatches':32768,
    'reconstructionInverseWorkers':int(sys.argv[4]),
    'rootPrefetchWorkers':160,'rootPrefetchPreparationWorkers':8,
    'rootPrefetchEncodedQueueKeys':16777216,'rootPrefetchPreparedQueueKeys':4194304,
    'nativeSha256':hashlib.sha256((root/'emitter/target/native/libtd18_jni.so').read_bytes()).hexdigest(),
    'sourceFileSha256':{p:hashlib.sha256((root/p).read_bytes()).hexdigest() for p in sources if p},
    'javaClassSha256':{str(p.relative_to(root/'emitter/target/classes')):hashlib.sha256(p.read_bytes()).hexdigest()
        for p in (root/'emitter/target/classes').rglob('*.class')}
}
(root/'logs/run-settings.json').write_text(json.dumps(settings,indent=2,sort_keys=True)+chr(10))
PY
printf 'INFO Full emission from scratch: devices=1,2,3, slots/device=16, queue MiB/slot=896, WAL=disabled, work=%s\n' "$run_work"
exec ./tools/td18 emit --phase=all "--work=$run_work"
