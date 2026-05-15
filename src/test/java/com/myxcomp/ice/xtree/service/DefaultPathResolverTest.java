package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.DefaultTreeCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultPathResolverTest {

    DefaultTreeCache cache;
    DefaultPathResolver resolver;
    static final Instant T = Instant.EPOCH;

    static CachedNode folder(long id, long parentId, String name) {
        return new CachedNode(id, parentId, name, "Folder", T, "sys");
    }

    static CachedNode leaf(long id, long parentId, String name) {
        return new CachedNode(id, parentId, name, "Report", T, "sys");
    }

    static void loadFixture(DefaultTreeCache c) {
        c.applyCreate(folder(1L,  0L, "root"));
        c.applyCreate(folder(2L,  1L, "Users"));
        c.applyCreate(folder(10L, 2L, "testuser1"));
        c.applyCreate(folder(12L, 2L, "deepuser"));
        c.applyCreate(folder(20L, 12L, "L2"));
        c.applyCreate(folder(21L, 20L, "L3"));
        c.applyCreate(folder(22L, 21L, "L4"));
        c.applyCreate(leaf  (110L, 10L, "MyReport"));
        c.applyCreate(leaf  (210L, 22L, "DeepReport"));
    }

    @BeforeEach
    void setUp() {
        cache = new DefaultTreeCache();
        resolver = new DefaultPathResolver(cache);
    }

    @Nested
    class PathOf {

        @Test
        void rootReturnsItsName() {
            loadFixture(cache);
            assertThat(resolver.pathOf(1L)).isEqualTo("root");
        }

        @Test
        void depth1ReturnsRootSlashName() {
            loadFixture(cache);
            assertThat(resolver.pathOf(2L)).isEqualTo("root/Users");
        }

        @Test
        void depth2ReturnsThreeSegments() {
            loadFixture(cache);
            assertThat(resolver.pathOf(10L)).isEqualTo("root/Users/testuser1");
        }

        @Test
        void deepLeafReturnsFullPath() {
            loadFixture(cache);
            assertThat(resolver.pathOf(210L))
                    .isEqualTo("root/Users/deepuser/L2/L3/L4/DeepReport");
        }

        @Test
        void unknownIdReturnsEmptyString() {
            loadFixture(cache);
            assertThat(resolver.pathOf(999L)).isEmpty();
        }
    }
}
