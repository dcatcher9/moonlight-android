package com.limelight.sbs;

/**
 * Bounded ownership tracker for the full-resolution color frames used by Client SBS.
 *
 * <p>Each acquisition returns an immutable token. A stale worker can therefore neither advance
 * nor release a slot that has already been reset and reused by a newer renderer generation.</p>
 */
public final class ClientSbsFrameSlots {
    public enum State {
        FREE,
        CAPTURE,
        INFERENCE,
        PUBLISHED,
        ACTIVE
    }

    public static final class Lease {
        private final int slot;
        private final long token;
        private final int generation;
        private final long frameSequence;
        private final long capturedAtNs;

        private Lease(int slot, long token, int generation, long frameSequence,
                      long capturedAtNs) {
            this.slot = slot;
            this.token = token;
            this.generation = generation;
            this.frameSequence = frameSequence;
            this.capturedAtNs = capturedAtNs;
        }

        public int getSlot() {
            return slot;
        }

        public int getGeneration() {
            return generation;
        }

        public long getFrameSequence() {
            return frameSequence;
        }

        public long getCapturedAtNs() {
            return capturedAtNs;
        }
    }

    private final State[] states;
    private final long[] tokens;
    private long nextToken = 1L;

    public ClientSbsFrameSlots(int slotCount) {
        if (slotCount <= 0) {
            throw new IllegalArgumentException("slotCount must be positive");
        }
        states = new State[slotCount];
        tokens = new long[slotCount];
        reset();
    }

    public synchronized Lease tryAcquireForCapture(int generation, long frameSequence,
                                                    long capturedAtNs) {
        for (int slot = 0; slot < states.length; slot++) {
            if (states[slot] == State.FREE) {
                long token = nextToken++;
                if (token == 0L) {
                    token = nextToken++;
                }
                states[slot] = State.CAPTURE;
                tokens[slot] = token;
                return new Lease(slot, token, generation, frameSequence, capturedAtNs);
            }
        }
        return null;
    }

    public synchronized boolean markInference(Lease lease) {
        return transition(lease, State.CAPTURE, State.INFERENCE);
    }

    public synchronized boolean markPublished(Lease lease) {
        return transition(lease, State.INFERENCE, State.PUBLISHED);
    }

    public synchronized boolean markActive(Lease lease) {
        return transition(lease, State.PUBLISHED, State.ACTIVE);
    }

    /** Releases a slot only if the caller still owns the expected pipeline stage. */
    public synchronized boolean release(Lease lease, State expected) {
        if (!owns(lease) || states[lease.slot] != expected) {
            return false;
        }
        states[lease.slot] = State.FREE;
        return true;
    }

    /**
     * Releases a currently owned slot regardless of stage. Reserved for teardown, where the whole
     * generation is invalidated immediately afterwards.
     */
    public synchronized boolean release(Lease lease) {
        if (!owns(lease) || states[lease.slot] == State.FREE) {
            return false;
        }
        states[lease.slot] = State.FREE;
        return true;
    }

    /** Invalidates every outstanding lease, including leases held by stale worker generations. */
    public synchronized void reset() {
        for (int slot = 0; slot < states.length; slot++) {
            states[slot] = State.FREE;
            tokens[slot] = 0L;
        }
    }

    public synchronized State getState(int slot) {
        if (slot < 0 || slot >= states.length) {
            throw new IllegalArgumentException("invalid slot " + slot);
        }
        return states[slot];
    }

    private boolean transition(Lease lease, State expected, State next) {
        if (!owns(lease) || states[lease.slot] != expected) {
            return false;
        }
        states[lease.slot] = next;
        return true;
    }

    private boolean owns(Lease lease) {
        return lease != null && lease.slot >= 0 && lease.slot < states.length
                && tokens[lease.slot] == lease.token;
    }
}
