package com.codebaseai.backend.service;

/**
 * Raised when an archive would extract more data than the configured total
 * budget allows (zip-bomb guard). Mapped to HTTP 413 by the upload endpoint.
 */
public class ZipLimitExceededException extends RuntimeException {

    public ZipLimitExceededException(String message) {
        super(message);
    }
}
