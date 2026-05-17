package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.common.UserContext;
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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TreeServiceTest {

    @Mock TreeCache cache;
    @Mock PathResolver pathResolver;
    @Mock HomeFolderService homeFolderService;
    TreeService service;

    static final Instant T = Instant.EPOCH;

    @BeforeEach
    void setUp() {
        service = new TreeService(cache, pathResolver, homeFolderService);
    }

    private static CachedNode folder(long id, long parentId, String name) {
        return new CachedNode(id, parentId, name, "Folder", T, "sys");
    }

    @Test
    void getTreeUsesImpersonatedUserForHomeFolderLookup() {
        CachedNode home = folder(10L, 2L, "alice");
        when(homeFolderService.findHomeFolder("alice")).thenReturn(home);
        when(cache.getTreeView(10L)).thenReturn(List.of(home));
        when(pathResolver.pathsOf(List.of(10L))).thenReturn(Map.of(10L, "root/Users/alice"));

        List<TreeNodeView> result = service.getTree(new UserContext("bob", "alice"));

        assertThat(result).containsExactly(new TreeNodeView(home, "root/Users/alice"));
    }

    @Test
    void getTreeFallsBackToIceUserWhenNoImpersonation() {
        CachedNode home = folder(11L, 2L, "bob");
        when(homeFolderService.findHomeFolder("bob")).thenReturn(home);
        when(cache.getTreeView(11L)).thenReturn(List.of(home));
        when(pathResolver.pathsOf(List.of(11L))).thenReturn(Map.of(11L, "root/Users/bob"));

        List<TreeNodeView> result = service.getTree(new UserContext("bob", null));

        assertThat(result).extracting(TreeNodeView::path).containsExactly("root/Users/bob");
    }

    @Test
    void getTreePreservesCacheOrderingWhilePairingPaths() {
        CachedNode root = folder(1L, 0L, "root");
        CachedNode users = folder(2L, 1L, "Users");
        CachedNode alice = folder(10L, 2L, "alice");
        when(homeFolderService.findHomeFolder("alice")).thenReturn(alice);
        when(cache.getTreeView(10L)).thenReturn(List.of(root, users, alice));
        when(pathResolver.pathsOf(List.of(1L, 2L, 10L))).thenReturn(Map.of(
                1L, "root",
                2L, "root/Users",
                10L, "root/Users/alice"
        ));

        List<TreeNodeView> result = service.getTree(new UserContext("alice", null));

        assertThat(result).containsExactly(
                new TreeNodeView(root,  "root"),
                new TreeNodeView(users, "root/Users"),
                new TreeNodeView(alice, "root/Users/alice")
        );
    }

    @Test
    void getSubtreeReturnsPairsForEveryNodeInSubtree() {
        CachedNode parent = folder(20L, 1L, "Group");
        CachedNode child  = folder(21L, 20L, "Sub");
        when(cache.getById(20L)).thenReturn(Optional.of(parent));
        when(cache.getSubtreeFlat(20L)).thenReturn(List.of(parent, child));
        when(pathResolver.pathsOf(List.of(20L, 21L))).thenReturn(Map.of(
                20L, "root/Group",
                21L, "root/Group/Sub"
        ));

        List<TreeNodeView> result = service.getSubtree(20L);

        assertThat(result).containsExactly(
                new TreeNodeView(parent, "root/Group"),
                new TreeNodeView(child,  "root/Group/Sub")
        );
    }

    @Test
    void getSubtreeThrowsNotFoundForUnknownRoot() {
        when(cache.getById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSubtree(999L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("999");
    }

    @Test
    void getSubtreeFallsBackToEmptyStringPathWhenResolverOmitsId() {
        CachedNode node = folder(30L, 1L, "X");
        when(cache.getById(30L)).thenReturn(Optional.of(node));
        when(cache.getSubtreeFlat(30L)).thenReturn(List.of(node));
        when(pathResolver.pathsOf(anyCollection())).thenReturn(Map.of());

        List<TreeNodeView> result = service.getSubtree(30L);

        assertThat(result).containsExactly(new TreeNodeView(node, ""));
    }
}
