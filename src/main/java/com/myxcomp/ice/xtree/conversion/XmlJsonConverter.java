package com.myxcomp.ice.xtree.conversion;

/**
 * Bidirectional XML ↔ JSON conversion (design §11).
 *
 * Phase A: backed by the Jackson stub in {@code conversion/dev/}.
 * Phase B: backed by the in-house Barcap library in {@code conversion/prod/}.
 */
public interface XmlJsonConverter {

    /** Converts an XML document to its canonical JSON representation. Throws on malformed input. */
    String xmlToJson(String xml);

    /** Converts a JSON document to its canonical XML representation. Throws on malformed input. */
    String jsonToXml(String json);
}
