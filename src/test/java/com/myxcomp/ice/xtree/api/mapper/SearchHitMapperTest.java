package com.myxcomp.ice.xtree.api.mapper;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.generated.model.SearchHit;
import com.myxcomp.ice.xtree.service.SearchHitView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchHitMapperTest {

    private final SearchHitMapper mapper = new SearchHitMapper();

    @Test
    void mapsIdNameTypeAndPath() {
        CachedNode node = new CachedNode(42L, 7L, "Report-1", "Report", Instant.EPOCH, "alice");
        SearchHitView view = new SearchHitView(node, "/root/thing");

        SearchHit hit = mapper.toDto(view);

        assertThat(hit.getItemTreeId()).isEqualTo(42L);
        assertThat(hit.getName()).isEqualTo("Report-1");
        assertThat(hit.getType()).isEqualTo("Report");
        assertThat(hit.getPath()).isEqualTo("/root/thing");
    }

    @Test
    void mapsListInOrder() {
        CachedNode a = new CachedNode(1L, 0L, "root",  "Folder", Instant.EPOCH, "sys");
        CachedNode b = new CachedNode(2L, 1L, "Users", "Folder", Instant.EPOCH, "sys");

        List<SearchHit> hits = mapper.toDtos(List.of(
                new SearchHitView(a, "/root"),
                new SearchHitView(b, "/root/Users")));

        assertThat(hits).extracting(SearchHit::getItemTreeId).containsExactly(1L, 2L);
    }

    @Test
    void toDtosMapsEachViewWithCorrectPath() {
        CachedNode a = new CachedNode(1L, 0L, "root",  "Folder", Instant.EPOCH, "sys");
        CachedNode b = new CachedNode(2L, 1L, "Users", "Folder", Instant.EPOCH, "sys");
        SearchHitView viewA = new SearchHitView(a, "/root");
        SearchHitView viewB = new SearchHitView(b, "/root/child");

        List<SearchHit> out = mapper.toDtos(List.of(viewA, viewB));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).getPath()).isEqualTo("/root");
        assertThat(out.get(1).getPath()).isEqualTo("/root/child");
    }
}
