package com.myxcomp.ice.xtree.service.exception;

/** Maps to HTTP 400 in Phase 8. */
public class ValidationException extends ItemTreeException {
    public ValidationException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
