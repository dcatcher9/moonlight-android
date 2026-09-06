package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowNetworkInfo;
import org.robolectric.shadows.ShadowWifiInfo;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class WifiLinkSpeedTest {
    @Test
    public void wifiReceiveAndTransmitRatesMapToDownloadAndUpload() {
        Fixture fixture = new Fixture();
        fixture.setLinkSpeeds(2400, 1200, 866);

        WifiLinkSpeed.Snapshot speed = WifiLinkSpeed.read(fixture.context);

        assertEquals(1200, speed.downloadMbps);
        assertEquals(866, speed.uploadMbps);
        assertTrue(speed.hasAnySpeed());
    }

    @Test
    public void missingDirectionalRateIsNotPresentedAsFalseSymmetry() {
        Fixture fixture = new Fixture();
        fixture.setLinkSpeeds(2400, 1200, -1);

        WifiLinkSpeed.Snapshot speed = WifiLinkSpeed.read(fixture.context);

        assertEquals(1200, speed.downloadMbps);
        assertEquals(WifiLinkSpeed.UNKNOWN_MBPS, speed.uploadMbps);
    }

    @Test
    public void nonWifiActiveRouteDoesNotExposeAnAssociatedWifiLink() {
        Fixture fixture = new Fixture();
        fixture.setLinkSpeeds(2400, 1200, 866);
        Shadows.shadowOf(fixture.capabilities).removeTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        Shadows.shadowOf(fixture.capabilities).addTransportType(NetworkCapabilities.TRANSPORT_VPN);

        WifiLinkSpeed.Snapshot speed = WifiLinkSpeed.read(fixture.context);

        assertFalse(speed.hasAnySpeed());
        assertEquals(WifiLinkSpeed.UNKNOWN_MBPS, speed.downloadMbps);
        assertEquals(WifiLinkSpeed.UNKNOWN_MBPS, speed.uploadMbps);
    }

    private static final class Fixture {
        final Context context = RuntimeEnvironment.getApplication();
        final ConnectivityManager connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        final NetworkCapabilities capabilities = new NetworkCapabilities();
        final WifiInfo wifiInfo = ShadowWifiInfo.newInstance();

        Fixture() {
            // Use the Android objects and Robolectric's resettable network service. Mixing
            // inline/subclass Mockito makers for transformed framework classes made the old
            // first Context stub depend on which other fixture had registered a maker first.
            ShadowConnectivityManager shadow = Shadows.shadowOf(connectivityManager);
            shadow.setDefaultNetworkActive(true);
            shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
                    NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI,
                    0, true, true));
            Network network = connectivityManager.getActiveNetwork();
            assertNotNull(network);
            Shadows.shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
            Shadows.shadowOf(capabilities).setTransportInfo(wifiInfo);
            shadow.setNetworkCapabilities(network, capabilities);
        }

        void setLinkSpeeds(int fallback, int receive, int transmit) {
            Shadows.shadowOf(wifiInfo).setLinkSpeed(fallback);
            // Directional setters are framework APIs absent from the public SDK stub.
            ReflectionHelpers.callInstanceMethod(wifiInfo, "setRxLinkSpeedMbps",
                    ClassParameter.from(int.class, receive));
            ReflectionHelpers.callInstanceMethod(wifiInfo, "setTxLinkSpeedMbps",
                    ClassParameter.from(int.class, transmit));
        }
    }
}
