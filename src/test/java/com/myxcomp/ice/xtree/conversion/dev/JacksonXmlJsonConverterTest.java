package com.myxcomp.ice.xtree.conversion.dev;

import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

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
        assertThat(xml).contains("<data>");
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
    void malformedXmlThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> converter.xmlToJson("<bad><unclosed>"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("XML");
    }

    @Test
    void malformedJsonThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> converter.jsonToXml("{ not json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON");
    }

    @Nested
    class NullAndBlankGuards {

        static Stream<String> blankInputs() {
            return Stream.of("", "   ");
        }

        @Test
        void xmlToJsonNullThrowsNullPointerException() {
            assertThatThrownBy(() -> converter.xmlToJson(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("xml");
        }

        @ParameterizedTest(name = "xmlToJson(\"{0}\") throws IllegalArgumentException")
        @MethodSource("blankInputs")
        void xmlToJsonBlankThrowsIllegalArgumentException(String blank) {
            assertThatThrownBy(() -> converter.xmlToJson(blank))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        void jsonToXmlNullThrowsNullPointerException() {
            assertThatThrownBy(() -> converter.jsonToXml(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("json");
        }

        @ParameterizedTest(name = "jsonToXml(\"{0}\") throws IllegalArgumentException")
        @MethodSource("blankInputs")
        void jsonToXmlBlankThrowsIllegalArgumentException(String blank) {
            assertThatThrownBy(() -> converter.jsonToXml(blank))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }
    }
}
