#include <cuda_runtime.h>
#include <jni.h>

#include <thrust/device_vector.h>
#include <thrust/execution_policy.h>
#include <thrust/remove.h>
#include <thrust/sort.h>
#include <thrust/unique.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include "core/cuda_error.cuh"

namespace {

constexpr int MAX_DOMAIN = 19;
constexpr int INPUT_RECORD_BYTES = 20;
constexpr int KEY_RECORD_BYTES = 13;
constexpr uint8_t INVALID_DOMAIN = 0xff;
constexpr int MAX_BUDGET = 10;
constexpr int ROOT_SYMBOLS = 19;
constexpr int THREADS = 256;

enum ReductionKind : uint8_t {
    ORIENTED_THREE = 0,
    ORIENTED_ODD = 1,
    EVEN_PAIR = 2
};

struct InputRecord {
    uint8_t domain;
    uint8_t omega[MAX_DOMAIN];
};

static_assert(sizeof(InputRecord) == INPUT_RECORD_BYTES);

struct ReconstructionTask {
    uint32_t inputIndex;
    uint8_t fixedCount;
    uint8_t gaps[3];
    uint8_t first;
    uint8_t second;
    uint8_t kind;
};

struct CanonicalKey {
    uint8_t domain;
    uint8_t omega[MAX_DOMAIN];
};

static_assert(sizeof(CanonicalKey) == 20);

struct HostCycle {
    std::vector<uint8_t> symbols;
};

struct PolicyPair {
    uint64_t parentType;
    uint64_t childAndKind;
};

__host__ __device__ int compare_key(const CanonicalKey &left,
                                   const CanonicalKey &right) {
    if (left.domain != right.domain) {
        return left.domain < right.domain ? -1 : 1;
    }
    for (int index = 0; index < MAX_DOMAIN; ++index) {
        if (left.omega[index] != right.omega[index]) {
            return left.omega[index] < right.omega[index] ? -1 : 1;
        }
    }
    return 0;
}

struct KeyLess {
    __host__ __device__ bool operator()(const CanonicalKey &left,
                                        const CanonicalKey &right) const {
        return compare_key(left, right) < 0;
    }
};

struct KeyEqual {
    __host__ __device__ bool operator()(const CanonicalKey &left,
                                        const CanonicalKey &right) const {
        return compare_key(left, right) == 0;
    }
};

struct InvalidKey {
    __host__ __device__ bool operator()(const CanonicalKey &key) const {
        return key.domain == INVALID_DOMAIN;
    }
};

int known_diameter(const int symbols) {
    constexpr int td[] = {0, 0, 1, 2, 3, 3, 4, 4, 5,
                          5, 6, 6, 7, 8, 8, 9, 9, 10};
    if (symbols <= 1) return 0;
    if (symbols >= ROOT_SYMBOLS) return std::numeric_limits<int>::max();
    return td[symbols - 1];
}

std::vector<HostCycle> decompose_host(const InputRecord &input) {
    std::vector<HostCycle> result;
    bool visited[MAX_DOMAIN]{};
    for (int start = 0; start < input.domain; ++start) {
        if (visited[start]) continue;
        HostCycle cycle;
        int current = start;
        do {
            if (current < 0 || current >= input.domain || visited[current]) {
                throw std::invalid_argument(
                        "GPU avoidance input omega is not a permutation");
            }
            visited[current] = true;
            cycle.symbols.push_back(static_cast<uint8_t>(current));
            current = input.omega[current];
        } while (current != start);
        result.push_back(std::move(cycle));
    }
    return result;
}

void add_task(std::vector<ReconstructionTask> &tasks, const uint32_t inputIndex,
              const int fixedCount, const int firstGap, const int secondGap,
              const int thirdGap, const int first, const int second,
              const ReductionKind kind) {
    ReconstructionTask task{};
    task.inputIndex = inputIndex;
    task.fixedCount = static_cast<uint8_t>(fixedCount);
    task.gaps[0] = static_cast<uint8_t>(firstGap);
    task.gaps[1] = static_cast<uint8_t>(secondGap);
    task.gaps[2] = static_cast<uint8_t>(thirdGap);
    task.first = static_cast<uint8_t>(first);
    task.second = static_cast<uint8_t>(second);
    task.kind = static_cast<uint8_t>(kind);
    tasks.push_back(task);
}

void enumerate_tasks_for_count(
        std::vector<ReconstructionTask> &tasks, const uint32_t inputIndex,
        const InputRecord &input, const std::vector<HostCycle> &cycles,
        const int fixedCount) {
    const int n = input.domain;
    if (fixedCount == 1) {
        for (int gap = 0; gap < n; ++gap) {
            for (size_t firstCycle = 0; firstCycle < cycles.size(); ++firstCycle) {
                const auto &left = cycles[firstCycle].symbols;
                if (left.size() <= 1 || left.size() % 2 == 0) continue;
                for (size_t secondCycle = firstCycle + 1;
                     secondCycle < cycles.size(); ++secondCycle) {
                    const auto &right = cycles[secondCycle].symbols;
                    if (right.size() <= 1 || right.size() % 2 == 0) continue;
                    for (const int first : left) {
                        for (const int second : right) {
                            add_task(tasks, inputIndex, 1, gap, 0, 0,
                                     first, second, ORIENTED_ODD);
                        }
                    }
                }
                for (size_t first = 0; first + 1 < left.size(); ++first) {
                    for (size_t second = first + 1; second < left.size(); ++second) {
                        add_task(tasks, inputIndex, 1, gap, 0, 0,
                                 left[first], left[second], EVEN_PAIR);
                    }
                }
            }
        }
        return;
    }
    if (fixedCount == 2) {
        for (int firstGap = 0; firstGap < n; ++firstGap) {
            for (int secondGap = firstGap; secondGap < n; ++secondGap) {
                for (const auto &cycle : cycles) {
                    if (cycle.symbols.size() <= 1
                        || cycle.symbols.size() % 2 == 0) continue;
                    for (const int symbol : cycle.symbols) {
                        add_task(tasks, inputIndex, 2, firstGap, secondGap, 0,
                                 symbol, 0, ORIENTED_ODD);
                    }
                }
            }
        }
        return;
    }
    for (int firstGap = 0; firstGap < n; ++firstGap) {
        for (int secondGap = firstGap; secondGap < n; ++secondGap) {
            for (int thirdGap = secondGap; thirdGap < n; ++thirdGap) {
                add_task(tasks, inputIndex, 3, firstGap, secondGap, thirdGap,
                         0, 0, ORIENTED_THREE);
            }
        }
    }
}

std::vector<ReconstructionTask> enumerate_tasks(
        const std::vector<InputRecord> &inputs, const int maximumMoves) {
    const int parentMovesUsed = MAX_BUDGET - maximumMoves - 1;
    std::vector<ReconstructionTask> tasks;
    tasks.reserve(inputs.size() * 1024);
    for (uint32_t inputIndex = 0; inputIndex < inputs.size(); ++inputIndex) {
        const auto &input = inputs[inputIndex];
        if (input.domain == 0 || input.domain > MAX_DOMAIN) {
            throw std::invalid_argument(
                    "GPU avoidance input domain lies outside 1..19");
        }
        const auto cycles = decompose_host(input);
        for (int fixedCount = 1; fixedCount <= 3; ++fixedCount) {
            const int parentSymbols = input.domain + fixedCount;
            const int earlierContraction = ROOT_SYMBOLS - parentSymbols;
            if (parentSymbols > ROOT_SYMBOLS
                || earlierContraction < parentMovesUsed
                || earlierContraction > 3 * parentMovesUsed
                || known_diameter(parentSymbols) <= maximumMoves + 1) {
                continue;
            }
            enumerate_tasks_for_count(tasks, inputIndex, input, cycles,
                                      fixedCount);
        }
    }
    return tasks;
}

__device__ bool cyclic_order(const uint8_t *cycle, const int n,
                             const uint8_t *symbols, const int count) {
    if (count <= 1) return true;
    uint8_t positions[MAX_DOMAIN];
    for (int index = 0; index < n; ++index) positions[cycle[index]] = index;
    int wraps = 0;
    for (int index = 0; index < count; ++index) {
        if (positions[symbols[index]] > positions[symbols[(index + 1) % count]]
            && ++wraps > 1) return false;
    }
    return true;
}

__device__ void compose_move_inverse(const uint8_t *omega, const int n,
                                     const uint8_t first, const uint8_t second,
                                     const uint8_t third, uint8_t *result) {
    for (int symbol = 0; symbol < n; ++symbol) result[symbol] = omega[symbol];
    result[first] = omega[third];
    result[second] = omega[first];
    result[third] = omega[second];
}

__device__ void apply_transposition(const uint8_t *beta, const int n,
                                    int first, int second, int third,
                                    uint8_t *result) {
    if (first > second) { const int value = first; first = second; second = value; }
    if (second > third) { const int value = second; second = third; third = value; }
    if (first > second) { const int value = first; first = second; second = value; }
    const int leftLength = second - first;
    const int rightLength = third - second;
    for (int position = 0; position < n; ++position) {
        int source = position;
        if (position >= first && position < first + rightLength) {
            source += leftLength;
        } else if (position >= first + rightLength && position < third) {
            source -= rightLength;
        }
        result[position] = beta[source];
    }
}

__device__ bool equal_cycle(const uint8_t *left, const uint8_t *right,
                            const int n) {
    int shift = -1;
    for (int index = 0; index < n; ++index) {
        if (right[index] == left[0]) { shift = index; break; }
    }
    if (shift < 0) return false;
    for (int index = 0; index < n; ++index) {
        if (left[index] != right[(shift + index) % n]) return false;
    }
    return true;
}

__device__ bool forward_replays(
        const uint8_t *parentOmega, const uint8_t *parentBeta,
        const uint8_t *restoredOmega, const uint8_t *restoredBeta, const int n,
        const uint8_t supportFirst, const uint8_t supportSecond,
        const uint8_t supportThird) {
    const uint8_t support[3] = {supportFirst, supportSecond, supportThird};
    const bool ordered = cyclic_order(parentBeta, n, support, 3);
    const uint8_t first = supportFirst;
    const uint8_t second = ordered ? supportSecond : supportThird;
    const uint8_t third = ordered ? supportThird : supportSecond;
    uint8_t replayedOmega[MAX_DOMAIN];
    compose_move_inverse(parentOmega, n, first, second, third, replayedOmega);
    for (int symbol = 0; symbol < n; ++symbol) {
        if (replayedOmega[symbol] != restoredOmega[symbol]) return false;
    }
    uint8_t positions[MAX_DOMAIN];
    for (int index = 0; index < n; ++index) positions[parentBeta[index]] = index;
    uint8_t replayedBeta[MAX_DOMAIN];
    apply_transposition(parentBeta, n, positions[first], positions[second],
                        positions[third], replayedBeta);
    return equal_cycle(replayedBeta, restoredBeta, n);
}

__device__ bool target_is_cycle(const uint8_t *omega, const uint8_t *beta,
                                const int n) {
    uint8_t betaImage[MAX_DOMAIN];
    for (int index = 0; index < n; ++index) {
        betaImage[beta[index]] = beta[(index + 1) % n];
    }
    bool visited[MAX_DOMAIN]{};
    int current = 0;
    for (int count = 0; count < n; ++count) {
        if (visited[current]) return false;
        visited[current] = true;
        current = omega[betaImage[current]];
    }
    return current == 0;
}

__device__ int decompose(const uint8_t *omega, const int n,
                         uint8_t cycles[MAX_DOMAIN][MAX_DOMAIN],
                         uint8_t *lengths, uint8_t *cycleBySymbol) {
    bool visited[MAX_DOMAIN]{};
    int cycleCount = 0;
    for (int start = 0; start < n; ++start) {
        if (visited[start]) continue;
        int current = start;
        int length = 0;
        do {
            if (current < 0 || current >= n || visited[current]) return -1;
            visited[current] = true;
            cycleBySymbol[current] = cycleCount;
            cycles[cycleCount][length++] = current;
            current = omega[current];
        } while (current != start);
        lengths[cycleCount++] = length;
    }
    return cycleCount;
}

__device__ bool cycle_is_oriented(const uint8_t *beta, const int n,
                                  const uint8_t *symbols, const int length) {
    if (length <= 2) return false;
    uint8_t inversePositions[MAX_DOMAIN];
    for (int position = 0; position < n; ++position) {
        inversePositions[beta[position]] = n - 1 - position;
    }
    int wraps = 0;
    for (int index = 0; index < length; ++index) {
        if (inversePositions[symbols[index]]
            > inversePositions[symbols[(index + 1) % length]]
            && ++wraps > 1) return true;
    }
    return false;
}

__device__ uint64_t annotated_type(const uint8_t *omega, const uint8_t *beta,
                                   const int n) {
    uint8_t cycles[MAX_DOMAIN][MAX_DOMAIN];
    uint8_t lengths[MAX_DOMAIN]{};
    uint8_t cycleBySymbol[MAX_DOMAIN]{};
    const int totalCycles = decompose(omega, n, cycles, lengths, cycleBySymbol);
    if (totalCycles < 0) return UINT64_MAX;
    uint8_t keptLengths[9]{};
    bool keptOriented[9]{};
    int kept = 0;
    for (int cycle = 0; cycle < totalCycles; ++cycle) {
        if (lengths[cycle] <= 1) continue;
        if (kept >= 9) return UINT64_MAX;
        keptLengths[kept] = lengths[cycle];
        keptOriented[kept] = cycle_is_oriented(
                beta, n, cycles[cycle], lengths[cycle]);
        ++kept;
    }
    for (int first = 0; first < kept; ++first) {
        for (int second = first + 1; second < kept; ++second) {
            if (keptLengths[second] > keptLengths[first]
                || (keptLengths[second] == keptLengths[first]
                    && !keptOriented[second] && keptOriented[first])) {
                const uint8_t length = keptLengths[first];
                keptLengths[first] = keptLengths[second];
                keptLengths[second] = length;
                const bool oriented = keptOriented[first];
                keptOriented[first] = keptOriented[second];
                keptOriented[second] = oriented;
            }
        }
    }
    uint64_t packed = kept;
    for (int index = 0; index < kept; ++index) {
        const uint64_t entry = keptLengths[index]
                | (keptOriented[index] ? 0x20ULL : 0ULL);
        packed |= entry << (4 + 6 * index);
    }
    return packed;
}

__device__ bool accepted_transition(
        const uint64_t parentType, const uint64_t childAndKind,
        const uint64_t *policyParents, const uint64_t *policyChildren,
        const int policyCount) {
    int low = 0;
    int high = policyCount;
    while (low < high) {
        const int middle = low + (high - low) / 2;
        const uint64_t candidateParent = policyParents[middle];
        const uint64_t candidateChild = policyChildren[middle];
        if (candidateParent < parentType
            || (candidateParent == parentType
                && candidateChild < childAndKind)) {
            low = middle + 1;
        } else {
            high = middle;
        }
    }
    return low < policyCount && policyParents[low] == parentType
            && policyChildren[low] == childAndKind;
}

__device__ void transformed_word(
        const uint8_t *baseLabels, const uint8_t *baseRanks,
        const uint8_t *sizesByOldLabel, const int n, const int shift,
        const bool reflected, uint8_t *labels, uint8_t *ranks) {
    uint8_t newLabelByOld[MAX_DOMAIN + 1]{};
    uint8_t originByOld[MAX_DOMAIN + 1]{};
    bool originSet[MAX_DOMAIN + 1]{};
    uint8_t nextLabel = 1;
    for (int offset = 0; offset < n; ++offset) {
        int position = reflected ? (-shift - offset) % n : (shift + offset) % n;
        if (position < 0) position += n;
        const uint8_t oldLabel = baseLabels[position];
        if (newLabelByOld[oldLabel] == 0) {
            newLabelByOld[oldLabel] = nextLabel++;
        }
        labels[offset] = newLabelByOld[oldLabel];
        const uint8_t oldRank = baseRanks[position];
        if (oldRank == 0) {
            ranks[offset] = 0;
            continue;
        }
        const int size = sizesByOldLabel[oldLabel];
        const int zeroRank = oldRank - 1;
        const int transformed = reflected
                ? (size - zeroRank) % size : zeroRank;
        if (!originSet[oldLabel]) {
            originByOld[oldLabel] = transformed;
            originSet[oldLabel] = true;
        }
        ranks[offset] = (transformed - originByOld[oldLabel] + size) % size + 1;
    }
}

__device__ void fixed_color_symbols(const uint8_t *labels,
                                    const uint8_t *ranks, const int n,
                                    uint8_t *symbols) {
    uint8_t sizes[MAX_DOMAIN + 1]{};
    bool oriented[MAX_DOMAIN + 1]{};
    int colorCount = 0;
    for (int position = 0; position < n; ++position) {
        const uint8_t label = labels[position];
        if (sizes[label]++ == 0) ++colorCount;
        oriented[label] = oriented[label] || ranks[position] != 0;
    }
    uint8_t colors[MAX_DOMAIN];
    for (int label = 1; label <= colorCount; ++label) colors[label - 1] = label;
    for (int first = 0; first < colorCount; ++first) {
        for (int second = first + 1; second < colorCount; ++second) {
            const int left = colors[first];
            const int right = colors[second];
            if (sizes[right] > sizes[left]
                || (sizes[right] == sizes[left]
                    && oriented[right] < oriented[left])
                || (sizes[right] == sizes[left]
                    && oriented[right] == oriented[left] && right < left)) {
                const uint8_t value = colors[first];
                colors[first] = colors[second];
                colors[second] = value;
            }
        }
    }
    uint8_t base[MAX_DOMAIN + 1]{};
    uint8_t next = 0;
    for (int index = 0; index < colorCount; ++index) {
        const int label = colors[index];
        base[label] = next;
        next += oriented[label] ? sizes[label] : 1;
    }
    for (int position = 0; position < n; ++position) {
        const int label = labels[position];
        symbols[position] = base[label]
                + (oriented[label] ? ranks[position] - 1 : 0);
    }
}

__device__ bool lexicographically_less(const uint8_t *left,
                                        const uint8_t *right, const int n) {
    for (int index = 0; index < n; ++index) {
        if (left[index] != right[index]) return left[index] < right[index];
    }
    return false;
}

__device__ bool canonicalize(const uint8_t *omega, const uint8_t *beta,
                             const int n, uint8_t *canonicalOmega) {
    uint8_t cycles[MAX_DOMAIN][MAX_DOMAIN];
    uint8_t cycleLengths[MAX_DOMAIN]{};
    uint8_t cycleBySymbol[MAX_DOMAIN]{};
    const int cycleCount = decompose(
            omega, n, cycles, cycleLengths, cycleBySymbol);
    if (cycleCount <= 0) return false;

    bool cycleOriented[MAX_DOMAIN]{};
    for (int cycle = 0; cycle < cycleCount; ++cycle) {
        cycleOriented[cycle] = cycle_is_oriented(
                beta, n, cycles[cycle], cycleLengths[cycle]);
    }
    uint8_t labelByCycle[MAX_DOMAIN]{};
    uint8_t baseLabels[MAX_DOMAIN]{};
    uint8_t baseRanks[MAX_DOMAIN]{};
    uint8_t sizesByLabel[MAX_DOMAIN + 1]{};
    uint8_t nextLabel = 1;
    for (int position = 0; position < n; ++position) {
        const int symbol = beta[position];
        const int cycle = cycleBySymbol[symbol];
        if (labelByCycle[cycle] == 0) {
            labelByCycle[cycle] = nextLabel;
            sizesByLabel[nextLabel] = cycleLengths[cycle];
            ++nextLabel;
        }
        baseLabels[position] = labelByCycle[cycle];
    }
    for (int cycle = 0; cycle < cycleCount; ++cycle) {
        if (!cycleOriented[cycle]) continue;
        int firstPosition = n;
        int firstCycleIndex = 0;
        for (int cycleIndex = 0; cycleIndex < cycleLengths[cycle]; ++cycleIndex) {
            const int symbol = cycles[cycle][cycleIndex];
            for (int position = 0; position < n; ++position) {
                if (beta[position] == symbol && position < firstPosition) {
                    firstPosition = position;
                    firstCycleIndex = cycleIndex;
                }
            }
        }
        for (int offset = 0; offset < cycleLengths[cycle]; ++offset) {
            const int symbol = cycles[cycle][
                    (firstCycleIndex + offset) % cycleLengths[cycle]];
            for (int position = 0; position < n; ++position) {
                if (beta[position] == symbol) baseRanks[position] = offset + 1;
            }
        }
    }

    bool haveBest = false;
    uint8_t bestSymbols[MAX_DOMAIN]{};
    uint8_t bestLabels[MAX_DOMAIN]{};
    uint8_t bestRanks[MAX_DOMAIN]{};
    for (int shift = 0; shift < n; ++shift) {
        for (int reflectedValue = 0; reflectedValue < 2; ++reflectedValue) {
            uint8_t labels[MAX_DOMAIN]{};
            uint8_t ranks[MAX_DOMAIN]{};
            uint8_t symbols[MAX_DOMAIN]{};
            transformed_word(baseLabels, baseRanks, sizesByLabel, n, shift,
                             reflectedValue != 0, labels, ranks);
            fixed_color_symbols(labels, ranks, n, symbols);
            if (!haveBest || lexicographically_less(symbols, bestSymbols, n)) {
                haveBest = true;
                for (int position = 0; position < n; ++position) {
                    bestSymbols[position] = symbols[position];
                    bestLabels[position] = labels[position];
                    bestRanks[position] = ranks[position];
                }
            }
        }
    }
    if (!haveBest) return false;

    for (int symbol = 0; symbol < n; ++symbol) canonicalOmega[symbol] = symbol;
    uint8_t colorCount = 0;
    for (int position = 0; position < n; ++position) {
        if (bestLabels[position] > colorCount) colorCount = bestLabels[position];
    }
    for (int label = 1; label <= colorCount; ++label) {
        uint8_t positions[MAX_DOMAIN]{};
        int size = 0;
        bool oriented = false;
        for (int position = 0; position < n; ++position) {
            if (bestLabels[position] != label) continue;
            ++size;
            oriented = oriented || bestRanks[position] != 0;
        }
        if (oriented) {
            for (int position = 0; position < n; ++position) {
                if (bestLabels[position] == label) {
                    positions[bestRanks[position] - 1] = position;
                }
            }
        } else {
            int index = 0;
            for (int position = n - 1; position >= 0; --position) {
                if (bestLabels[position] == label) positions[index++] = position;
            }
        }
        for (int index = 0; index < size; ++index) {
            canonicalOmega[positions[index]] = positions[(index + 1) % size];
        }
    }
    return true;
}

__global__ void reconstruct_kernel(
        const InputRecord *inputs, const ReconstructionTask *tasks,
        const size_t taskCount, const uint64_t *policyParents,
        const uint64_t *policyChildren, const int policyCount,
        CanonicalKey *outputs, unsigned int *replayFailures) {
    const size_t index = static_cast<size_t>(blockIdx.x) * blockDim.x
            + threadIdx.x;
    if (index >= taskCount) return;
    CanonicalKey &output = outputs[index];
    output.domain = INVALID_DOMAIN;
    for (int position = 0; position < MAX_DOMAIN; ++position) output.omega[position] = 0;

    const ReconstructionTask task = tasks[index];
    const InputRecord input = inputs[task.inputIndex];
    const int restoredDomain = input.domain + task.fixedCount;
    uint8_t restoredBeta[MAX_DOMAIN]{};
    int destination = 0;
    int nextFixed = 0;
    for (int symbol = 0; symbol < input.domain; ++symbol) {
        restoredBeta[destination++] = symbol;
        while (nextFixed < task.fixedCount && task.gaps[nextFixed] == symbol) {
            restoredBeta[destination++] = input.domain + nextFixed++;
        }
    }
    if (destination != restoredDomain || nextFixed != task.fixedCount) return;
    uint8_t restoredOmega[MAX_DOMAIN]{};
    for (int symbol = 0; symbol < input.domain; ++symbol) {
        restoredOmega[symbol] = input.omega[symbol];
    }
    for (int symbol = input.domain; symbol < restoredDomain; ++symbol) {
        restoredOmega[symbol] = symbol;
    }

    uint8_t rawSupport[3]{};
    if (task.fixedCount == 1) {
        rawSupport[0] = task.first;
        rawSupport[1] = task.second;
        rawSupport[2] = input.domain;
    } else if (task.fixedCount == 2) {
        rawSupport[0] = task.first;
        rawSupport[1] = input.domain;
        rawSupport[2] = input.domain + 1;
    } else {
        rawSupport[0] = input.domain;
        rawSupport[1] = input.domain + 1;
        rawSupport[2] = input.domain + 2;
    }
    const bool ordered = cyclic_order(restoredBeta, restoredDomain, rawSupport, 3);
    const uint8_t first = rawSupport[0];
    const uint8_t second = ordered ? rawSupport[1] : rawSupport[2];
    const uint8_t third = ordered ? rawSupport[2] : rawSupport[1];

    uint8_t parentOmega[MAX_DOMAIN]{};
    compose_move_inverse(restoredOmega, restoredDomain, first, second, third,
                         parentOmega);
    uint8_t restoredPositions[MAX_DOMAIN];
    for (int position = 0; position < restoredDomain; ++position) {
        restoredPositions[restoredBeta[position]] = position;
    }
    uint8_t parentBeta[MAX_DOMAIN]{};
    apply_transposition(restoredBeta, restoredDomain,
                        restoredPositions[first], restoredPositions[second],
                        restoredPositions[third], parentBeta);

    if (!forward_replays(parentOmega, parentBeta, restoredOmega, restoredBeta,
                         restoredDomain, first, second, third)) {
        atomicAdd(replayFailures, 1U);
        return;
    }
    for (int symbol = 0; symbol < restoredDomain; ++symbol) {
        if (parentOmega[symbol] == symbol) return;
    }
    if (!target_is_cycle(parentOmega, parentBeta, restoredDomain)) {
        atomicAdd(replayFailures, 1U);
        return;
    }
    const uint64_t parentType = annotated_type(
            parentOmega, parentBeta, restoredDomain);
    if (parentType == UINT64_MAX) return;

    uint8_t childCycles[MAX_DOMAIN][MAX_DOMAIN];
    uint8_t childLengths[MAX_DOMAIN]{};
    uint8_t childBySymbol[MAX_DOMAIN]{};
    const int childCycleCount = decompose(input.omega, input.domain,
                                          childCycles, childLengths, childBySymbol);
    uint8_t keptChildLengths[9]{};
    int keptChildren = 0;
    for (int cycle = 0; cycle < childCycleCount; ++cycle) {
        if (childLengths[cycle] > 1) keptChildLengths[keptChildren++] = childLengths[cycle];
    }
    for (int firstIndex = 0; firstIndex < keptChildren; ++firstIndex) {
        for (int secondIndex = firstIndex + 1; secondIndex < keptChildren; ++secondIndex) {
            if (keptChildLengths[secondIndex] > keptChildLengths[firstIndex]) {
                const uint8_t value = keptChildLengths[firstIndex];
                keptChildLengths[firstIndex] = keptChildLengths[secondIndex];
                keptChildLengths[secondIndex] = value;
            }
        }
    }
    uint64_t childPartition = keptChildren;
    for (int child = 0; child < keptChildren; ++child) {
        childPartition |= static_cast<uint64_t>(keptChildLengths[child])
                << (4 + 5 * child);
    }
    const uint64_t childAndKind = (childPartition << 2) | task.kind;
    if (!accepted_transition(parentType, childAndKind, policyParents,
                             policyChildren, policyCount)) return;

    output.domain = restoredDomain;
    if (!canonicalize(parentOmega, parentBeta, restoredDomain, output.omega)) {
        output.domain = INVALID_DOMAIN;
        return;
    }
}

void throw_runtime(JNIEnv *env, const char *message) {
    if (env->ExceptionCheck()) env->ExceptionClear();
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls != nullptr) {
        env->ThrowNew(cls, message);
        env->DeleteLocalRef(cls);
    }
}

void encode_key_record(const CanonicalKey &key, uint8_t *destination) {
    std::memset(destination, 0, KEY_RECORD_BYTES);
    destination[0] = key.domain;
    int bitOffset = 0;
    for (int symbol = 0; symbol < key.domain; ++symbol) {
        const int image = key.omega[symbol];
        for (int bit = 0; bit < 5; ++bit) {
            const int valueBit = (image >> (4 - bit)) & 1;
            const int outputBit = bitOffset + bit;
            destination[1 + outputBit / 8] |= static_cast<uint8_t>(
                    valueBit << (7 - outputBit % 8));
        }
        bitOffset += 5;
    }
}

std::vector<CanonicalKey> perform_reconstruction(
        const std::vector<InputRecord> &inputs, const int maximumMoves,
        const std::vector<uint64_t> &policyParents,
        const std::vector<uint64_t> &policyChildren, const int device,
        const size_t budgetBytes, uint64_t &applications, uint64_t &accepted,
        uint64_t &elapsedNanos) {
    if (policyParents.size() != policyChildren.size() || policyParents.empty()) {
        throw std::invalid_argument(
                "GPU avoidance reconstruction policy is empty or inconsistent");
    }
    const auto started = std::chrono::steady_clock::now();
    CHECK_CUDA(cudaSetDevice(device));
    const auto tasks = enumerate_tasks(inputs, maximumMoves);
    applications = tasks.size();
    accepted = 0;
    elapsedNanos = 0;
    if (tasks.empty()) {
        elapsedNanos = std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::steady_clock::now() - started).count();
        return {};
    }

    // The device owns the canonical output and one merge-sort temporary key
    // array. remove_if and unique operate in place; reserving three key arrays
    // here rejected batches well below measured physical VRAM capacity.
    const size_t requested = inputs.size() * sizeof(InputRecord)
            + tasks.size() * sizeof(ReconstructionTask)
            + tasks.size() * sizeof(CanonicalKey) * 2
            + policyParents.size() * sizeof(uint64_t) * 2;
    if (requested > budgetBytes) {
        throw std::runtime_error(
                "GPU avoidance reconstruction batch exceeds td18.avoidanceGpuBudgetMB");
    }
    size_t freeBytes = 0;
    size_t totalBytes = 0;
    CHECK_CUDA(cudaMemGetInfo(&freeBytes, &totalBytes));
    if (requested > freeBytes) {
        throw std::runtime_error(
                "GPU avoidance reconstruction does not fit available device memory");
    }

    thrust::device_vector<InputRecord> deviceInputs(inputs.begin(), inputs.end());
    thrust::device_vector<ReconstructionTask> deviceTasks(tasks.begin(), tasks.end());
    thrust::device_vector<uint64_t> devicePolicyParents(
            policyParents.begin(), policyParents.end());
    thrust::device_vector<uint64_t> devicePolicyChildren(
            policyChildren.begin(), policyChildren.end());
    thrust::device_vector<CanonicalKey> deviceOutputs(tasks.size());
    thrust::device_vector<unsigned int> deviceReplayFailures(1, 0);

    const size_t blocks64 = (tasks.size() + THREADS - 1) / THREADS;
    if (blocks64 > static_cast<size_t>(std::numeric_limits<unsigned int>::max())) {
        throw std::runtime_error("GPU avoidance reconstruction grid is too large");
    }
    reconstruct_kernel<<<static_cast<unsigned int>(blocks64), THREADS>>>(
            thrust::raw_pointer_cast(deviceInputs.data()),
            thrust::raw_pointer_cast(deviceTasks.data()), tasks.size(),
            thrust::raw_pointer_cast(devicePolicyParents.data()),
            thrust::raw_pointer_cast(devicePolicyChildren.data()),
            static_cast<int>(policyParents.size()),
            thrust::raw_pointer_cast(deviceOutputs.data()),
            thrust::raw_pointer_cast(deviceReplayFailures.data()));
    CHECK_CUDA(cudaGetLastError());

    auto validEnd = thrust::remove_if(
            deviceOutputs.begin(), deviceOutputs.end(), InvalidKey{});
    accepted = static_cast<uint64_t>(validEnd - deviceOutputs.begin());
    thrust::sort(deviceOutputs.begin(), validEnd, KeyLess{});
    const auto uniqueEnd = thrust::unique(
            deviceOutputs.begin(), validEnd, KeyEqual{});
    const size_t uniqueCount = uniqueEnd - deviceOutputs.begin();
    std::vector<CanonicalKey> result(uniqueCount);
    thrust::copy(deviceOutputs.begin(), uniqueEnd, result.begin());
    unsigned int replayFailures = 0;
    thrust::copy(deviceReplayFailures.begin(), deviceReplayFailures.end(),
                 &replayFailures);
    CHECK_CUDA(cudaDeviceSynchronize());
    elapsedNanos = std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now() - started).count();
    if (replayFailures != 0) {
        throw std::runtime_error(
                "GPU avoidance reconstruction failed a forward-replay invariant");
    }
    return result;
}

} // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_br_unb_cic_td18_GpuAvoidanceReconstructor_reconstructNative(
        JNIEnv *env, jclass, jbyteArray javaInputs, jint inputCount,
        jint maximumMoves, jlongArray javaPolicyParents,
        jlongArray javaPolicyChildren, jint device, jlong budgetBytes,
        jlongArray javaStatistics) {
    try {
        clearCudaRuntimePoisoned();
        if (javaInputs == nullptr || javaPolicyParents == nullptr
            || javaPolicyChildren == nullptr || javaStatistics == nullptr) {
            throw std::invalid_argument(
                    "GPU avoidance reconstruction arguments must not be null");
        }
        if (inputCount < 0
            || env->GetArrayLength(javaInputs) != inputCount * INPUT_RECORD_BYTES) {
            throw std::invalid_argument(
                    "GPU avoidance reconstruction input length is inconsistent");
        }
        if (maximumMoves <= 0 || maximumMoves >= MAX_BUDGET) {
            throw std::invalid_argument(
                    "GPU avoidance reconstruction budget lies outside 1..9");
        }
        if (budgetBytes <= 0 || env->GetArrayLength(javaStatistics) < 4) {
            throw std::invalid_argument(
                    "GPU avoidance reconstruction budget or statistics buffer is invalid");
        }
        std::vector<uint8_t> rawInputs(static_cast<size_t>(inputCount)
                                       * INPUT_RECORD_BYTES);
        if (!rawInputs.empty()) {
            env->GetByteArrayRegion(javaInputs, 0, rawInputs.size(),
                                    reinterpret_cast<jbyte *>(rawInputs.data()));
        }
        std::vector<InputRecord> inputs(static_cast<size_t>(inputCount));
        if (!inputs.empty()) {
            std::memcpy(inputs.data(), rawInputs.data(), rawInputs.size());
        }

        const jsize policyCount = env->GetArrayLength(javaPolicyParents);
        if (policyCount != env->GetArrayLength(javaPolicyChildren)) {
            throw std::invalid_argument(
                    "GPU avoidance reconstruction policy arrays differ in length");
        }
        std::vector<jlong> rawParents(static_cast<size_t>(policyCount));
        std::vector<jlong> rawChildren(static_cast<size_t>(policyCount));
        env->GetLongArrayRegion(javaPolicyParents, 0, policyCount, rawParents.data());
        env->GetLongArrayRegion(javaPolicyChildren, 0, policyCount, rawChildren.data());
        std::vector<uint64_t> policyParents(static_cast<size_t>(policyCount));
        std::vector<uint64_t> policyChildren(static_cast<size_t>(policyCount));
        for (jsize index = 0; index < policyCount; ++index) {
            policyParents[index] = static_cast<uint64_t>(rawParents[index]);
            policyChildren[index] = static_cast<uint64_t>(rawChildren[index]);
        }

        uint64_t applications = 0;
        uint64_t accepted = 0;
        uint64_t elapsedNanos = 0;
        const auto keys = perform_reconstruction(
                inputs, maximumMoves, policyParents, policyChildren,
                static_cast<int>(device), static_cast<size_t>(budgetBytes),
                applications, accepted, elapsedNanos);
        if (keys.size() > static_cast<size_t>(
                std::numeric_limits<jsize>::max() / KEY_RECORD_BYTES)) {
            throw std::runtime_error(
                    "GPU avoidance reconstruction result exceeds a Java byte array");
        }
        std::vector<uint8_t> records(keys.size() * KEY_RECORD_BYTES);
        for (size_t index = 0; index < keys.size(); ++index) {
            encode_key_record(keys[index], records.data()
                    + index * KEY_RECORD_BYTES);
        }
        jbyteArray result = env->NewByteArray(static_cast<jsize>(records.size()));
        if (result == nullptr) return nullptr;
        if (!records.empty()) {
            env->SetByteArrayRegion(result, 0, records.size(),
                                    reinterpret_cast<const jbyte *>(records.data()));
        }
        const jlong statistics[4] = {
                static_cast<jlong>(applications),
                static_cast<jlong>(applications),
                static_cast<jlong>(accepted),
                static_cast<jlong>(elapsedNanos)};
        env->SetLongArrayRegion(javaStatistics, 0, 4, statistics);
        return result;
    } catch (const std::exception &failure) {
        throw_runtime(env, failure.what());
    }
    return nullptr;
}
