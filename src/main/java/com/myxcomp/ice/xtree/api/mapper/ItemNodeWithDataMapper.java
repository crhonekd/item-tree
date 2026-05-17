package com.myxcomp.ice.xtree.api.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.generated.model.ItemNodeWithData;
import com.myxcomp.ice.xtree.service.ItemWithData;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ItemNodeWithDataMapper {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final TimeMapper timeMapper;

    public ItemNodeWithDataMapper(ObjectMapper objectMapper, TimeMapper timeMapper) {
        this.objectMapper = objectMapper;
        this.timeMapper = timeMapper;
    }

    public ItemNodeWithData toDto(ItemWithData src) {
        ItemNodeWithData dto = new ItemNodeWithData(
                src.itemTreeId(),
                src.parentId(),
                src.name(),
                src.type(),
                timeMapper.toOffsetDateTime(src.lastUpdate()),
                src.lastUpdateUser());

        if (src.dataJson() != null) {
            dto.setDataJson(parseJson(src.dataJson(), src.itemTreeId()));
        }
        if (src.dataXml() != null) {
            dto.setDataXml(src.dataXml());
        }
        if (src.children() == null) {
            // non-folder node: override the DTO default (new ArrayList<>()) with null
            dto.setChildren(null);
        } else {
            // folder node: map children list (may be empty for an empty folder)
            List<ItemNodeWithData> shaped = new ArrayList<>(src.children().size());
            for (ItemWithData c : src.children()) shaped.add(toDto(c));
            dto.setChildren(shaped);
        }
        return dto;
    }

    public List<ItemNodeWithData> toDtos(List<ItemWithData> items) {
        List<ItemNodeWithData> out = new ArrayList<>(items.size());
        for (ItemWithData i : items) out.add(toDto(i));
        return out;
    }

    private Map<String, Object> parseJson(String json, long id) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to parse stored JSON payload for id " + id, e);
        }
    }
}
