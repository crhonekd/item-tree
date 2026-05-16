package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    @Mock TreeCache cache;
    SearchService service;

    @BeforeEach
    void setUp() {
        service = new SearchService(cache);
    }

    @Test
    void searchByIdReturnsCacheValue() {
        CachedNode node = new CachedNode(7L, 1L, "x", "Report", Instant.EPOCH, "sys");
        when(cache.searchById(7L)).thenReturn(Optional.of(node));

        assertThat(service.searchById(7L)).contains(node);
    }

    @Test
    void searchByIdReturnsEmptyWhenMissing() {
        when(cache.searchById(99L)).thenReturn(Optional.empty());
        assertThat(service.searchById(99L)).isEmpty();
    }

    @Test
    void searchByNamePropagatesLimit() {
        when(cache.searchByName("rep", OptionalInt.of(5))).thenReturn(List.of());
        service.searchByName("rep", OptionalInt.of(5));

        verify(cache).searchByName("rep", OptionalInt.of(5));
    }

    @Test
    void searchByNameDefaultsToEmptyLimit() {
        when(cache.searchByName("rep", OptionalInt.empty())).thenReturn(List.of());
        service.searchByName("rep", OptionalInt.empty());

        verify(cache).searchByName("rep", OptionalInt.empty());
    }

    @Test
    void searchByNameReturnsCacheHits() {
        CachedNode hit = new CachedNode(8L, 1L, "MyReport", "Report", Instant.EPOCH, "sys");
        when(cache.searchByName("report", OptionalInt.empty())).thenReturn(List.of(hit));

        assertThat(service.searchByName("report", OptionalInt.empty())).containsExactly(hit);
    }
}
