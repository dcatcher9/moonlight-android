package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.limelight.sbs.SbsDepthTelemetrySnapshot;

import org.junit.Test;

public final class XrStreamPresenterHostTelemetryFormatTest {
    @Test
    public void formatsExplicitUnavailableStates() {
        assertEquals("Unsupported by this host",
                XrStreamPresenter.formatHostSbsTelemetryStatus(
                        SbsDepthTelemetrySnapshot.unavailable(
                                SbsDepthTelemetrySnapshot.Availability.UNSUPPORTED)));
        assertEquals("Host telemetry stale",
                XrStreamPresenter.formatHostSbsTelemetryStatus(
                        SbsDepthTelemetrySnapshot.unavailable(
                                SbsDepthTelemetrySnapshot.Availability.STALE)));
    }

    @Test
    public void formatsReportedHostFieldsWithoutClaimingCurrentGeometryProof() {
        SbsDepthTelemetrySnapshot telemetry = SbsDepthTelemetrySnapshot.available(
                SbsDepthTelemetrySnapshot.VALID_ALL,
                SbsDepthTelemetrySnapshot.RUNTIME_INITIALIZED
                        | SbsDepthTelemetrySnapshot.RUNTIME_DEPTH_READY,
                1036, 584, 2, 1.2f, 2.0f, 1.7f,
                0.1f, 0.2f, -2.0f, 0.5f, 1.0f, 0.4f,
                8, 1, 0, 0, 0, 10);
        assertEquals("Live | 1036x584 | host depth telemetry",
                XrStreamPresenter.formatHostSbsTelemetryStatus(telemetry));
        assertEquals("pipeline ready | raw valid 100.0% | reported pop 1.700 | cut range 0.4000",
                XrStreamPresenter.formatHostV2Field(telemetry));

        SbsDepthTelemetrySnapshot initializedOnly = SbsDepthTelemetrySnapshot.available(
                SbsDepthTelemetrySnapshot.VALID_DEPTH_FRACTION,
                SbsDepthTelemetrySnapshot.RUNTIME_INITIALIZED,
                1036, 584, 0, Float.NaN, Float.NaN, Float.NaN,
                Float.NaN, Float.NaN, Float.NaN, Float.NaN, 1.0f, Float.NaN,
                8, 1, 0, 0, 0, 10);
        assertEquals("state initialized | raw valid 100.0% | reported pop n/a",
                XrStreamPresenter.formatHostV2Field(initializedOnly));
        String reported = XrStreamPresenter.formatHostV2Field(telemetry);
        for (String unreported : new String[] {
                "fixed", "zero", "amplitude", "adaptive", "linear", "P05", "P95"
        }) {
            assertFalse("wire v2 does not authenticate " + unreported,
                    reported.contains(unreported));
        }
    }

    @Test
    public void hostPopUsesItsReportedConfigurationRatherThanTheClientDefault() {
        SbsDepthTelemetrySnapshot configured = SbsDepthTelemetrySnapshot.available(
                SbsDepthTelemetrySnapshot.VALID_EFFECTIVE,
                SbsDepthTelemetrySnapshot.RUNTIME_INITIALIZED,
                0, 0, 0, 2.25f, 2.25f, 2.25f,
                Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN,
                0, 0, 0, 0, 0, 0);
        assertEquals("state initialized | raw valid n/a | reported pop 2.250",
                XrStreamPresenter.formatHostV2Field(configured));
    }

    @Test
    public void reportsGeometryAndAppearanceCutArmingSeparately() {
        SbsDepthTelemetrySnapshot bothArmed = SbsDepthTelemetrySnapshot.available(
                SbsDepthTelemetrySnapshot.VALID_SCENE
                        | SbsDepthTelemetrySnapshot.VALID_CUTS,
                SbsDepthTelemetrySnapshot.RUNTIME_GEOMETRY_ARMED
                        | SbsDepthTelemetrySnapshot.RUNTIME_APPEARANCE_ARMED,
                0, 0, 0, 0.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                12, 4, 2, 0, 0, 0);
        assertEquals("4 total | scene age 12 frames | geometry armed | appearance armed",
                XrStreamPresenter.formatHostSceneCutStatus(
                        bothArmed.hardCutCount, (int)bothArmed.sceneAge,
                        bothArmed.isGeometryArmed(), bothArmed.isAppearanceArmed()));

        SbsDepthTelemetrySnapshot appearanceOnly = SbsDepthTelemetrySnapshot.available(
                SbsDepthTelemetrySnapshot.VALID_SCENE
                        | SbsDepthTelemetrySnapshot.VALID_CUTS,
                SbsDepthTelemetrySnapshot.RUNTIME_APPEARANCE_ARMED,
                0, 0, 0, 0.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                3, 1, 0, 0, 0, 0);
        assertEquals("1 total | scene age 3 frames | geometry disarmed | appearance armed",
                XrStreamPresenter.formatHostSceneCutStatus(
                        appearanceOnly.hardCutCount, (int)appearanceOnly.sceneAge,
                        appearanceOnly.isGeometryArmed(), appearanceOnly.isAppearanceArmed()));
    }
}
