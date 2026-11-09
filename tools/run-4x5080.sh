#!/usr/bin/env bash
set -euo pipefail

# Run on the server with JDK 21, Maven, CMake >= 3.24 and CUDA 12.8–13.0 installed.
# All source, certificates, work directories and logs stay on /workspace.
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:/usr/local/cuda/bin:$PATH"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Xmx100g -XX:+UseG1GC -XX:ActiveProcessorCount=64"

mkdir -p /workspace
if [[ ! -d /workspace/td18/.git ]]; then
    git clone --branch td18-reduction-routing \
        https://github.com/luizaugustogarcia/td18.git /workspace/td18
fi
cd /workspace/td18
mkdir -p logs

# RTX 5080: build only the native sm_120 target.
cmake -S emitter/src/native -B emitter/target/native \
    -DCMAKE_BUILD_TYPE=Release -DTD18_CUDA_ARCHITECTURES=120-real \
    >> logs/emission.log 2>&1

# 32 active searches; 14 GiB of queue storage per GPU (56 GiB total).
# Keep the remaining VRAM available for CUDA contexts and allocator overhead.
# 64 additional GPU queue entries give 96 pending searches in total.
# 192 producers cover 32 active + 96 pending searches and 64 CPU workers.
# Set TD18_CPU_PREFIX_DEPTH=0 for GPU-first execution; CPU shortcuts usually
# improve total throughput, so depth four remains the default.
# Reuse certificates on subsequent runs; no existing data is deleted.
nohup ./tools/td18 emit -B -Dstyle.color=never \
    -Dtd18.gpuDevices=0,1,2,3 \
    -Dtd18.gpuSlots=8 \
    -Dtd18.gpuBudgetMB=1792 \
    -Dtd18.gpuQueueCapacity=64 \
    -Dtd18.twistedBraceletWorkers=192 \
    -Dtd18.cpuPrefixDepth="${TD18_CPU_PREFIX_DEPTH:-4}" \
    -Dtd18.cpuPrefixMaxTriples=8192 \
    -Dtd18.certificateQueueCapacity=1048576 \
    -Dtd18.certificateLookupBatchSize=262144 \
    -Dtd18.reconstructionBufferKeys=1048576 \
    -Dtd18.certificateFlushSeconds=5 \
    -Dtd18.certificateDb=/workspace/td18/certificates \
    -Dtd18.args=--phase=all \
    >> logs/emission.log 2>&1 < /dev/null &

printf '%s\n' "$!" > logs/emission.pid
printf 'Emission launched (PID %s).\n' "$!"
printf 'Follow: tail -F /workspace/td18/logs/emission.log\n'
