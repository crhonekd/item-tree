package com.myxcomp.ice.xtree.api.mapper;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.generated.model.SearchHit;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchHitMapperTest {

    private final SearchHitMapper mapper = new SearchHitMapper();

    @Test
    void mapsTheThreeFieldsOnly() {
        CachedNode node = new CachedNode(42L, 7L, "Report-1", "Report", Instant.EPOCH, "alice");

        SearchHit hit = mapper.toDto(node);

        assertThat(hit.getItemTreeId()).isEqualTo(42L);
        assertThat(hit.getName()).isEqualTo("Report-1");
        assertThat(hit.getType()).isEqualTo("Report");
    }

    @Test
    void mapsListInOrder() {
        CachedNode a = new CachedNode(1L, 0L, "root",  "Folder", Instant.EPOCH, "sys");
        CachedNode b = new CachedNode(2L, 1L, "Users", "Folder", Instant.EPOCH, "sys");

        List<SearchHit> hits = mapper.toDtos(List.of(a, b));

        assertThat(hits).extracting(SearchHit::getItemTreeId).containsExactly(1L, 2L);
    }
}
