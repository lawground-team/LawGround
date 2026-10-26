package com.lawground.job.service;

/** Safe stage codes, never an upstream error message or legal verdict. */
public final class LiveWorkerFailure extends RuntimeException {
    public enum Code {
        VERIFIER_NOT_CONFIGURED,
        VERIFICATION_CANDIDATES_UNAVAILABLE,
        VERIFICATION_EXTENSION_UNSUPPORTED,
        PROMPT_VERSION_MISMATCH,
        MODEL_NOT_READY,
        MODEL_REVISION_MISMATCH,
        AI_CONFIGURATION_INVALID,
        AI_INPUT_INVALID,
        AI_CONTRACT_INVALID,
        CORPUS_CHANGED,
        INVALID_SOURCE,
        INVALID_INDEX,
        INDEX_NOT_READY,
        LAW_VERSION_UNAVAILABLE,
        SNAPSHOT_INVALID,
        CHECKPOINT_INVALID,
        ACCESS_DENIED,
        PIPELINE_VERSION_MISMATCH
    }

    private final Code code;

    public LiveWorkerFailure(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
