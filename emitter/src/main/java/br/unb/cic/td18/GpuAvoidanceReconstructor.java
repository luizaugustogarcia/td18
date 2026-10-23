package br.unb.cic.td18;

import br.unb.cic.td18.base.CertificateCodec;
import br.unb.cic.td18.base.CyclicTargetPair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Batched CUDA inverse reconstruction with device-side class deduplication. */
final class GpuAvoidanceReconstructor {
    static final int INPUT_RECORD_BYTES = 20;
    static final int KEY_RECORD_BYTES = 13;
    static final int MAX_DOMAIN = 19;
    private static final int NATIVE_STATS_LENGTH = 4;
    private static final ConcurrentHashMap<Integer, Policy> POLICIES =
            new ConcurrentHashMap<>();

    private final int device;
    private final long budgetBytes;

    GpuAvoidanceReconstructor(final int device, final long budgetBytes) {
        if (device < 0 || budgetBytes <= 0) {
            throw new IllegalArgumentException(
                    "GPU reconstruction device and memory budget must be positive");
        }
        System.loadLibrary("td18_jni");
        this.device = device;
        this.budgetBytes = budgetBytes;
    }

    Batch reconstruct(
            final List<CyclicTargetPair> inputs,
            final int maximumMoves
    ) {
        if (inputs.isEmpty()) {
            return new Batch(0, 0, 0, new byte[0]);
        }
        final byte[] encodedInputs = encodeInputs(inputs);
        final int parentMovesUsed = ReductionTypeEnumerator.MAX_BUDGET
                - maximumMoves - 1;
        final var policy = POLICIES.computeIfAbsent(
                parentMovesUsed, GpuAvoidanceReconstructor::buildPolicy);
        final long[] statistics = new long[NATIVE_STATS_LENGTH];
        final byte[] keyRecords = reconstructNative(
                encodedInputs, inputs.size(), maximumMoves,
                policy.parentTypes(), policy.childAndKinds(),
                device, budgetBytes, statistics);
        if (keyRecords == null || keyRecords.length % KEY_RECORD_BYTES != 0) {
            throw new IllegalStateException(
                    "CUDA avoidance reconstruction returned malformed key records");
        }
        final long unique = keyRecords.length / KEY_RECORD_BYTES;
        if (statistics[0] < 0 || statistics[1] != statistics[0]
                || statistics[2] < unique || statistics[3] < 0) {
            throw new IllegalStateException(
                    "CUDA avoidance reconstruction returned inconsistent statistics");
        }
        return new Batch(statistics[0], statistics[2],
                statistics[3], keyRecords);
    }

    private static byte[] encodeInputs(final List<CyclicTargetPair> inputs) {
        final byte[] encoded = new byte[Math.multiplyExact(
                inputs.size(), INPUT_RECORD_BYTES)];
        for (var index = 0; index < inputs.size(); index++) {
            final var pair = inputs.get(index).canonicalRepresentative();
            final int domain = pair.getBeta().size();
            if (domain <= 0 || domain > MAX_DOMAIN) {
                throw new IllegalArgumentException(
                        "GPU avoidance reconstruction supports domains of size 1..19");
            }
            for (var symbol = 0; symbol < domain; symbol++) {
                if (pair.getBeta().get(symbol) != symbol) {
                    throw new IllegalStateException(
                            "Canonical avoidance input does not use the canonical current cycle");
                }
            }
            final int offset = index * INPUT_RECORD_BYTES;
            encoded[offset] = (byte) domain;
            System.arraycopy(pair.getOmega().getOneLineNotation(), 0,
                    encoded, offset + 1, domain);
        }
        return encoded;
    }

    static List<CyclicTargetPair> decodeKeyRecords(final byte[] records) {
        if (records.length % KEY_RECORD_BYTES != 0) {
            throw new IllegalArgumentException(
                    "Malformed GPU avoidance key records");
        }
        final var result = new ArrayList<CyclicTargetPair>(
                records.length / KEY_RECORD_BYTES);
        for (var offset = 0; offset < records.length; offset += KEY_RECORD_BYTES) {
            final int domain = Byte.toUnsignedInt(records[offset]);
            final int keyBytes = keyBytes(domain);
            result.add(CertificateCodec.pairForKey(
                    Arrays.copyOfRange(records, offset + 1,
                            offset + 1 + keyBytes), domain));
        }
        return List.copyOf(result);
    }

    static Batch cpuReference(
            final List<CyclicTargetPair> inputs,
            final int maximumMoves
    ) {
        long applications = 0;
        final var records = new ArrayList<byte[]>();
        for (final var input : inputs) {
            final var batch = ObstructionAvoidance.reconstructParents(
                    input, maximumMoves);
            applications = Math.addExact(applications, batch.applications());
            for (final var parent : batch.parents()) {
                records.add(keyRecord(parent));
            }
        }
        records.sort(Arrays::compareUnsigned);
        final var unique = new ArrayList<byte[]>(records.size());
        byte[] previous = null;
        for (final var record : records) {
            if (previous == null || !Arrays.equals(previous, record)) {
                unique.add(record);
                previous = record;
            }
        }
        final byte[] flattened = new byte[Math.multiplyExact(
                unique.size(), KEY_RECORD_BYTES)];
        for (var index = 0; index < unique.size(); index++) {
            System.arraycopy(unique.get(index), 0, flattened,
                    index * KEY_RECORD_BYTES, KEY_RECORD_BYTES);
        }
        return new Batch(applications, records.size(), 0, flattened);
    }

    private static byte[] keyRecord(final CyclicTargetPair pair) {
        final var canonical = pair.canonicalRepresentative();
        final int domain = canonical.getBeta().size();
        final byte[] key = CertificateCodec.keyFor(canonical);
        final byte[] result = new byte[KEY_RECORD_BYTES];
        result[0] = (byte) domain;
        System.arraycopy(key, 0, result, 1, key.length);
        return result;
    }

    static int keyBytes(final int domain) {
        if (domain <= 0 || domain > MAX_DOMAIN) {
            throw new IllegalArgumentException(
                    "GPU avoidance key domain lies outside 1..19");
        }
        return (5 * domain + 7) / 8;
    }

    private static Policy buildPolicy(final int movesUsed) {
        final var transitions =
                ReductionTypeEnumerator.reconstructionTransitions(movesUsed);
        final long[] parentTypes = new long[transitions.size()];
        final long[] childAndKinds = new long[transitions.size()];
        for (var index = 0; index < transitions.size(); index++) {
            parentTypes[index] = transitions.get(index).parentType();
            childAndKinds[index] = transitions.get(index).childAndKind();
        }
        return new Policy(parentTypes, childAndKinds);
    }

    record Batch(long applications, long acceptedBeforeDeduplication,
                 long pipelineNanos, byte[] keyRecords) {
        Batch {
            if (applications < 0 || acceptedBeforeDeduplication < 0
                    || pipelineNanos < 0 || keyRecords == null
                    || keyRecords.length % KEY_RECORD_BYTES != 0) {
                throw new IllegalArgumentException(
                        "Inconsistent GPU avoidance reconstruction batch");
            }
        }

        long uniqueKeys() {
            return keyRecords.length / KEY_RECORD_BYTES;
        }

    }

    private record Policy(long[] parentTypes, long[] childAndKinds) {
    }

    private static native byte[] reconstructNative(
            byte[] inputs, int inputCount, int maximumMoves,
            long[] policyParentTypes, long[] policyChildAndKinds,
            int device, long budgetBytes, long[] statistics);
}
