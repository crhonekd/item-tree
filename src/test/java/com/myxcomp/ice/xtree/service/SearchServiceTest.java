package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
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

    private CachedNode node(long id, String name) {
        return new CachedNode(id, 1L, name, "Report", Instant.EPOCH, "sys");
    }

    @Nested
    class Search {

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "\t"})
        void blankQueryReturnsEmptyListAndDoesNotTouchCache(String q) {
            assertThat(service.search(q, OptionalInt.empty())).isEmpty();
            verify(cache, never()).searchById(anyLong());
            verify(cache, never()).searchByName(any(), any());
        }

        @Test
        void nullQueryReturnsEmptyList() {
            assertThat(service.search(null, OptionalInt.empty())).isEmpty();
            verify(cache, never()).searchById(anyLong());
            verify(cache, never()).searchByName(any(), any());
        }

        @Test
        void numericQueryHittingByIdReturnsSingleNode() {
            CachedNode hit = node(42L, "Report-42");
            when(cache.searchById(42L)).thenReturn(Optional.of(hit));

            assertThat(service.search("42", OptionalInt.empty())).containsExactly(hit);
            verify(cache, never()).searchByName(any(), any());
        }

        @Test
        void numericQueryWithSurroundingWhitespaceIsTrimmedBeforeParsing() {
            CachedNode hit = node(7L, "Lucky");
            when(cache.searchById(7L)).thenReturn(Optional.of(hit));

            assertThat(service.search("  7  ", OptionalInt.empty())).containsExactly(hit);
        }

        @Test
        void numericQueryMissingByIdFallsBackToNameSearch() {
            when(cache.searchById(999L)).thenReturn(Optional.empty());
            CachedNode nameHit = node(101L, "999-Report");
            when(cache.searchByName("999", OptionalInt.empty())).thenReturn(List.of(nameHit));

            assertThat(service.search("999", OptionalInt.empty())).containsExactly(nameHit);
        }

        @Test
        void nonNumericQueryGoesStraightToNameSearch() {
            CachedNode hit = node(8L, "MyReport");
            when(cache.searchByName("repo", OptionalInt.empty())).thenReturn(List.of(hit));

            assertThat(service.search("repo", OptionalInt.empty())).containsExactly(hit);
            verify(cache, never()).searchById(anyLong());
        }

        @Test
        void numericTooLargeForLongFallsBackToNameSearch() {
            String overflow = "99999999999999999999"; // > Long.MAX_VALUE
            when(cache.searchByName(overflow, OptionalInt.empty())).thenReturn(List.of());

            assertThat(service.search(overflow, OptionalInt.empty())).isEmpty();
            verify(cache, never()).searchById(anyLong());
            verify(cache).searchByName(overflow, OptionalInt.empty());
        }

        @Test
        void limitIsPropagatedToNameSearch() {
            when(cache.searchByName("rep", OptionalInt.of(5))).thenReturn(List.of());

            service.search("rep", OptionalInt.of(5));

            verify(cache).searchByName("rep", OptionalInt.of(5));
        }

        @Test
        void limitIsNotConsultedWhenIdHits() {
            CachedNode hit = node(3L, "Three");
            when(cache.searchById(3L)).thenReturn(Optional.of(hit));

            assertThat(service.search("3", OptionalInt.of(1))).containsExactly(hit);
            verify(cache, never()).searchByName(any(), any());
        }
    }
}
