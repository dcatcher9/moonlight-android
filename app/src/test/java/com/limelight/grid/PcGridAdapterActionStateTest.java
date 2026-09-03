package com.limelight.grid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.PcView;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.WifiLinkSpeed;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicInteger;

import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35}, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class PcGridAdapterActionStateTest {
    @Test
    public void singleMachineUsesHeroThenFallsBackToCompactGridForMultipleMachines() {
        TestFixture fixture = new TestFixture(2594, 1441);
        ComputerDetails primary = computer(ComputerDetails.State.ONLINE,
                PairingManager.PairState.PAIRED);
        primary.activeAddress = new ComputerDetails.AddressTuple("192.168.1.20", 47989);
        primary.vDisplaySupported = true;
        primary.vDisplayDriverReady = true;
        fixture.adapter.addComputer(new PcView.ComputerObject(primary));

        FrameLayout parent = new FrameLayout(fixture.context);
        View hero = fixture.adapter.getView(0, null, parent);

        assertTrue(fixture.adapter.isSingleMachinePresentation());
        assertEquals(R.id.pc_card_hero, hero.getId());
        assertFalse(hero.isFocusableInTouchMode());
        assertEquals(View.GONE,
                hero.findViewById(R.id.grid_primary_action).getVisibility());
        assertEquals(fixture.context.getString(R.string.xr_home_open_library_hint),
                ((TextView) hero.findViewById(R.id.grid_hint)).getText().toString());
        assertEquals(fixture.context.getString(R.string.xr_home_lan_address,
                        primary.activeAddress.address),
                ((TextView) hero.findViewById(R.id.grid_address)).getText().toString());
        View connectionSpeed = hero.findViewById(R.id.grid_connection_speed);
        assertEquals(fixture.context.getString(R.string.xr_home_download_link_speed,
                        "2.6 Gbps"), ((TextView) hero.findViewById(
                                R.id.grid_download_speed)).getText().toString());
        assertEquals(fixture.context.getString(R.string.xr_home_upload_link_speed,
                        "1.4 Gbps"), ((TextView) hero.findViewById(
                                R.id.grid_upload_speed)).getText().toString());
        assertEquals(fixture.context.getString(R.string.xr_home_wifi_link_speed_description,
                        "2.6 Gbps", "1.4 Gbps"),
                connectionSpeed.getContentDescription().toString());
        assertEquals(View.VISIBLE, connectionSpeed.getVisibility());
        assertEquals(fixture.context.getString(R.string.xr_home_virtual_display_ready),
                ((TextView) hero.findViewById(R.id.grid_display_fact)).getText().toString());
        assertEquals(fixture.context.getString(R.string.xr_home_session_ready),
                ((TextView) hero.findViewById(R.id.grid_session_fact)).getText().toString());
        assertTrue(hero.performClick());
        assertEquals(1, fixture.primaryClicks.get());

        ComputerDetails secondary = computer(ComputerDetails.State.ONLINE,
                PairingManager.PairState.PAIRED);
        secondary.uuid = "second-pc";
        secondary.name = "Office PC";
        fixture.adapter.addComputer(new PcView.ComputerObject(secondary));
        View compact = fixture.adapter.getView(0, hero, parent);

        assertFalse(fixture.adapter.isSingleMachinePresentation());
        assertEquals(R.id.pc_card_standard, compact.getId());
    }

    @Test
    public void pairedOnlineCardOpensLibraryAndUsesCompactMoreAction() {
        TestFixture fixture = new TestFixture();
        ComputerDetails computer = computer(ComputerDetails.State.ONLINE,
                PairingManager.PairState.PAIRED);

        fixture.bind(computer);

        assertEquals(View.GONE, fixture.primary.getVisibility());
        assertFalse(fixture.primary.isEnabled());
        assertFalse(fixture.primary.isClickable());
        assertFalse(fixture.primary.hasOnClickListeners());
        assertEquals(fixture.context.getString(R.string.xr_setting_plus),
                fixture.more.getText().toString());
        assertEquals(fixture.context.getString(R.string.xr_home_more) + ": " + computer.name,
                fixture.more.getContentDescription().toString());

        assertTrue(fixture.card.performClick());
        assertTrue(fixture.more.performClick());
        assertEquals(1, fixture.primaryClicks.get());
        assertEquals(1, fixture.moreClicks.get());
    }

    @Test
    public void standardHostWithoutVirtualDisplayExtensionUsesNeutralFact() {
        TestFixture fixture = new TestFixture();
        ComputerDetails computer = computer(ComputerDetails.State.ONLINE,
                PairingManager.PairState.PAIRED);
        fixture.adapter.addComputer(new PcView.ComputerObject(computer));
        FrameLayout parent = new FrameLayout(fixture.context);

        View hero = fixture.adapter.getView(0, null, parent);

        TextView displayFact = hero.findViewById(R.id.grid_display_fact);
        assertEquals(fixture.context.getString(
                        R.string.xr_home_virtual_display_not_advertised),
                displayFact.getText().toString());
        assertEquals(fixture.context.getColor(R.color.xr_text_secondary),
                displayFact.getCurrentTextColor());

        computer.hostSessionIdSupported = true;
        hero = fixture.adapter.getView(0, hero, parent);
        displayFact = hero.findViewById(R.id.grid_display_fact);
        assertEquals(fixture.context.getString(R.string.xr_home_virtual_display_unavailable),
                displayFact.getText().toString());
        assertEquals(fixture.context.getColor(R.color.xr_danger),
                displayFact.getCurrentTextColor());
    }

    @Test
    public void firstTouchGestureOnPairedCardOpensLibrary() {
        TestFixture fixture = new TestFixture();
        fixture.bind(computer(ComputerDetails.State.ONLINE,
                PairingManager.PairState.PAIRED));
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        root.addView(fixture.card);
        activity.setContentView(root);
        int width = Math.round(390 * fixture.context.getResources()
                .getDisplayMetrics().density);
        int height = Math.round(148 * fixture.context.getResources()
                .getDisplayMetrics().density);
        fixture.card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        fixture.card.layout(0, 0, width, height);

        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime,
                MotionEvent.ACTION_DOWN, 32f, 32f, 0);
        MotionEvent up = MotionEvent.obtain(downTime, downTime + 16L,
                MotionEvent.ACTION_UP, 32f, 32f, 0);
        try {
            assertTrue(fixture.card.dispatchTouchEvent(down));
            assertTrue(fixture.card.dispatchTouchEvent(up));
            shadowOf(Looper.getMainLooper()).idle();
        }
        finally {
            down.recycle();
            up.recycle();
        }

        assertEquals(1, fixture.primaryClicks.get());
    }

    @Test
    public void recycledCardShowsOnlyRequiredPairOrWakeActions() {
        TestFixture fixture = new TestFixture();

        fixture.bind(computer(ComputerDetails.State.OFFLINE,
                PairingManager.PairState.PAIRED));
        assertVisibleAction(fixture.primary, R.string.xr_home_wake);
        assertTrue(fixture.primary.performClick());
        assertEquals(1, fixture.primaryClicks.get());

        fixture.bind(computer(ComputerDetails.State.ONLINE,
                PairingManager.PairState.PAIRED));
        assertEquals(View.GONE, fixture.primary.getVisibility());
        assertFalse(fixture.primary.isEnabled());
        assertFalse(fixture.primary.isClickable());
        assertFalse(fixture.primary.hasOnClickListeners());
        assertFalse(fixture.primary.performClick());
        assertEquals(1, fixture.primaryClicks.get());

        fixture.bind(computer(ComputerDetails.State.ONLINE,
                PairingManager.PairState.NOT_PAIRED));
        assertVisibleAction(fixture.primary, R.string.xr_home_pair);
        assertTrue(fixture.primary.performClick());
        assertEquals(2, fixture.primaryClicks.get());

        fixture.bind(computer(ComputerDetails.State.UNKNOWN,
                PairingManager.PairState.NOT_PAIRED));
        assertEquals(View.GONE, fixture.primary.getVisibility());
        assertFalse(fixture.primary.isEnabled());
        assertFalse(fixture.primary.isClickable());
        assertFalse(fixture.primary.hasOnClickListeners());
        assertEquals(View.VISIBLE, fixture.spinner.getVisibility());
    }

    @Test
    public void unavailableWifiSpeedDoesNotLeaveStaleHeroText() {
        TestFixture fixture = new TestFixture(866, 433);
        ComputerDetails computer = computer(ComputerDetails.State.ONLINE,
                PairingManager.PairState.PAIRED);
        fixture.adapter.addComputer(new PcView.ComputerObject(computer));
        FrameLayout parent = new FrameLayout(fixture.context);
        View hero = fixture.adapter.getView(0, null, parent);
        View speed = hero.findViewById(R.id.grid_connection_speed);
        TextView download = hero.findViewById(R.id.grid_download_speed);
        TextView upload = hero.findViewById(R.id.grid_upload_speed);

        assertEquals(View.VISIBLE, speed.getVisibility());
        fixture.downloadMbps = WifiLinkSpeed.UNKNOWN_MBPS;
        fixture.uploadMbps = WifiLinkSpeed.UNKNOWN_MBPS;
        fixture.adapter.getView(0, hero, parent);

        assertEquals(View.GONE, speed.getVisibility());
        assertEquals("", download.getText().toString());
        assertEquals("", upload.getText().toString());
    }

    @Test
    public void oneAvailableWifiDirectionKeepsTheOtherExplicitlyUnknown() {
        TestFixture fixture = new TestFixture(1200, WifiLinkSpeed.UNKNOWN_MBPS);
        ComputerDetails computer = computer(ComputerDetails.State.ONLINE,
                PairingManager.PairState.PAIRED);
        fixture.adapter.addComputer(new PcView.ComputerObject(computer));
        View hero = fixture.adapter.getView(0, null, new FrameLayout(fixture.context));

        assertEquals(View.VISIBLE,
                hero.findViewById(R.id.grid_connection_speed).getVisibility());
        assertEquals("↓ 1.2 Gbps", ((TextView) hero.findViewById(
                R.id.grid_download_speed)).getText().toString());
        assertEquals("↑ —", ((TextView) hero.findViewById(
                R.id.grid_upload_speed)).getText().toString());
    }

    @Test
    public void offlineMachineDoesNotPresentHeadsetLinkAsHostSpeed() {
        TestFixture fixture = new TestFixture(2594, 2594);
        ComputerDetails computer = computer(ComputerDetails.State.OFFLINE,
                PairingManager.PairState.PAIRED);
        fixture.adapter.addComputer(new PcView.ComputerObject(computer));
        View hero = fixture.adapter.getView(0, null, new FrameLayout(fixture.context));

        assertEquals(View.GONE,
                hero.findViewById(R.id.grid_connection_speed).getVisibility());
    }

    @Test
    public void managementUrlBracketsIpv6Literal() {
        ComputerDetails computer = computer(ComputerDetails.State.ONLINE,
                PairingManager.PairState.PAIRED);
        computer.activeAddress = new ComputerDetails.AddressTuple("2001:db8::20", 47989);

        assertEquals("https://[2001:db8::20]:47990",
                new PcView.ComputerObject(computer).guessManagementUrl());
    }

    private static void assertVisibleAction(Button action, int expectedText) {
        assertEquals(View.VISIBLE, action.getVisibility());
        assertTrue(action.isEnabled());
        assertTrue(action.isClickable());
        assertTrue(action.hasOnClickListeners());
        assertEquals(action.getResources().getString(expectedText), action.getText().toString());
    }

    private static ComputerDetails computer(ComputerDetails.State state,
                                            PairingManager.PairState pairState) {
        ComputerDetails details = new ComputerDetails();
        details.uuid = "test-pc";
        details.name = "Apollo XR";
        details.state = state;
        details.pairState = pairState;
        return details;
    }

    private static final class TestFixture {
        final Context context;
        final PcGridAdapter adapter;
        final View card;
        final Button primary;
        final Button more;
        final ProgressBar spinner;
        final AtomicInteger primaryClicks = new AtomicInteger();
        final AtomicInteger moreClicks = new AtomicInteger();
        int downloadMbps;
        int uploadMbps;

        TestFixture() {
            this(WifiLinkSpeed.UNKNOWN_MBPS, WifiLinkSpeed.UNKNOWN_MBPS);
        }

        TestFixture(int downloadMbps, int uploadMbps) {
            Context application = ApplicationProvider.getApplicationContext();
            context = new ContextThemeWrapper(application, R.style.AppTheme);
            this.downloadMbps = downloadMbps;
            this.uploadMbps = uploadMbps;
            adapter = new PcGridAdapter(context,
                    PreferenceConfiguration.readPreferences(context),
                    () -> new WifiLinkSpeed.Snapshot(this.downloadMbps, this.uploadMbps));
            adapter.setActionListener(new PcGridAdapter.ActionListener() {
                @Override
                public void onPrimaryAction(PcView.ComputerObject computer, View anchor) {
                    primaryClicks.incrementAndGet();
                }

                @Override
                public void onMoreActions(PcView.ComputerObject computer, View anchor) {
                    moreClicks.incrementAndGet();
                }
            });
            card = LayoutInflater.from(context).inflate(R.layout.pc_grid_item, null, false);
            primary = card.findViewById(R.id.grid_primary_action);
            more = card.findViewById(R.id.grid_more_button);
            spinner = card.findViewById(R.id.grid_spinner);
        }

        void bind(ComputerDetails details) {
            adapter.populateView(card,
                    card.findViewById(R.id.grid_image),
                    null,
                    spinner,
                    card.findViewById(R.id.grid_text),
                    card.findViewById(R.id.grid_overlay),
                    new PcView.ComputerObject(details));
        }
    }
}
