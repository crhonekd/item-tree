package com.myxcomp.ice.xtree.service.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ForbiddenExceptionTest {

    @Test
    void carriesErrorCodeAndMessage() {
        ForbiddenException ex = new ForbiddenException(
                ErrorCode.NOT_IN_USER_FOLDER, "X is not yours");
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.NOT_IN_USER_FOLDER);
        assertThat(ex).hasMessage("X is not yours");
    }

    @Test
    void extendsItemTreeException() {
        ForbiddenException ex = new ForbiddenException(
                ErrorCode.NOT_IN_USER_FOLDER, "x");
        assertThat(ex).isInstanceOf(ItemTreeException.class);
    }

    @Test
    void nullErrorCodeIsRejected() {
        assertThatThrownBy(() -> new ForbiddenException(null, "x"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("errorCode");
    }
}
