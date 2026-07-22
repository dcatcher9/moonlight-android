package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class WifiLinkSpeedTest {
    @Test
    public void wifiReceiveAndTransmitRatesMapToDownloadAndUpload() {
        Fixture fixture = new Fixture();
        when(fixture.wifiInfo.getRxLinkSpeedMbps()).thenReturn(1200);
        when(fixture.wifiInfo.getTxLinkSpeedMbps()).thenReturn(866);

        WifiLinkSpeed.Snapshot speed = WifiLinkSpeed.read(fixture.context);

        assertEquals(1200, speed.downloadMbps);
        assertEquals(866, speed.uploadMbps);
        assertTrue(speed.hasAnySpeed());
    }

    @Test
    public void missingDirectionalRateIsNotPresentedAsFalseSymmetry() {
        Fixture fixture = new Fixture();
        when(fixture.wifiInfo.getLinkSpeed()).thenReturn(2400);
        when(fixture.wifiInfo.getRxLinkSpeedMbps()).thenReturn(1200);
        when(fixture.wifiInfo.getTxLinkSpeedMbps()).thenReturn(-1);

        WifiLinkSpeed.Snapshot speed = WifiLinkSpeed.read(fixture.context);

        assertEquals(1200, speed.downloadMbps);
        assertEquals(WifiLinkSpeed.UNKNOWN_MBPS, speed.uploadMbps);
    }

    @Test
    public void nonWifiActiveRouteDoesNotExposeAnAssociatedWifiLink() {
        Fixture fixture = new Fixture();
        when(fixture.capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                .thenReturn(false);
        when(fixture.capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                .thenReturn(true);

        WifiLinkSpeed.Snapshot speed = WifiLinkSpeed.read(fixture.context);

        assertFalse(speed.hasAnySpeed());
        assertEquals(WifiLinkSpeed.UNKNOWN_MBPS, speed.downloadMbps);
        assertEquals(WifiLinkSpeed.UNKNOWN_MBPS, speed.uploadMbps);
    }

    private static final class Fixture {
        final Context context = mock(Context.class);
        final ConnectivityManager connectivityManager = mock(ConnectivityManager.class);
        final Network network = mock(Network.class);
        final NetworkCapabilities capabilities = mock(NetworkCapabilities.class);
        final WifiInfo wifiInfo = mock(WifiInfo.class);

        Fixture() {
            when(context.getSystemService(Context.CONNECTIVITY_SERVICE))
                    .thenReturn(connectivityManager);
            when(connectivityManager.getActiveNetwork()).thenReturn(network);
            when(connectivityManager.getNetworkCapabilities(network)).thenReturn(capabilities);
            when(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).thenReturn(true);
            when(capabilities.getTransportInfo()).thenReturn(wifiInfo);
        }
    }
}
