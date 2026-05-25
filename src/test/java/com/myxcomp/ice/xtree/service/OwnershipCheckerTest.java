package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.ForbiddenException;
import com.myxcomp.ice.xtree.service.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OwnershipCheckerTest {

    private static final Instant T = Instant.parse("2026-05-25T10:00:00Z");

    @Mock TreeCache cache;

    OwnershipChecker checker;

    @BeforeEach
    void setUp() {
        checker = new OwnershipChecker(cache);
    }

    @Nested
    class RequireHomeFolderExists {

        @Test
        void returnsHomeFolderWhenPresent() {
            CachedNode home = new CachedNode(10L, 2L, "alice", "Folder", T, "sys");
            when(cache.findHomeFolder("alice")).thenReturn(Optional.of(home));

            CachedNode result = checker.requireHomeFolderExists("alice");

            assertThat(result).isSameAs(home);
        }

        @Test
        void throwsHomeFolderNotFoundWhenAbsent() {
            when(cache.findHomeFolder("ghost")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> checker.requireHomeFolderExists("ghost"))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("ghost")
                    .satisfies(e -> assertThat(((NotFoundException) e).errorCode())
                            .isEqualTo(ErrorCode.HOME_FOLDER_NOT_FOUND));
        }

        @Test
        void rejectsNullUser() {
            assertThatThrownBy(() -> checker.requireHomeFolderExists(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("effectiveUser");
        }
    }

    @Nested
    class RequireOwned {

        private final CachedNode home = new CachedNode(10L, 2L, "alice", "Folder", T, "sys");

        @Test
        void allowsItemThatIsTheHomeFolderItself() {
            assertThatCode(() -> checker.requireOwned(10L, home, "alice", "Item"))
                    .doesNotThrowAnyException();
        }

        @Test
        void allowsItemThatIsInHomeSubtree() {
            when(cache.isAncestor(10L, 99L)).thenReturn(true);

            assertThatCode(() -> checker.requireOwned(99L, home, "alice", "Item"))
                    .doesNotThrowAnyException();
        }

        @Test
        void throwsForbiddenWhenItemIsNotInHomeSubtree() {
            when(cache.isAncestor(10L, 99L)).thenReturn(false);

            assertThatThrownBy(() -> checker.requireOwned(99L, home, "alice", "Parent"))
                    .isInstanceOf(ForbiddenException.class)
                    .satisfies(e -> assertThat(((ForbiddenException) e).errorCode())
                            .isEqualTo(ErrorCode.NOT_IN_USER_FOLDER))
                    .hasMessageContaining("Parent")
                    .hasMessageContaining("99")
                    .hasMessageContaining("alice");
        }

        @Test
        void contextLabelAppearsInDetail() {
            when(cache.isAncestor(10L, 50L)).thenReturn(false);

            assertThatThrownBy(() -> checker.requireOwned(50L, home, "alice", "New parent"))
                    .hasMessageContaining("New parent 50");
        }

        @Test
        void rejectsNullHomeFolder() {
            assertThatThrownBy(() -> checker.requireOwned(1L, null, "alice", "Item"))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("homeFolder");
        }

        @Test
        void rejectsNullUser() {
            assertThatThrownBy(() -> checker.requireOwned(1L, home, null, "Item"))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("effectiveUser");
        }

        @Test
        void rejectsNullContextLabel() {
            assertThatThrownBy(() -> checker.requireOwned(1L, home, "alice", null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("contextLabel");
        }
    }
}
