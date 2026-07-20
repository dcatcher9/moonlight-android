package com.limelight.binding.video;

public interface PerfOverlayListener {
    void onPerfUpdate(final String text);

    /**
     * Structured update for consumers that need unambiguous units and stage names. The default
     * preserves compatibility with the legacy formatted-text listener.
     */
    default void onPerfUpdate(final StreamPerformanceSnapshot snapshot,
                              final String legacyText) {
        onPerfUpdate(legacyText);
    }
}
