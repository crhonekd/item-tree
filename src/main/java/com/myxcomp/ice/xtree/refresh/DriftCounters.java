package com.myxcomp.ice.xtree.refresh;

public final class DriftCounters {
    private int created;
    private int deleted;
    private int mutated;

    public DriftCounters() {}

    public DriftCounters(int created, int deleted, int mutated) {
        this.created = created;
        this.deleted = deleted;
        this.mutated = mutated;
    }

    public int created() { return created; }
    public int deleted() { return deleted; }
    public int mutated() { return mutated; }

    public void incrementCreated() { created++; }
    public void incrementDeleted() { deleted++; }
    public void incrementMutated() { mutated++; }

    public int total() { return created + deleted + mutated; }

    @Override
    public String toString() {
        return "DriftCounters{created=" + created + ", deleted=" + deleted
                + ", mutated=" + mutated + '}';
    }
}
