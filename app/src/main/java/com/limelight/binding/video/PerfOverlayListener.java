package com.limelight.binding.video;

public interface PerfOverlayListener {
    /** Structured decoder performance update with unambiguous units and stage names. */
    void onPerfUpdate(final StreamPerformanceSnapshot snapshot);
}
