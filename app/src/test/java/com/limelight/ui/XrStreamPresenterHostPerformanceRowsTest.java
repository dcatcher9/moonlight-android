package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.sbs.HostSbsTelemetrySnapshot;
import com.limelight.sbs.HostSbsTelemetryTracker;
import com.limelight.sbs.SbsDepthTelemetrySnapshot;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public class XrStreamPresenterHostPerformanceRowsTest {
    private ActivityController<Activity> controller;
    private XrStreamPresenter presenter;
    private TableLayout table;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.create();
        presenter = new XrStreamPresenter(activity,
                PreferenceConfiguration.readPreferences(activity), surface -> { }, visible -> { });
        table = new TableLayout(activity);
        ReflectionHelpers.setField(presenter, "statsTable", table);
    }

    @After
    public void tearDown() {
        presenter.onDestroy();
        controller.destroy();
    }

    @Test
    public void diagnosticsOffShowsExplanationAndHealthyZeroFaultsWithoutFakeTiming() {
        HostSbsTelemetryTracker tracker = new HostSbsTelemetryTracker();
        tracker.activateRequest(1);
        tracker.accept(sample(1, 1000, false), 100);
        Map<String, String> rows = render(tracker.sampleAtStatsTick(200));
        assertEquals("Enable host diagnostics for performance", rows.get("Host performance"));
        assertEquals("empty 0 | collapsed 0", rows.get("Depth faults"));
        assertEquals("1 | received 0.1 s ago", rows.get("Host publication"));
        assertFalse(rows.containsKey("Conversion CPU wall"));
        assertFalse(rows.containsKey("Infer / reuse / invalid FPS"));
    }

    @Test
    public void actualRowsDistinguishMissingTimingFromMeasuredZeroAndShowIndependentRates() {
        HostSbsTelemetryTracker tracker = new HostSbsTelemetryTracker();
        tracker.activateRequest(1);
        tracker.accept(sample(1, 1000, true), 100);
        Map<String, String> baseline = render(tracker.sampleAtStatsTick(100));
        assertEquals("Waiting for two fresh samples", baseline.get("Infer / reuse / invalid FPS"));
        tracker.accept(sample(2, 2000, true), 200);
        Map<String, String> rows = render(tracker.sampleAtStatsTick(300));
        assertEquals("10.0 / 30.0 / 20.0 | 1.00 s", rows.get("Infer / reuse / invalid FPS"));
        assertEquals("75.0% of infer + reuse decisions", rows.get("Near-identical reuse"));
        assertEquals("50.0 / 5.0 / 1.0 | 1.00 s", rows.get("Warp / packed repeat / flat FPS"));
        assertEquals("0.00 ms | 2 samples", rows.get("Conversion CPU wall"));
        assertEquals("Not sampled", rows.get("Encode / retrieve CPU wall"));
        assertEquals("Since mode change; CPU/GPU stages overlap", rows.get("Stage averages"));
        assertTrue(rows.containsKey("Model / reuse branch GPU"));
        assertTrue(rows.containsKey("Postprocess GPU"));
        assertTrue(rows.containsKey("Output GPU"));
    }

    private Map<String, String> render(SbsDepthTelemetrySnapshot sample) {
        ReflectionHelpers.callInstanceMethod(presenter, "beginStatsRows");
        ReflectionHelpers.callInstanceMethod(presenter, "addHostDepthTelemetryRows",
                ClassParameter.from(SbsDepthTelemetrySnapshot.class, sample));
        ReflectionHelpers.callInstanceMethod(presenter, "finishStatsRows");
        Map<String, String> rows = new LinkedHashMap<>();
        for (int i = 0; i < table.getChildCount(); i++) {
            TableRow row = (TableRow)table.getChildAt(i);
            if (row.getVisibility() == android.view.View.VISIBLE && row.getChildCount() == 2) {
                rows.put(((TextView)row.getChildAt(0)).getText().toString(),
                        ((TextView)row.getChildAt(1)).getText().toString());
            }
        }
        return rows;
    }

    private static HostSbsTelemetrySnapshot sample(int sequence, int hostMs, boolean performance) {
        ByteBuffer body = ByteBuffer.allocate(HostSbsTelemetrySnapshot.WIRE_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);
        body.put(0, (byte)HostSbsTelemetrySnapshot.VERSION_2);
        body.putInt(4, 1);
        body.putInt(8, sequence);
        int valid = SbsDepthTelemetrySnapshot.VALID_FAULTS;
        if (performance) {
            valid |= HostSbsTelemetrySnapshot.VALID_OUTCOMES
                    | HostSbsTelemetrySnapshot.VALID_OUTPUT
                    | HostSbsTelemetrySnapshot.VALID_STAGE_BASE;
            body.putInt(88, 1);
            body.putInt(92, sequence);
            body.putInt(96, hostMs);
            body.putLong(104, sequence * 10L);
            body.putLong(112, sequence * 30L);
            body.putLong(120, sequence * 20L);
            body.putInt(128, hostMs);
            body.putInt(132, sequence * 50);
            body.putInt(136, sequence * 5);
            body.putInt(140, sequence);
            body.putFloat(144, 0.0f);
            body.putInt(148, 2);
            body.putInt(152, hostMs);
        }
        body.putInt(12, valid);
        return HostSbsTelemetrySnapshot.parse(body.array());
    }
}
