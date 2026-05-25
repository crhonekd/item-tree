package com.myxcomp.ice.xtree.service.exception;

/** Maps to HTTP 403 in the HTTP layer. */
public class ForbiddenException extends ItemTreeException {
    public ForbiddenException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
