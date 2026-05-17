package com.myxcomp.ice.xtree.refresh;

public record RefreshResult(
        Type type,
        boolean success,
        long durationMs,
        DeltaCounters deltaCounters,
        DriftCounters driftCounters,
        String errorMessage
) {
    public enum Type { DELTA, FULL }

    public static RefreshResult deltaSuccess(long durationMs, DeltaCounters counters) {
        return new RefreshResult(Type.DELTA, true, durationMs, counters, null, null);
    }

    public static RefreshResult deltaFailure(long durationMs, String error) {
        return new RefreshResult(Type.DELTA, false, durationMs, null, null, error);
    }

    public static RefreshResult fullSuccess(long durationMs, DriftCounters counters) {
        return new RefreshResult(Type.FULL, true, durationMs, null, counters, null);
    }

    public static RefreshResult fullFailure(long durationMs, String error) {
        return new RefreshResult(Type.FULL, false, durationMs, null, null, error);
    }
}
