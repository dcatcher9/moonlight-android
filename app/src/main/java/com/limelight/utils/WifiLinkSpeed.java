package com.limelight.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TransportInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

/** Reads the headset's current negotiated Wi-Fi receive and transmit link rates. */
public final class WifiLinkSpeed {
    public static final int UNKNOWN_MBPS = -1;

    public static final class Snapshot {
        public final int downloadMbps;
        public final int uploadMbps;

        public Snapshot(int downloadMbps, int uploadMbps) {
            this.downloadMbps = normalize(downloadMbps);
            this.uploadMbps = normalize(uploadMbps);
        }

        public boolean hasAnySpeed() {
            return downloadMbps != UNKNOWN_MBPS || uploadMbps != UNKNOWN_MBPS;
        }
    }

    private WifiLinkSpeed() {
    }

    @SuppressWarnings("deprecation")
    public static Snapshot read(Context context) {
        WifiInfo wifiInfo = null;
        ConnectivityManager connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network activeNetwork = connectivityManager != null
                ? connectivityManager.getActiveNetwork() : null;
        NetworkCapabilities capabilities = activeNetwork != null
                ? connectivityManager.getNetworkCapabilities(activeNetwork) : null;
        if (capabilities == null
                || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return new Snapshot(UNKNOWN_MBPS, UNKNOWN_MBPS);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                TransportInfo transportInfo = capabilities.getTransportInfo();
                if (transportInfo instanceof WifiInfo) {
                    wifiInfo = (WifiInfo) transportInfo;
                }
            }
        }

        // Some Android builds omit WifiInfo from NetworkCapabilities. The legacy accessor still
        // exposes non-sensitive link-rate fields and gives us a safe fallback on those devices.
        if (wifiInfo == null) {
            WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                wifiInfo = wifiManager.getConnectionInfo();
            }
        }

        if (wifiInfo == null) {
            return new Snapshot(UNKNOWN_MBPS, UNKNOWN_MBPS);
        }

        int fallbackMbps = normalize(wifiInfo.getLinkSpeed());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return new Snapshot(fallbackMbps, fallbackMbps);
        }

        int downloadMbps = normalize(wifiInfo.getRxLinkSpeedMbps());
        int uploadMbps = normalize(wifiInfo.getTxLinkSpeedMbps());
        if (downloadMbps == UNKNOWN_MBPS && uploadMbps == UNKNOWN_MBPS) {
            return new Snapshot(fallbackMbps, fallbackMbps);
        }
        return new Snapshot(downloadMbps, uploadMbps);
    }

    private static int normalize(int speedMbps) {
        return speedMbps > 0 ? speedMbps : UNKNOWN_MBPS;
    }
}
