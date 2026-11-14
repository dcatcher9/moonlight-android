package com.limelight.ui;

/**
 * State of one StreamContainer surface transaction: the generation that identifies its current
 * owner, the callback that owner is waiting for, and its absolute deadline. StreamContainer reads
 * and writes the fields directly on the main thread; the type holds no transaction logic.
 */
final class OwnedTransaction {
    /**
     * Advanced for every new owner and on cancellation, so a queued continuation that captured an
     * older value recognises it is stale. Volatile to keep the visibility the Client SBS
     * mode-switch generation had as a volatile StreamContainer field; the host-resize and HDR
     * transactions share the type.
     */
    volatile int generation;
    /** Non-null exactly while an owner is pending. */
    StreamContainer.SurfaceSwitchCallback callback;
    /** Uptime deadline of the pending owner; 0 when none is stored here. */
    long deadlineMs;

    /** Retires the pending owner without invoking its callback. */
    void cancel() {
        generation++;
        callback = null;
        deadlineMs = 0L;
    }
}
