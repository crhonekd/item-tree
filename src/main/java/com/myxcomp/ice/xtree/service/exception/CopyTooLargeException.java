package com.myxcomp.ice.xtree.service.exception;

/** Maps to HTTP 413 in the HTTP layer. */
public class CopyTooLargeException extends ItemTreeException {
    public CopyTooLargeException(String message) {
        super(ErrorCode.COPY_TOO_LARGE, message);
    }
}
