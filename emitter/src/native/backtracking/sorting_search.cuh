#pragma once

#include <cstddef>
#include <vector>

#include <cuda_runtime.h>

#include "core/common.cuh"

void allocateGpuSortingSearchSlot(int slot,
                                  int device,
                                  size_t queueBytesBudget,
                                  int slotsOnDevice,
                                  int slotOrdinalOnDevice);

struct GpuSortingResult {
    int kind = 0; // 0: exhausted search, 1: complete witness, 2: diameter closure, 3: timeout, 4: depth limit
    std::vector<Move> moves;
};

GpuSortingResult performGpuSortingSearch(std::vector<short> beta,
                                          std::vector<short> omega,
                                          int maximumDepth,
                                          bool allowDiameterClosure,
                                          int maximumSearchDepth,
                                          unsigned long long maximumNanos,
                                          const std::vector<int> &knownDiameters,
                                          cudaStream_t stream,
                                          int slot,
                                          int maxWarps = -1);

void destroyGpuSortingSearchSlots();
