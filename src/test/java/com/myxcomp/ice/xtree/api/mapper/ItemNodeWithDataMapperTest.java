package com.myxcomp.ice.xtree.api.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.generated.model.ItemNodeWithData;
import com.myxcomp.ice.xtree.service.ItemWithData;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ItemNodeWithDataMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ItemNodeWithDataMapper mapper = new ItemNodeWithDataMapper(objectMapper, new TimeMapper());

    private static final Instant T = Instant.parse("2026-05-16T12:34:56Z");

    @Test
    void mapsJsonStringIntoMap() {
        // Non-folder node: children == null
        ItemWithData input = new ItemWithData(
                42L, 7L, "Report-1", "Report", T, "alice",
                "{\"foo\":\"bar\",\"n\":1}", null, null);

        ItemNodeWithData dto = mapper.toDto(input);

        assertThat(dto.getItemTreeId()).isEqualTo(42L);
        assertThat(dto.getParentId()).isEqualTo(7L);
        assertThat(dto.getName()).isEqualTo("Report-1");
        assertThat(dto.getType()).isEqualTo("Report");
        assertThat(dto.getLastUpdate()).isEqualTo(OffsetDateTime.ofInstant(T, ZoneOffset.UTC));
        assertThat(dto.getLastUpdateUser()).isEqualTo("alice");
        assertThat(dto.getDataJson()).containsEntry("foo", "bar").containsEntry("n", 1);
        assertThat(dto.getDataXml()).isNull();
        assertThat(dto.getChildren()).isNull();
    }

    @Test
    void preservesRawXmlPayload() {
        // Non-folder node: children == null
        ItemWithData input = new ItemWithData(
                42L, 7L, "Bucket-1", "Bucket.Collection", T, "alice",
                null, "<bucket/>", null);

        ItemNodeWithData dto = mapper.toDto(input);

        assertThat(dto.getDataXml()).isEqualTo("<bucket/>");
        assertThat(dto.getDataJson()).isNull();
        assertThat(dto.getChildren()).isNull();
    }

    @Test
    void nonFolderNodeProducesNullChildren() {
        // Non-folder with no data payload: children == null marks it as non-folder
        ItemWithData input = new ItemWithData(
                42L, 7L, "Shortcut-1", "Shortcut", T, "alice", null, null, null);

        ItemNodeWithData dto = mapper.toDto(input);

        assertThat(dto.getDataJson()).isNull();
        assertThat(dto.getDataXml()).isNull();
        assertThat(dto.getChildren()).isNull();
    }

    @Test
    void emptyFolderProducesEmptyChildrenList() {
        // Folder with no children: children == List.of() (non-null empty list)
        ItemWithData input = new ItemWithData(
                42L, 7L, "MyFolder", "Folder", T, "alice", null, null, List.of());

        ItemNodeWithData dto = mapper.toDto(input);

        assertThat(dto.getDataJson()).isNull();
        assertThat(dto.getDataXml()).isNull();
        assertThat(dto.getChildren()).isNotNull().isEmpty();
    }

    @Test
    void folderRecursesChildrenOneLevel() {
        // Child nodes are non-folders: children == null
        ItemWithData childA = new ItemWithData(
                10L, 1L, "child-A", "Report", T, "alice",
                "{\"k\":\"v\"}", null, null);
        ItemWithData childB = new ItemWithData(
                11L, 1L, "child-B", "Folder", T, "alice", null, null, List.of());
        ItemWithData folder = new ItemWithData(
                1L, 0L, "myFolder", "Folder", T, "alice", null, null, List.of(childA, childB));

        ItemNodeWithData dto = mapper.toDto(folder);

        assertThat(dto.getChildren()).hasSize(2);
        assertThat(dto.getChildren().get(0).getItemTreeId()).isEqualTo(10L);
        assertThat(dto.getChildren().get(0).getDataJson()).containsEntry("k", "v");
        assertThat(dto.getChildren().get(0).getChildren()).isNull();
        assertThat(dto.getChildren().get(1).getItemTreeId()).isEqualTo(11L);
        assertThat(dto.getChildren().get(1).getChildren()).isNotNull().isEmpty();
    }

    @Test
    void listMappingPreservesOrder() {
        ItemWithData a = new ItemWithData(1L, 0L, "a", "Folder", T, "sys", null, null, List.of());
        ItemWithData b = new ItemWithData(2L, 0L, "b", "Folder", T, "sys", null, null, List.of());

        List<ItemNodeWithData> dtos = mapper.toDtos(List.of(a, b));

        assertThat(dtos).extracting(ItemNodeWithData::getItemTreeId).containsExactly(1L, 2L);
    }

    @Test
    void malformedJsonProducesIllegalStateException() {
        ItemWithData input = new ItemWithData(
                42L, 7L, "Report-1", "Report", T, "alice",
                "{not-json", null, null);

        assertThatThrownBy(() -> mapper.toDto(input))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("42");
    }
}
