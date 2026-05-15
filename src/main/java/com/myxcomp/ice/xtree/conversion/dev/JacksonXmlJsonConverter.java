package com.myxcomp.ice.xtree.conversion.dev;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.myxcomp.ice.xtree.conversion.XmlJsonConverter;

@Component
@Profile("dev")
public class JacksonXmlJsonConverter implements XmlJsonConverter {

    private static final String DEFAULT_ROOT = "data";

    private final XmlMapper xmlMapper = new XmlMapper();
    private final ObjectMapper jsonMapper = new ObjectMapper();

    @Override
    public String xmlToJson(String xml) {
        try {
            JsonNode tree = xmlMapper.readTree(xml);
            return jsonMapper.writeValueAsString(tree);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to convert XML to JSON: " + e.getOriginalMessage(), e);
        }
    }

    @Override
    public String jsonToXml(String json) {
        try {
            JsonNode tree = jsonMapper.readTree(json);
            return xmlMapper.writer().withRootName(DEFAULT_ROOT).writeValueAsString(tree);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to convert JSON to XML: " + e.getOriginalMessage(), e);
        }
    }
}
