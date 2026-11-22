package com.limelight;

import android.net.ConnectivityManager;
import android.net.Network;

import java.util.concurrent.Executor;

/**
 * Tracks whether the device has a default network while a stream Activity exists, and reports
 * the edges: the network was lost, and a network returned after a loss. A switch between two
 * networks, or the initial report of the current one, is not an edge.
 *
 * <p>Main-thread confined. ConnectivityManager callbacks arrive on a binder thread and are
 * handed to {@code mainThread} before any state is read or written; {@link #register},
 * {@link #unregister} and {@link #isNetworkLost} run on the main thread.</p>
 */
final class StreamNetworkMonitor {
    interface Listener {
        /** The default network went away while one was available. */
        void onNetworkLost();

        /** A default network returned after {@link #onNetworkLost()}. */
        void onNetworkReturned();
    }

    private final Executor mainThread;
    private final Listener listener;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback callback;
    private boolean networkLost;

    StreamNetworkMonitor(Executor mainThread, Listener listener) {
        this.mainThread = mainThread;
        this.listener = listener;
    }

    void register(ConnectivityManager connMgr) {
        networkLost = connMgr.getActiveNetwork() == null;
        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                mainThread.execute(StreamNetworkMonitor.this::onAvailable);
            }

            @Override
            public void onLost(Network network) {
                mainThread.execute(StreamNetworkMonitor.this::onLost);
            }
        };
        try {
            connMgr.registerDefaultNetworkCallback(callback);
            connectivityManager = connMgr;
        } catch (RuntimeException e) {
            // The platform caps callbacks per app. Recovery then relies on transport timeouts.
            LimeLog.warning("Stream network monitoring unavailable: " + e.getMessage());
            callback = null;
            networkLost = false;
        }
    }

    void unregister() {
        if (callback == null) {
            return;
        }
        try {
            connectivityManager.unregisterNetworkCallback(callback);
        } catch (IllegalArgumentException ignored) {
            // Registration failed or already ended.
        }
        callback = null;
        connectivityManager = null;
    }

    /** No default network exists; a stream transport cannot survive and recovery must wait. */
    boolean isNetworkLost() {
        return networkLost;
    }

    void onLost() {
        if (networkLost) {
            return;
        }
        networkLost = true;
        listener.onNetworkLost();
    }

    void onAvailable() {
        if (!networkLost) {
            // Registration reports the current default network, and a switch between two
            // networks never lost connectivity.
            return;
        }
        networkLost = false;
        listener.onNetworkReturned();
    }
}
