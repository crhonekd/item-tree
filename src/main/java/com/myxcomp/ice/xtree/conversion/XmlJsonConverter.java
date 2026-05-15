package com.myxcomp.ice.xtree.conversion;

/**
 * Bidirectional XML ↔ JSON conversion (design §11).
 *
 * Phase A: backed by the Jackson stub in {@code conversion/dev/}.
 * Phase B: backed by the in-house Barcap library in {@code conversion/prod/}.
 */
public interface XmlJsonConverter {

    /**
     * Converts an XML document to its canonical JSON representation.
     *
     * @throws NullPointerException if {@code xml} is null
     * @throws IllegalArgumentException if {@code xml} is blank or malformed
     */
    String xmlToJson(String xml);

    /**
     * Converts a JSON document to its canonical XML representation.
     *
     * @throws NullPointerException if {@code json} is null
     * @throws IllegalArgumentException if {@code json} is blank or malformed
     */
    String jsonToXml(String json);
}
