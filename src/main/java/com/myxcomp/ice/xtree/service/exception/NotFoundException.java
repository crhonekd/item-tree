package com.myxcomp.ice.xtree.service.exception;

/** Maps to HTTP 404 in Phase 8. */
public class NotFoundException extends ItemTreeException {
    public NotFoundException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
