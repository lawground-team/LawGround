package com.lawground.law.search;

/** Safe internal retrieval errors; no input, vector or upstream body in the message. */
public class LiveSearchFailure extends RuntimeException {
    public enum Code {
        INVALID_SOURCE,
        SOURCE_CHANGED,
        INVALID_INDEX,
        CORPUS_CHANGED,
        TRANSACTION_FORBIDDEN
    }

    private final Code code;

    public LiveSearchFailure(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
