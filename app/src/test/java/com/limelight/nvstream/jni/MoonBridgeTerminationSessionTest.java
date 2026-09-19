package com.limelight.nvstream.jni;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.limelight.nvstream.NvConnectionListener;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = MoonBridgeTerminationSessionTest.NoNativeMoonBridge.class)
public class MoonBridgeTerminationSessionTest {
    @Implements(value = MoonBridge.class, isInAndroidSdk = false)
    public static class NoNativeMoonBridge {
        @Implementation
        protected static void __staticInitializer__() {
            // Exercise real bridge setup, cleanup, and dispatch without loading Android JNI.
        }
    }

    @Before
    @After
    public void cleanupBridge() {
        MoonBridge.cleanupBridge();
    }

    @Test
    public void gameSourceStatusPreservesUnsignedTokensAndStopsAfterCleanup() {
        NvConnectionListener listener = mock(NvConnectionListener.class);
        MoonBridge.setupBridge(null, null, listener);
        MoonBridge.bridgeClGameSourceStatus(1, 1, 0x89abcdef, 0xfedcba98, 1920, 1080, 3840, 1080);
        verify(listener).onGameSourceStatus(1, 1, 0x89abcdef, 0xfedcba98, 1920, 1080, 3840, 1080);
        MoonBridge.cleanupBridge();
        MoonBridge.bridgeClGameSourceStatus(0, 0, 0x89abcdef, 0xfedcba99, 1920, 1080, 3840, 1080);
        verifyNoMoreInteractions(listener);
    }

    @Test
    public void delayedTerminationCannotStopReplacementSession() {
        NvConnectionListener listenerA = mock(NvConnectionListener.class);
        NvConnectionListener listenerB = mock(NvConnectionListener.class);
        long sessionA = MoonBridge.setupBridge(null, null, listenerA);
        Runnable delayedTerminationA = () -> MoonBridge.bridgeClConnectionTerminated(-17, sessionA);

        MoonBridge.cleanupBridge();
        long sessionB = MoonBridge.setupBridge(null, null, listenerB);
        assertNotEquals(sessionA, sessionB);

        AtomicBoolean sessionBStopped = new AtomicBoolean();
        doAnswer(invocation -> {
            sessionBStopped.set(true);
            MoonBridge.cleanupBridge();
            return null;
        }).when(listenerB).connectionTerminated(anyInt());

        delayedTerminationA.run();
        assertFalse(sessionBStopped.get());
        verifyNoInteractions(listenerA, listenerB);

        MoonBridge.bridgeClConnectionTerminated(-29, sessionB);
        assertTrue(sessionBStopped.get());
        verify(listenerB).connectionTerminated(-29);
        verifyNoMoreInteractions(listenerB);
    }

    @Test
    public void reusedListenerStillRejectsPreviousSessionIdentity() {
        NvConnectionListener listener = mock(NvConnectionListener.class);
        long sessionA = MoonBridge.setupBridge(null, null, listener);
        MoonBridge.cleanupBridge();
        long sessionB = MoonBridge.setupBridge(null, null, listener);

        MoonBridge.bridgeClConnectionTerminated(-17, sessionA);
        verifyNoInteractions(listener);

        MoonBridge.bridgeClConnectionTerminated(-29, sessionB);
        verify(listener).connectionTerminated(-29);
        verifyNoMoreInteractions(listener);
    }

    @Test
    public void terminationAfterCleanupIsIgnored() {
        NvConnectionListener listener = mock(NvConnectionListener.class);
        long sessionId = MoonBridge.setupBridge(null, null, listener);
        MoonBridge.cleanupBridge();

        MoonBridge.bridgeClConnectionTerminated(-17, sessionId);

        verifyNoInteractions(listener);
    }

    @Test
    public void listenerCanStopBridgeWithoutHoldingTheStartStopMonitor() {
        NvConnectionListener listener = mock(NvConnectionListener.class);
        long sessionId = MoonBridge.setupBridge(null, null, listener);
        doAnswer(invocation -> {
            // Holding this monitor across listener code can deadlock a synchronous stop.
            assertFalse(Thread.holdsLock(MoonBridge.class));
            MoonBridge.cleanupBridge();
            return null;
        }).when(listener).connectionTerminated(-17);

        MoonBridge.bridgeClConnectionTerminated(-17, sessionId);
        MoonBridge.bridgeClConnectionTerminated(-29, sessionId);

        verify(listener).connectionTerminated(-17);
        verifyNoMoreInteractions(listener);
    }
}
