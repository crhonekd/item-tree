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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HomeFolderServiceTest {

    @Mock TreeCache cache;
    HomeFolderService service;

    @BeforeEach
    void setUp() {
        service = new HomeFolderService(cache);
    }

    @Test
    void returnsTheFolderWhenPresent() {
        CachedNode node = new CachedNode(42L, 2L, "alice", "Folder", Instant.EPOCH, "sys");
        when(cache.findHomeFolder("alice")).thenReturn(Optional.of(node));

        assertThat(service.findHomeFolder("alice")).isSameAs(node);
    }

    @Test
    void throwsNotFoundExceptionWhenAbsent() {
        when(cache.findHomeFolder("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findHomeFolder("ghost"))
                .isInstanceOf(NotFoundException.class)
                .satisfies(t -> assertThat(((NotFoundException) t).errorCode())
                        .isEqualTo(ErrorCode.HOME_FOLDER_NOT_FOUND))
                .hasMessageContaining("ghost");
    }

    @Test
    void rejectsNullUserName() {
        assertThatThrownBy(() -> service.findHomeFolder(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("userName");
    }
}
