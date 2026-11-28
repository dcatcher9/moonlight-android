package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HostTimestampFramePacerTest {
    private static final long MS = HostTimestampFramePacer.NS_PER_MS;
    private static final long FRAME_NS = 1_000_000_000L / 90L;
    /** This device's clock when the host's clock reads zero. */
    private static final long CLOCK_OFFSET_NS = 7_123_456_789L;

    private static long hostMs(int frame) {
        return frame * FRAME_NS / MS;
    }

    /** The device time at which a frame with no delay at all would be ready. */
    private static long earliestReadyNs(int frame) {
        return hostMs(frame) * MS + CLOCK_OFFSET_NS;
    }

    @Test
    public void spacesUnevenArrivalsByTheHostTimes() {
        HostTimestampFramePacer pacer = new HostTimestampFramePacer(FRAME_NS);
        // Every third frame is ready 6 ms after the others, as a host wait makes it.
        long[] release = new long[900];
        for (int frame = 0; frame < release.length; frame++) {
            long lateNs = frame % 3 == 2 ? 6L * MS : 0L;
            release[frame] = pacer.releaseTimeNs(hostMs(frame), earliestReadyNs(frame) + lateNs);
        }
        // Once the delay covers the 6 ms, every release follows the host's spacing exactly.
        assertTrue(pacer.delayNs() >= 6L * MS);
        for (int frame = 600; frame < release.length; frame++) {
            assertEquals("frame " + frame,
                    (hostMs(frame) - hostMs(frame - 1)) * MS, release[frame] - release[frame - 1]);
        }
    }

    @Test
    public void neverReleasesBeforeTheFrameIsReady() {
        HostTimestampFramePacer pacer = new HostTimestampFramePacer(FRAME_NS);
        for (int frame = 0; frame < 300; frame++) {
            pacer.releaseTimeNs(hostMs(frame), earliestReadyNs(frame));
        }
        // One frame far later than any delay goes out at once.
        long readyNs = earliestReadyNs(300) + 40L * MS;
        assertEquals(readyNs, pacer.releaseTimeNs(hostMs(300), readyNs));
    }

    @Test
    public void lengthensTheDelayAfterAFewLateFramesAndShortensItSlowly() {
        HostTimestampFramePacer pacer = new HostTimestampFramePacer(FRAME_NS);
        int frame = 0;
        for (; frame < 400; frame++) {
            pacer.releaseTimeNs(hostMs(frame), earliestReadyNs(frame));
        }
        long steadyNs = pacer.delayNs();
        assertTrue(steadyNs <= HostTimestampFramePacer.INITIAL_DELAY_NS);

        // The third frame later than the delay lengthens it, without waiting for the window.
        for (int late = 0; late < HostTimestampFramePacer.LATE_FRAMES_FOR_ATTACK; late++, frame++) {
            pacer.releaseTimeNs(hostMs(frame), earliestReadyNs(frame) + 8L * MS);
        }
        assertEquals(8L * MS + HostTimestampFramePacer.MARGIN_NS, pacer.delayNs());

        // Even arrivals again: the delay comes down one step for each window, not at once.
        long lengthenedNs = pacer.delayNs();
        int framesPerWindow = (int) (HostTimestampFramePacer.WINDOW_NS / FRAME_NS) + 1;
        for (int i = 0; i < 3 * framesPerWindow; i++, frame++) {
            pacer.releaseTimeNs(hostMs(frame), earliestReadyNs(frame));
        }
        assertTrue(pacer.delayNs() < lengthenedNs);
        assertTrue(pacer.delayNs() >= lengthenedNs - 3L * HostTimestampFramePacer.DECAY_STEP_NS);
    }

    @Test
    public void keepsTheDelayWithinTwoFrames() {
        HostTimestampFramePacer pacer = new HostTimestampFramePacer(FRAME_NS);
        for (int frame = 0; frame < 600; frame++) {
            long lateNs = frame % 2 == 0 ? 0L : 45L * MS;
            pacer.releaseTimeNs(hostMs(frame), earliestReadyNs(frame) + lateNs);
        }
        assertEquals(2L * FRAME_NS, pacer.delayNs());
    }

    @Test
    public void followsTheClocksAsTheyDriftApart() {
        HostTimestampFramePacer pacer = new HostTimestampFramePacer(FRAME_NS);
        // This device's clock gains 100 microseconds a second on the host's: 3 ms over 30 s.
        long lastRelease = 0L;
        for (int frame = 0; frame < 2700; frame++) {
            long driftNs = hostMs(frame) * MS / 10_000L;
            long readyNs = earliestReadyNs(frame) + driftNs;
            lastRelease = pacer.releaseTimeNs(hostMs(frame), readyNs);
            // The frame never waits longer than the delay plus the drift of one look-back.
            assertTrue("frame " + frame, lastRelease - readyNs
                    <= pacer.delayNs() + HostTimestampFramePacer.NS_PER_MS);
        }
        assertTrue(pacer.delayNs() <= HostTimestampFramePacer.INITIAL_DELAY_NS);
    }

    @Test
    public void keepsItsLearningWhenAHostTimeStepsBackALittle() {
        HostTimestampFramePacer pacer = new HostTimestampFramePacer(FRAME_NS);
        for (int frame = 0; frame < 400; frame++) {
            // Every tenth frame carries a time 3 ms before its predecessor's.
            long host = hostMs(frame) - (frame % 10 == 9 ? 14L : 0L);
            long readyNs = hostMs(frame) * MS + CLOCK_OFFSET_NS;
            long releaseNs = pacer.releaseTimeNs(host, readyNs);
            if (frame % 10 == 9) {
                assertEquals("frame " + frame, readyNs, releaseNs);
            }
        }
        String report = pacer.takeReport();
        assertNotNull(report);
        assertTrue(report, report.contains("resets=0"));
        assertTrue(report, report.contains("untimed=40"));
    }

    @Test
    public void aPictureSentAgainDoesNotLengthenTheDelay() {
        HostTimestampFramePacer pacer = new HostTimestampFramePacer(FRAME_NS);
        int frame = 0;
        for (; frame < 400; frame++) {
            pacer.releaseTimeNs(hostMs(frame), earliestReadyNs(frame));
        }
        long delayNs = pacer.delayNs();
        // The picture stands still: the host sends it again every 100 ms under the same time.
        long stillHostMs = hostMs(frame - 1);
        for (int again = 1; again <= 20; again++) {
            long readyNs = earliestReadyNs(frame - 1) + again * 100L * MS;
            assertEquals(readyNs, pacer.releaseTimeNs(stillHostMs, readyNs));
        }
        assertEquals(delayNs, pacer.delayNs());
        // Motion resumes two seconds later and is paced as before.
        int resumed = frame + 180;
        long readyNs = earliestReadyNs(resumed);
        assertTrue(pacer.releaseTimeNs(hostMs(resumed), readyNs) - readyNs <= delayNs);
        assertEquals(delayNs, pacer.delayNs());
    }

    @Test
    public void spacesTwoFramesThatAskForTheSameMoment() {
        HostTimestampFramePacer pacer = new HostTimestampFramePacer(FRAME_NS);
        long spacingNs = FRAME_NS * 4L / 5L;
        long first = pacer.spacedReleaseNs(1_000L * MS, 995L * MS);
        assertEquals(1_000L * MS, first);
        // A frame stamped before its predecessor still follows it, most of a frame later.
        long second = pacer.spacedReleaseNs(998L * MS, 996L * MS);
        assertEquals(first + spacingNs, second);
        // A frame already far enough behind keeps its own time.
        long third = pacer.spacedReleaseNs(second + FRAME_NS, second);
        assertEquals(second + FRAME_NS, third);
    }

    @Test
    public void givesUpTheSpacingOnceItFallsTooFarBehind() {
        HostTimestampFramePacer pacer = new HostTimestampFramePacer(FRAME_NS);
        long spacingNs = FRAME_NS * 4L / 5L;
        long readyNs = 1_000L * MS;
        long release = pacer.spacedReleaseNs(readyNs, readyNs);
        // Frames that all ask for the same moment queue up behind one another...
        long behindNs = 0L;
        int queued = 0;
        while (behindNs <= 2L * FRAME_NS + spacingNs) {
            long next = pacer.spacedReleaseNs(readyNs, readyNs);
            if (next == readyNs) {
                break;
            }
            assertEquals(release + spacingNs, next);
            release = next;
            behindNs = release - readyNs;
            queued++;
        }
        // ...until one would wait longer than the longest delay plus one spacing.
        assertTrue(queued >= 3);
        assertTrue(behindNs <= 2L * FRAME_NS + spacingNs);
    }

    @Test
    public void startsAgainAfterAGapInTheHostTimes() {
        HostTimestampFramePacer pacer = new HostTimestampFramePacer(FRAME_NS);
        for (int frame = 0; frame < 200; frame++) {
            pacer.releaseTimeNs(hostMs(frame), earliestReadyNs(frame));
        }
        // The stream resumes: host times jump ahead, and the two clocks no longer line up.
        long hostJumpMs = 5_000L;
        long newOffsetNs = CLOCK_OFFSET_NS - 900L * MS;
        for (int frame = 200; frame < 400; frame++) {
            long host = hostMs(frame) + hostJumpMs;
            long readyNs = host * MS + newOffsetNs;
            long releaseNs = pacer.releaseTimeNs(host, readyNs);
            assertTrue("frame " + frame, releaseNs - readyNs <= pacer.delayNs());
        }
        String report = pacer.takeReport();
        assertNotNull(report);
        assertTrue(report, report.contains("resets=1"));
        assertNull(pacer.takeReport());
    }
}
