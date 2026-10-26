package br.unb.cic.td18;

/** JNI entry point retained to preserve the native library ABI. */
public final class GpuAvoidanceReconstructor {
    private GpuAvoidanceReconstructor() { }

    public static native byte[] reconstructNative(
            byte[] inputs, int inputCount, int maximumMoves,
            long[] policyParentTypes, long[] policyChildAndKinds,
            int device, long budgetBytes, long[] statistics);
}
