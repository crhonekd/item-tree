package com.myxcomp.ice.xtree.policy;

/**
 * Decision surface for type-driven data behaviour (design §10).
 * Implementations are immutable; type-list changes require a context restart.
 */
public interface TypePolicy {

    /** True if the type is allowed to carry payload data — i.e. it is NOT in {@code types-without-data}. */
    boolean hasData(String type);

    /** True if a write of this type must also populate the XML column from the JSON payload. */
    boolean isAlsoPersistedAsXmlOnWrite(String type);

    /** True if the UI expects the payload as XML in the response. (Empty in ICEX today.) */
    boolean isSentAsXmlToUi(String type);

    /** True if the type appears in at least one configured list. Used for diagnostics and the unknown-type metric. */
    boolean isKnown(String type);
}
