package com.myxcomp.ice.xtree.api.mapper;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.generated.model.ItemNode;
import com.myxcomp.ice.xtree.service.TreeNodeView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ItemNodeMapperTest {

    private final ItemNodeMapper mapper = new ItemNodeMapper();

    private static final Instant T = Instant.parse("2026-05-16T12:34:56Z");

    @Test
    void cachedNodeMapsToItemNodeWithoutPath() {
        CachedNode node = new CachedNode(42L, 7L, "Report-1", "Report", T, "alice");

        ItemNode dto = mapper.toDto(node);

        assertThat(dto.getItemTreeId()).isEqualTo(42L);
        assertThat(dto.getParentId()).isEqualTo(7L);
        assertThat(dto.getName()).isEqualTo("Report-1");
        assertThat(dto.getType()).isEqualTo("Report");
        assertThat(dto.getPath()).isNull();
        assertThat(dto.getLastUpdate()).isEqualTo(OffsetDateTime.ofInstant(T, ZoneOffset.UTC));
        assertThat(dto.getLastUpdateUser()).isEqualTo("alice");
    }

    @Test
    void treeNodeViewMapsToItemNodeWithPath() {
        CachedNode node = new CachedNode(42L, 7L, "Report-1", "Report", T, "alice");
        TreeNodeView view = new TreeNodeView(node, "root/Users/alice/Report-1");

        ItemNode dto = mapper.toDto(view);

        assertThat(dto.getItemTreeId()).isEqualTo(42L);
        assertThat(dto.getPath()).isEqualTo("root/Users/alice/Report-1");
    }

    @Test
    void treeNodeViewListMapsInOrder() {
        CachedNode a = new CachedNode(1L, 0L, "root",  "Folder", T, "sys");
        CachedNode b = new CachedNode(2L, 1L, "Users", "Folder", T, "sys");
        List<TreeNodeView> views = List.of(
                new TreeNodeView(a, "root"),
                new TreeNodeView(b, "root/Users"));

        List<ItemNode> dtos = mapper.toDtos(views);

        assertThat(dtos).extracting(ItemNode::getItemTreeId).containsExactly(1L, 2L);
        assertThat(dtos).extracting(ItemNode::getPath).containsExactly("root", "root/Users");
    }
}
