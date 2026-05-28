package com.myxcomp.ice.xtree.api.mapper;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.generated.model.SearchHit;
import com.myxcomp.ice.xtree.service.SearchHitView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchHitMapperTest {

    private final SearchHitMapper mapper = new SearchHitMapper(new ItemNodeMapper(new TimeMapper()));

    private static CachedNode folder(long id, long parentId, String name) {
        return new CachedNode(id, parentId, name, "Folder", Instant.EPOCH, "sys");
    }

    @Test
    void mapsIdParentNameTypeAndPath() {
        CachedNode node = new CachedNode(42L, 7L, "Report-1", "Report", Instant.EPOCH, "alice");
        SearchHit hit = mapper.toDto(new SearchHitView(node, "/root/thing", List.of()));

        assertThat(hit.getItemTreeId()).isEqualTo(42L);
        assertThat(hit.getParentId()).isEqualTo(7L);
        assertThat(hit.getName()).isEqualTo("Report-1");
        assertThat(hit.getType()).isEqualTo("Report");
        assertThat(hit.getPath()).isEqualTo("/root/thing");
        assertThat(hit.getAncestors()).isEmpty();
    }

    @Test
    void mapsAncestorsInRootFirstOrderWithNullPath() {
        CachedNode root = folder(1L, 0L, "root");
        CachedNode users = folder(2L, 1L, "Users");
        CachedNode hit = new CachedNode(42L, 2L, "Report-1", "Report", Instant.EPOCH, "alice");

        SearchHit dto = mapper.toDto(new SearchHitView(hit, "/root/Users/Report-1", List.of(root, users)));

        assertThat(dto.getAncestors()).extracting("itemTreeId").containsExactly(1L, 2L);
        assertThat(dto.getAncestors()).extracting("name").containsExactly("root", "Users");
        assertThat(dto.getAncestors()).allSatisfy(a -> assertThat(a.getPath()).isNull());
    }

    @Test
    void emptyAncestorsMapToEmptyList() {
        CachedNode root = folder(1L, 0L, "root");
        SearchHit dto = mapper.toDto(new SearchHitView(root, "/root", List.of()));
        assertThat(dto.getAncestors()).isEmpty();
    }

    @Test
    void mapsListInOrder() {
        CachedNode a = folder(1L, 0L, "root");
        CachedNode b = folder(2L, 1L, "Users");
        List<SearchHit> hits = mapper.toDtos(List.of(
                new SearchHitView(a, "/root", List.of()),
                new SearchHitView(b, "/root/Users", List.of(a))));
        assertThat(hits).extracting(SearchHit::getItemTreeId).containsExactly(1L, 2L);
    }
}
