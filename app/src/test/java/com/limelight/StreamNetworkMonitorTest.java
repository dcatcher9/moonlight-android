package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.net.ConnectivityManager;
import android.net.Network;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class StreamNetworkMonitorTest {
    private final List<String> edges = new ArrayList<>();
    private final List<Runnable> posted = new ArrayList<>();
    private final StreamNetworkMonitor monitor = new StreamNetworkMonitor(posted::add,
            new StreamNetworkMonitor.Listener() {
                @Override public void onNetworkLost() { edges.add("lost"); }
                @Override public void onNetworkReturned() { edges.add("returned"); }
            });

    @Test public void reportsOnlyLossAndReturnEdges() {
        ConnectivityManager connMgr = mock(ConnectivityManager.class);
        when(connMgr.getActiveNetwork()).thenReturn(mock(Network.class));
        monitor.register(connMgr);
        ArgumentCaptor<ConnectivityManager.NetworkCallback> callback =
                ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback.class);
        verify(connMgr).registerDefaultNetworkCallback(callback.capture());
        assertFalse(monitor.isNetworkLost());

        // The registration report of the current network, and a network switch, are not edges.
        deliver(() -> callback.getValue().onAvailable(mock(Network.class)));
        assertTrue(edges.isEmpty());

        deliver(() -> callback.getValue().onLost(mock(Network.class)));
        deliver(() -> callback.getValue().onLost(mock(Network.class)));
        assertTrue(monitor.isNetworkLost());
        deliver(() -> callback.getValue().onAvailable(mock(Network.class)));
        assertFalse(monitor.isNetworkLost());

        assertEquals(java.util.Arrays.asList("lost", "returned"), edges);

        monitor.unregister();
        verify(connMgr).unregisterNetworkCallback(callback.getValue());
    }

    @Test public void callbackStateChangesOnlyOnTheMainThreadExecutor() {
        ConnectivityManager connMgr = mock(ConnectivityManager.class);
        when(connMgr.getActiveNetwork()).thenReturn(mock(Network.class));
        monitor.register(connMgr);
        ArgumentCaptor<ConnectivityManager.NetworkCallback> callback =
                ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback.class);
        verify(connMgr).registerDefaultNetworkCallback(callback.capture());

        callback.getValue().onLost(mock(Network.class));
        assertFalse(monitor.isNetworkLost());
        posted.remove(0).run();
        assertTrue(monitor.isNetworkLost());
    }

    @Test public void startsLostWithoutADefaultNetwork() {
        ConnectivityManager connMgr = mock(ConnectivityManager.class);
        monitor.register(connMgr);
        assertTrue(monitor.isNetworkLost());
    }

    @Test public void refusedRegistrationAssumesANetworkAndUnregistersNothing() {
        ConnectivityManager connMgr = mock(ConnectivityManager.class);
        doThrow(new RuntimeException("too many callbacks"))
                .when(connMgr).registerDefaultNetworkCallback(any());

        monitor.register(connMgr);
        monitor.unregister();

        assertFalse(monitor.isNetworkLost());
        verify(connMgr, never()).unregisterNetworkCallback(
                any(ConnectivityManager.NetworkCallback.class));
    }

    private void deliver(Runnable binderCallback) {
        binderCallback.run();
        while (!posted.isEmpty()) {
            posted.remove(0).run();
        }
    }
}
