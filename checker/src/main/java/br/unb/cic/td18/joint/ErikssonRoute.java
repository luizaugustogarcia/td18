package br.unb.cic.td18.joint;

/** The active two-sided three-move route; checkpoint identity includes its budgets. */
public final class ErikssonRoute {
    public static final String ID="td18-eriksson-three-v1";
    public static final int ROOT_SYMBOLS=KnownDiameters.ROOT_SYMBOLS;
    public static final int ROOT_BUDGET=KnownDiameters.MAX_BUDGET;
    public static final int ROOT_PARTITIONS=53;
    public static final int ENDPOINT_SYMBOLS=ROOT_SYMBOLS-5;
    public static final int ENDPOINT_BUDGET=ROOT_BUDGET-3;
    public static final int ENDPOINT_TYPES=74;
    private ErikssonRoute() { }
}
