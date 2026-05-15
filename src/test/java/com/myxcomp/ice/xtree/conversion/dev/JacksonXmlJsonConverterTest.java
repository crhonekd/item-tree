package com.myxcomp.ice.xtree.conversion.dev;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JacksonXmlJsonConverterTest {

    private final JacksonXmlJsonConverter converter = new JacksonXmlJsonConverter();
    private final ObjectMapper jsonMapper = new ObjectMapper();

    @Test
    void xmlToJsonProducesJsonTreeMatchingXmlStructure() throws Exception {
        String xml = "<report><name>r1</name><n>1</n></report>";
        String json = converter.xmlToJson(xml);
        JsonNode tree = jsonMapper.readTree(json);
        // XML carries no type info; values arrive as strings.
        assertThat(tree.get("name").asText()).isEqualTo("r1");
        assertThat(tree.get("n").asText()).isEqualTo("1");
    }

    @Test
    void jsonToXmlProducesXmlWithRootElement() {
        String json = "{\"name\":\"r1\",\"n\":1}";
        String xml = converter.jsonToXml(json);
        assertThat(xml).contains("<name>r1</name>");
        assertThat(xml).contains("<n>1</n>");
    }

    @Test
    void roundTripXmlPreservesElementTree() throws Exception {
        String xml = "<filter><name>f1</name><n>1</n></filter>";
        String json = converter.xmlToJson(xml);
        String roundTripped = converter.jsonToXml(json);

        // Compare via XmlMapper trees — ignores root element name (known stub limitation).
        com.fasterxml.jackson.dataformat.xml.XmlMapper xmlMapper =
                new com.fasterxml.jackson.dataformat.xml.XmlMapper();
        JsonNode original = xmlMapper.readTree(xml);
        JsonNode after    = xmlMapper.readTree(roundTripped);
        assertThat(after).isEqualTo(original);
    }

    @Test
    void roundTripJsonPreservesScalarsAsStrings() throws Exception {
        // Strings in == strings out; this documents the known XML type-loss limitation.
        String json = "{\"name\":\"r1\",\"n\":\"1\"}";
        String xml = converter.jsonToXml(json);
        String roundTripped = converter.xmlToJson(xml);
        JsonNode tree = jsonMapper.readTree(roundTripped);
        assertThat(tree.get("name").asText()).isEqualTo("r1");
        assertThat(tree.get("n").asText()).isEqualTo("1");
    }

    @Test
    void malformedXmlThrowsIllegalStateException() {
        assertThatThrownBy(() -> converter.xmlToJson("<bad><unclosed>"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("XML");
    }

    @Test
    void malformedJsonThrowsIllegalStateException() {
        assertThatThrownBy(() -> converter.jsonToXml("{ not json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JSON");
    }
}
