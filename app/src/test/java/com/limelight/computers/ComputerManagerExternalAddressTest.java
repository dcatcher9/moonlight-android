package com.limelight.computers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.limelight.nvstream.http.ComputerDetails;

import org.junit.Test;

import java.util.concurrent.locks.ReentrantLock;

/** STUN discovery runs off a VPN, and on a VPN only through a bound underlying network. */
public final class ComputerManagerExternalAddressTest {
    private static final class RecordingProbe implements ComputerManagerService.ExternalAddressProbe {
        final boolean bindSucceeds;
        int bindCalls;
        int unbindCalls;
        int stunCalls;
        boolean lockHeldDuringStun;
        final ReentrantLock lock;

        RecordingProbe(boolean bindSucceeds, ReentrantLock lock) {
            this.bindSucceeds = bindSucceeds;
            this.lock = lock;
        }

        @Override public boolean bindToUnderlyingNetwork() {
            bindCalls++;
            return bindSucceeds;
        }

        @Override public void unbindNetwork() {
            unbindCalls++;
        }

        @Override public String findExternalAddress() {
            stunCalls++;
            lockHeldDuringStun = lock.isHeldByCurrentThread();
            return "203.0.113.7";
        }
    }

    @Test public void withoutAVpnTheStunRequestRunsOnTheDefaultNetwork() {
        ReentrantLock lock = new ReentrantLock();
        RecordingProbe probe = new RecordingProbe(true, lock);
        ComputerDetails details = details();

        ComputerManagerService.populateExternalAddress(details, false, lock, probe);

        assertEquals(1, probe.stunCalls);
        assertEquals(0, probe.bindCalls);
        assertEquals(0, probe.unbindCalls);
        assertFalse(probe.lockHeldDuringStun);
        assertEquals("203.0.113.7", details.remoteAddress.address);
        assertEquals(47989, details.remoteAddress.port);
    }

    @Test public void onAVpnTheStunRequestRunsOnlyThroughABoundNetwork() {
        ReentrantLock lock = new ReentrantLock();
        RecordingProbe probe = new RecordingProbe(true, lock);
        ComputerDetails details = details();

        ComputerManagerService.populateExternalAddress(details, true, lock, probe);

        assertEquals(1, probe.bindCalls);
        assertEquals(1, probe.stunCalls);
        assertTrue(probe.lockHeldDuringStun);
        assertEquals(1, probe.unbindCalls);
        assertFalse(lock.isLocked());
        assertEquals("203.0.113.7", details.remoteAddress.address);
    }

    @Test public void onAVpnWithoutAnUnderlyingNetworkNoStunRequestIsSent() {
        ReentrantLock lock = new ReentrantLock();
        RecordingProbe probe = new RecordingProbe(false, lock);
        ComputerDetails details = details();

        ComputerManagerService.populateExternalAddress(details, true, lock, probe);

        assertEquals(0, probe.stunCalls);
        assertEquals(0, probe.unbindCalls);
        assertFalse(lock.isLocked());
        assertNull(details.remoteAddress);
    }

    private static ComputerDetails details() {
        ComputerDetails details = new ComputerDetails();
        details.localAddress = new ComputerDetails.AddressTuple("192.168.1.20", 47989);
        details.activeAddress = details.localAddress;
        return details;
    }
}
