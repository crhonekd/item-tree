package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TreeServiceSubtreeNotFoundTest {

    @Mock TreeCache cache;
    @Mock PathResolver pathResolver;
    @Mock HomeFolderService homeFolderService;

    TreeService service;

    @BeforeEach
    void setUp() {
        service = new TreeService(cache, pathResolver, homeFolderService);
    }

    @Test
    void throwsNotFoundWhenRootMissing() {
        when(cache.getById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSubtree(99L))
                .isInstanceOf(NotFoundException.class)
                .satisfies(t -> assertThat(((NotFoundException) t).errorCode())
                        .isEqualTo(ErrorCode.ITEM_NOT_FOUND))
                .hasMessageContaining("99");
    }

    @Test
    void returnsTheSubtreeWhenRootExists() {
        CachedNode root = new CachedNode(99L, 0L, "n", "Folder", Instant.EPOCH, "sys");
        when(cache.getById(99L)).thenReturn(Optional.of(root));
        when(cache.getSubtreeFlat(99L)).thenReturn(List.of(root));
        when(pathResolver.pathsOf(List.of(99L))).thenReturn(Map.of(99L, "root/n"));

        List<TreeNodeView> result = service.getSubtree(99L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).path()).isEqualTo("root/n");
    }
}
