#!/usr/bin/env bash
# Shared launch configuration; neither module depends on the other artifact.
td18_5090_main() {
    set -euo pipefail
    local module=$1 action=run resume= option phase=all
    shift
    for option in "$@"; do
        case "$option" in
            --build) action=build ;;
            --dry-run) action=dry-run ;;
            --resume-work=*) resume=${option#*=} ;;
            --help|-h)
                printf '%s\n' 'Usage: tools/run-5090-{emitter,checker}.sh [--build|--dry-run] [--resume-work=PATH]' \
                    'Default: full foreground run with output also saved under /workspace/td18/logs.' \
                    'Emitter resume requires a compatible sealed checkpoint; checker always starts fresh.'
                return ;;
            *) printf 'Unknown option: %s\n' "$option" >&2; return 2 ;;
        esac
    done
    if [[ $module == checker && -n $resume ]]; then
        printf '%s\n' 'The independent checker requires fresh work; resume is unsupported.' >&2
        return 2
    fi
    local project_root base database native_dir
    project_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
    base=${TD18_RUN_ROOT:-/workspace/td18}
    database=${TD18_CERTIFICATE_DB:-$base/certificates}
    native_dir=$base/build/native-5090
    cd -- "$project_root"

    # Respect affinity, CPU quota and memory limits inside the rented container.
    local detected_cpu detected_memory
    read -r detected_cpu detected_memory < <(python3 - <<'PY'
import os, pathlib
cpu = len(os.sched_getaffinity(0))
memory = int(next(l.split()[1] for l in pathlib.Path('/proc/meminfo').read_text().splitlines()
                  if l.startswith('MemTotal:'))) * 1024
for path in ('/sys/fs/cgroup/cpu.max',):
    try:
        quota, period = pathlib.Path(path).read_text().split()
        if quota != 'max': cpu = min(cpu, max(1, int(quota) // int(period)))
    except (OSError, ValueError): pass
try:
    quota = int(pathlib.Path('/sys/fs/cgroup/cpu/cpu.cfs_quota_us').read_text())
    period = int(pathlib.Path('/sys/fs/cgroup/cpu/cpu.cfs_period_us').read_text())
    if quota > 0: cpu = min(cpu, max(1, quota // period))
except (OSError, ValueError): pass
for path in ('/sys/fs/cgroup/memory.max', '/sys/fs/cgroup/memory/memory.limit_in_bytes'):
    try:
        value = pathlib.Path(path).read_text().strip()
        if value != 'max': memory = min(memory, int(value))
    except (OSError, ValueError): pass
print(cpu, memory // (1024**3))
PY
    )
    local cpu=${TD18_CPU_WORKERS:-$detected_cpu}
    local default_heap=$((detected_memory * 5 / 8))
    (( default_heap > 160 )) && default_heap=160
    local heap=${TD18_HEAP_GIB:-$default_heap}
    local writers=${TD18_WRITERS:-$((cpu < 64 ? cpu : 64))}
    local slots=${TD18_GPU_SLOTS:-32} budget=${TD18_GPU_BUDGET_MB:-896}
    local gpu_queue=${TD18_GPU_QUEUE_CAPACITY:-64}
    local callers=$((2 * slots + gpu_queue))
    local initial=$((heap < 16 ? heap : 16))
    local gc_threads=$((cpu < 64 ? cpu : 64))
    local concurrent_gc=$(((cpu + 3) / 4))
    (( concurrent_gc > 16 )) && concurrent_gc=16
    local value
    for value in "$cpu" "$heap" "$writers" "$slots" "$budget"; do
        [[ $value =~ ^[1-9][0-9]*$ ]] || { printf 'Invalid positive setting: %s\n' "$value" >&2; return 2; }
    done
    [[ $gpu_queue =~ ^[0-9]+$ ]] || return 2
    (( heap + 8 <= detected_memory )) || { printf '%s\n' 'Heap must leave at least 8 GiB outside Java.' >&2; return 2; }
    [[ $base = /* && $database = /* ]] || { printf '%s\n' 'Run root and database paths must be absolute.' >&2; return 2; }
    [[ $base != *[[:space:]]* && $database != *[[:space:]]* ]] || {
        printf '%s\n' 'JShell launcher option paths must not contain whitespace.' >&2; return 2;
    }

    local stamp work log settings
    stamp=$(date -u +%Y%m%dT%H%M%SZ)-$$
    work=$base/work/$module-$stamp
    log=$base/logs/$module-$stamp.log
    settings=$base/logs/$module-$stamp.json
    local -a jvm=(
        -J-Xmx512m "-R-Xms${initial}g" "-R-Xmx${heap}g"
        -R-XX:+UseG1GC -R-XX:+ParallelRefProcEnabled -R-XX:MaxGCPauseMillis=200
        "-R-XX:ActiveProcessorCount=$cpu" "-R-XX:ParallelGCThreads=$gc_threads"
        "-R-XX:ConcGCThreads=$concurrent_gc" -R-Duser.timezone=Europe/Lisbon
        "-R-Djava.io.tmpdir=$base/tmp" "-R-Dtd18.twistedBraceletWorkers=$cpu"
        "-R-Dtd18.certificateDb=$database" -R-Dtd18.dbWriteBatchEntries=524288
        "-R-Dtd18.reconstructionWriters=$writers"
        "-R-Dtd18.reconstructionBufferKeys=${TD18_BUFFER_KEYS:-67108864}"
        "-R-Dtd18.reconstructionQueueBatches=${TD18_QUEUE_BATCHES:-32768}"
        "-R-Dtd18.reconstructionGeneratedBatchKeys=${TD18_GENERATED_BATCH_KEYS:-65536}"
        "-R-Dtd18.reconstructionScanBatchKeys=${TD18_SCAN_BATCH_KEYS:-4096}"
    )
    local -a invocation=(./tools/td18)
    if [[ $module == emitter ]]; then
        jvm+=("-R-Djava.library.path=$native_dir" -R-Dtd18.gpuDevices=0
            "-R-Dtd18.gpuSlots=$slots" "-R-Dtd18.gpuBudgetMB=$budget"
            "-R-Dtd18.gpuQueueCapacity=$gpu_queue" -R-Dtd18.gpuStatsPeriodSeconds=10
            "-R-Dtd18.reconstructionGpuWorkers=$callers" "-R-Dtd18.reconstructionInverseWorkers=$cpu"
            -R-Dtd18.cpuPrefixMaxTriples=8192 -R-Dtd18.reconstructionCpuPrefixMillis=10
            -R-Dtd18.reconstructionGpuMillis=0 -R-Dtd18.cpuStatsPeriodSeconds=10
            -R-Dtd18.certificateQueueCapacity=1048576 -R-Dtd18.certificateFlushSeconds=5
            "-R-Dtd18.certificateWriters=${TD18_CERTIFICATE_WRITERS:-32}"
            "-R-Dtd18.certificateLookupBatchSize=${TD18_LOOKUP_BATCH_KEYS:-65536}"
            "-R-Dtd18.rootPrefetchWorkers=$callers" -R-Dtd18.rootPrefetchPreparationWorkers=16
            -R-Dtd18.rootPrefetchPreparedQueueBatches=128 -R-Dtd18.rootPrefetchQueueBatches=256
            -R-Dtd18.rootPrefetchSeenKeys=1048576 -R-Dtd18.rootPrefetchGpuMillis=100)
        if [[ -n $resume ]]; then phase=avoidance; invocation+=(emit --phase=avoidance "--resume-work=$resume");
        else invocation+=(emit --phase=all); fi
    else
        jvm+=("-R-Dtd18.checkerWorkers=$cpu" -R-Dtd18.checkerStatsPeriodSeconds=10
            "-R-Dtd18.certificateLookupBatchSize=${TD18_LOOKUP_BATCH_KEYS:-65536}"
            "-R-Dtd18.certificateLookupPipelines=${TD18_LOOKUP_PIPELINES:-8}")
        invocation+=(verify --phase=all)
    fi
    invocation+=("--database=$database" "--work=$work")
    printf -v TD18_JSHELL_OPTIONS '%s ' "${jvm[@]}"
    export TD18_JSHELL_OPTIONS
    if [[ $action == dry-run ]]; then
        printf 'module=%s cpuWorkers=%s heapGiB=%s writers=%s\n' "$module" "$cpu" "$heap" "$writers"
        [[ $module != emitter ]] || printf 'gpuSlots=%s queueMiBPerSlot=%s aggregateQueueGiB=%s GPUCallers=%s\n' \
            "$slots" "$budget" "$((slots * budget / 1024))" "$callers"
        printf 'log=%s\nsettings=%s\n' "$log" "$settings"
        printf 'TD18_JSHELL_OPTIONS=%q\n' "$TD18_JSHELL_OPTIONS"
        printf '%q ' "${invocation[@]}"; printf '\n'
        return
    fi

    if [[ -z ${JAVA_HOME:-} && -x /usr/lib/jvm/java-21-openjdk-amd64/bin/java ]]; then
        export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
    fi
    [[ -z ${JAVA_HOME:-} ]] || export PATH="$JAVA_HOME/bin:$PATH"
    local java_version
    java_version=$(java -version 2>&1)
    [[ $java_version =~ version\ \"21[.\"] ]] || {
        printf '%s\n' 'Set JAVA_HOME to JDK 21 (including jshell and javac).' >&2; return 2;
    }
    mkdir -p "$base/logs" "$base/work" "$base/tmp" "$base/build"
    exec 9>"$base/run.lock"
    flock -n 9 || { printf '%s\n' 'Another emitter/checker run or build holds the workspace lock.' >&2; return 2; }
    if [[ $action == build ]]; then
        mvn -q -pl "$module" -am compile dependency:build-classpath -Dmdep.outputFile=target/jshell-classpath
        if [[ $module == emitter ]]; then
            command -v nvcc >/dev/null
            nvcc --version | python3 -c 'import re,sys; m=re.search(r"release (\d+)\.(\d+)",sys.stdin.read()); v=tuple(map(int,m.groups())) if m else (0,0); sys.exit(0 if (12,8)<=v<=(13,0) else "Use CUDA Toolkit 12.8 through 13.0 for this driver profile")'
            nvcc --list-gpu-code | grep -q 'sm_120'
            cmake -S emitter/src/native -B "$native_dir" -DCMAKE_BUILD_TYPE=Release \
                '-DTD18_CUDA_ARCHITECTURES=120-real;120-virtual'
            cmake --build "$native_dir" --parallel "$cpu"
        fi
        return
    fi
    test -f "$module/target/jshell-classpath"
    if [[ $module == emitter ]]; then
        test -f "$native_dir/libtd18_jni.so"
        nvidia-smi --query-gpu=name,memory.free,memory.total,driver_version --format=csv
        # Native preflight additionally checks exact queue, cache and metadata bytes.
        local free_gpu
        free_gpu=$(nvidia-smi --id=0 --query-gpu=memory.free --format=csv,noheader,nounits)
        (( free_gpu >= slots * (budget + 12) + 1024 )) || {
            printf '%s\n' 'Insufficient free VRAM for queues, state caches and reserve.' >&2; return 2;
        }
    else
        test -f "$database/CURRENT"
    fi
    local free_disk
    free_disk=$(df -Pk "$base" | awk 'NR==2 {print $4}')
    (( free_disk >= ${TD18_MIN_FREE_GIB:-32} * 1024 * 1024 )) || {
        printf '%s\n' 'Insufficient free workspace storage; no data was removed.' >&2; return 2;
    }
    touch "$log"
    ln -sfn "$(basename -- "$log")" "$base/logs/$module-latest.log"
    printf '%s\n' "$$" > "$base/logs/$module.pid"
    python3 - "$module" "$work" "$database" "$settings" "$$" "$phase" <<'PY'
import datetime, hashlib, json, os, pathlib, subprocess, sys
settings = dict(format='td18-5090-run-v1', module=sys.argv[1], work=sys.argv[2],
    database=sys.argv[3], launcherPid=int(sys.argv[5]), phase=sys.argv[6], status='starting',
    startedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
    revision=subprocess.check_output(['git','rev-parse','HEAD']).decode().strip(),
    jshellOptions=os.environ['TD18_JSHELL_OPTIONS'].split(), walEnabled=False)
classes=pathlib.Path(sys.argv[1])/'target/classes'
settings['javaClassSha256']={str(p.relative_to(classes)):hashlib.sha256(p.read_bytes()).hexdigest()
                            for p in classes.rglob('*.class')}
settings['sourceStatus']=subprocess.check_output(['git','status','--porcelain']).decode()
if sys.argv[1]=='emitter':
    native=pathlib.Path(sys.argv[2]).parent.parent/'build/native-5090/libtd18_jni.so'
    settings['nativeSha256']=hashlib.sha256(native.read_bytes()).hexdigest()
pathlib.Path(sys.argv[4]).write_text(json.dumps(settings, indent=2)+'\n')
PY
    printf 'INFO %s starting; work=%s; log=%s; WAL=disabled\n' "$module" "$work" "$log" | tee -a "$log"
    local result=0 marker
    local -a pipeline_codes
    if "${invocation[@]}" 2>&1 | tee -a "$log"; then result=0;
    else
        pipeline_codes=("${PIPESTATUS[@]}")
        result=${pipeline_codes[0]}
        (( result != 0 )) || result=1
    fi
    if [[ $module == checker ]]; then marker='All regenerated obligations in the complete joint-selection scope are verified.';
    else marker='Requested emission scope completed:'; fi
    # JShell termination alone is not a successful verification or emission.
    if (( result == 0 )) && ! grep -Fq "$marker" "$log"; then result=1; fi
    python3 - "$settings" "$result" <<'PY'
import datetime, json, pathlib, sys
p=pathlib.Path(sys.argv[1]); settings=json.loads(p.read_text())
settings.update(exitCode=int(sys.argv[2]), status='complete' if sys.argv[2]=='0' else 'failed',
    finishedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat())
p.write_text(json.dumps(settings, indent=2)+'\n')
PY
    printf 'INFO %s exited: code=%s\n' "$module" "$result" | tee -a "$log"
    return "$result"
}
