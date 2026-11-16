#!/usr/bin/env bash
# Shared launch configuration; neither module depends on the other artifact.
td18_profile_main() {
    set -euo pipefail
    local module=$1 profile=$2 action=run resume= option phase=all
    shift 2
    local project_root default_base default_database architecture
    local max_heap max_writers initial_heap max_gc max_concurrent_gc
    local default_slots default_budget default_gpu_queue default_buffer default_queue
    local default_certificate_queue default_lookup default_pipelines
    local preparation_workers prepared_batches prefetch_batches seen_keys
    project_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
    case $profile in
        local)
            default_base=$project_root/target/local-runtime
            default_database=$project_root/certificates
            architecture=89
            max_heap=16 max_writers=8 initial_heap=2 max_gc=8 max_concurrent_gc=2
            default_slots=8 default_budget=768 default_gpu_queue=16
            default_buffer=4194304 default_queue=512 default_certificate_queue=262144
            default_lookup=32768 default_pipelines=4
            preparation_workers=4 prepared_batches=16 prefetch_batches=32 seen_keys=262144
            ;;
        5090)
            default_base=/workspace/td18
            default_database=$default_base/certificates
            architecture=120
            max_heap=160 max_writers=64 initial_heap=16 max_gc=64 max_concurrent_gc=16
            default_slots=32 default_budget=896 default_gpu_queue=64
            default_buffer=67108864 default_queue=32768 default_certificate_queue=1048576
            default_lookup=65536 default_pipelines=8
            preparation_workers=16 prepared_batches=128 prefetch_batches=256 seen_keys=1048576
            ;;
        *) printf 'Unknown runtime profile: %s\n' "$profile" >&2; return 2 ;;
    esac
    [[ $module == emitter || $module == checker ]] || return 2
    for option in "$@"; do
        case "$option" in
            --build) action=build ;;
            --dry-run) action=dry-run ;;
            --resume-work=*) resume=${option#*=} ;;
            --help|-h)
                printf 'Usage: tools/run-%s-{emitter,checker}.sh [--build|--dry-run] [--resume-work=PATH]\n' "$profile"
                printf '%s\n' "Default: full foreground run with output also saved under $default_base/logs." \
                    'Emitter resume requires a compatible sealed checkpoint; checker always starts fresh.'
                return ;;
            *) printf 'Unknown option: %s\n' "$option" >&2; return 2 ;;
        esac
    done
    if [[ $module == checker && -n $resume ]]; then
        printf '%s\n' 'The independent checker requires fresh work; resume is unsupported.' >&2
        return 2
    fi
    local base database native_dir
    base=${TD18_RUN_ROOT:-$default_base}
    if [[ $profile == 5090 ]]; then default_database=$base/certificates; fi
    database=${TD18_CERTIFICATE_DB:-$default_database}
    if [[ $profile == local ]]; then native_dir=$project_root/emitter/target/native;
    else native_dir=$base/build/native-$profile; fi
    cd -- "$project_root"

    # Respect affinity, CPU quota and memory limits on either host.
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
    (( default_heap > max_heap )) && default_heap=$max_heap
    local heap=${TD18_HEAP_GIB:-$default_heap}
    local writers=${TD18_WRITERS:-$((cpu < max_writers ? cpu : max_writers))}
    local slots=${TD18_GPU_SLOTS:-$default_slots} budget=${TD18_GPU_BUDGET_MB:-$default_budget}
    local gpu_queue=${TD18_GPU_QUEUE_CAPACITY:-$default_gpu_queue}
    local callers=$((2 * slots + gpu_queue))
    local initial=$((heap < initial_heap ? heap : initial_heap))
    local gc_threads=$((cpu < max_gc ? cpu : max_gc))
    local concurrent_gc=$(((cpu + 3) / 4))
    (( concurrent_gc > max_concurrent_gc )) && concurrent_gc=$max_concurrent_gc
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
        "-R-Dtd18.reconstructionBufferKeys=${TD18_BUFFER_KEYS:-$default_buffer}"
        "-R-Dtd18.reconstructionQueueBatches=${TD18_QUEUE_BATCHES:-$default_queue}"
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
            "-R-Dtd18.certificateQueueCapacity=$default_certificate_queue" -R-Dtd18.certificateFlushSeconds=5
            "-R-Dtd18.certificateWriters=${TD18_CERTIFICATE_WRITERS:-$default_slots}"
            "-R-Dtd18.certificateLookupBatchSize=${TD18_LOOKUP_BATCH_KEYS:-$default_lookup}"
            "-R-Dtd18.rootPrefetchWorkers=$callers" "-R-Dtd18.rootPrefetchPreparationWorkers=$preparation_workers"
            "-R-Dtd18.rootPrefetchPreparedQueueBatches=$prepared_batches" "-R-Dtd18.rootPrefetchQueueBatches=$prefetch_batches"
            "-R-Dtd18.rootPrefetchSeenKeys=$seen_keys" -R-Dtd18.rootPrefetchGpuMillis=100)
        if [[ -n $resume ]]; then phase=avoidance; invocation+=(emit --phase=avoidance "--resume-work=$resume");
        else invocation+=(emit --phase=all); fi
    else
        jvm+=("-R-Dtd18.checkerWorkers=$cpu" -R-Dtd18.checkerStatsPeriodSeconds=10
            "-R-Dtd18.certificateLookupBatchSize=${TD18_LOOKUP_BATCH_KEYS:-$default_lookup}"
            "-R-Dtd18.certificateLookupPipelines=${TD18_LOOKUP_PIPELINES:-$default_pipelines}")
        invocation+=(verify --phase=all)
    fi
    invocation+=("--database=$database" "--work=$work")
    printf -v TD18_JSHELL_OPTIONS '%s ' "${jvm[@]}"
    export TD18_JSHELL_OPTIONS
    if [[ $action == dry-run ]]; then
        printf 'profile=%s module=%s cpuWorkers=%s heapGiB=%s writers=%s\n' "$profile" "$module" "$cpu" "$heap" "$writers"
        [[ $module != emitter ]] || printf 'gpuSlots=%s queueMiBPerSlot=%s aggregateQueueGiB=%s GPUCallers=%s\n' \
            "$slots" "$budget" "$((slots * budget / 1024))" "$callers"
        [[ $module != emitter ]] || printf 'nativeArchitecture=sm_%s nativeBuild=%s\n' "$architecture" "$native_dir"
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
            nvcc --list-gpu-code | grep -Fx "sm_$architecture" >/dev/null || {
                printf 'The installed CUDA toolkit does not support sm_%s.\n' "$architecture" >&2; return 2;
            }
            local host_compiler=${TD18_CUDA_HOST_COMPILER:-}
            if [[ $profile == local && -z $host_compiler ]] && command -v g++-12 >/dev/null; then
                host_compiler=$(command -v g++-12)
            fi
            local -a compiler_options=()
            [[ -z $host_compiler ]] || compiler_options+=("-DCMAKE_CUDA_HOST_COMPILER=$host_compiler")
            cmake -S emitter/src/native -B "$native_dir" -DCMAKE_BUILD_TYPE=Release \
                "-DTD18_CUDA_ARCHITECTURES=$architecture-real;$architecture-virtual" \
                "-DTD18_BUILD_NATIVE_TESTS=${TD18_BUILD_NATIVE_TESTS:-OFF}" "${compiler_options[@]}"
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
    python3 - "$module" "$work" "$database" "$settings" "$$" "$phase" "$profile" "$native_dir" <<'PY'
import datetime, hashlib, json, os, pathlib, subprocess, sys
settings = dict(format='td18-'+sys.argv[7]+'-run-v1', profile=sys.argv[7], module=sys.argv[1], work=sys.argv[2],
    database=sys.argv[3], launcherPid=int(sys.argv[5]), phase=sys.argv[6], status='starting',
    startedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
    revision=subprocess.check_output(['git','rev-parse','HEAD']).decode().strip(),
    jshellOptions=os.environ['TD18_JSHELL_OPTIONS'].split(), walEnabled=False,
    cpuAffinity=sorted(os.sched_getaffinity(0)),
    javaVersion=subprocess.run(['java','-version'], capture_output=True, text=True, check=True).stderr,
    platform=list(os.uname()))
classes=pathlib.Path(sys.argv[1])/'target/classes'
settings['javaClassSha256']={str(p.relative_to(classes)):hashlib.sha256(p.read_bytes()).hexdigest()
                            for p in sorted(classes.rglob('*.class'))}
settings['sourceStatus']=subprocess.check_output(['git','status','--porcelain']).decode()
if sys.argv[1]=='emitter':
    native=pathlib.Path(sys.argv[8])/'libtd18_jni.so'
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
