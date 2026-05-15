package com.myxcomp.ice.xtree.cache;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class CachedNodeTest {

    @Test
    void nullParentIdThrowsNpe() {
        assertThatNullPointerException()
                .isThrownBy(() -> new CachedNode(1L, null, "root", "Folder", Instant.EPOCH, "sys"))
                .withMessageContaining("parentId");
    }

    @Test
    void nonNullParentIdConstructsSuccessfully() {
        CachedNode node = new CachedNode(1L, 0L, "root", "Folder", Instant.EPOCH, "sys");
        assertThat(node.parentId()).isZero();
    }
}
