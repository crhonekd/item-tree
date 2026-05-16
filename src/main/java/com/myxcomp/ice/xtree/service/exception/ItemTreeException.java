package com.myxcomp.ice.xtree.service.exception;

import java.util.Objects;

public abstract class ItemTreeException extends RuntimeException {

    private final ErrorCode errorCode;

    protected ItemTreeException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
