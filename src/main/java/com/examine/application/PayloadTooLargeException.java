package com.examine.application;

/**
 * Thrown when the JSON-serialized payload exceeds the configured maximum size.
 */
public class PayloadTooLargeException extends RuntimeException {

    private final int maxBytes;
    private final int actualBytes;

    public PayloadTooLargeException(int maxBytes, int actualBytes) {
        super("payload size " + actualBytes + " bytes exceeds maximum " + maxBytes + " bytes");
        this.maxBytes = maxBytes;
        this.actualBytes = actualBytes;
    }

    public int getMaxBytes() { return maxBytes; }
    public int getActualBytes() { return actualBytes; }
}
