package com.myxcomp.ice.xtree.refresh;

public final class DeltaCounters {
    private int created;
    private int moved;
    private int renamed;
    private int meta;

    public int created() { return created; }
    public int moved()   { return moved; }
    public int renamed() { return renamed; }
    public int meta()    { return meta; }

    public void incrementCreated() { created++; }
    public void incrementMoved()   { moved++; }
    public void incrementRenamed() { renamed++; }
    public void incrementMeta()    { meta++; }

    public int total() { return created + moved + renamed + meta; }

    @Override
    public String toString() {
        return "DeltaCounters{created=" + created + ", moved=" + moved
                + ", renamed=" + renamed + ", meta=" + meta + '}';
    }
}
