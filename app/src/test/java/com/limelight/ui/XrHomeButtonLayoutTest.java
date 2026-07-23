package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35}, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class XrHomeButtonLayoutTest {
    @Test
    public void homeActionsUseSemanticBackgrounds() {
        View home = inflate(R.layout.activity_pc_view);
        Button settings = home.findViewById(R.id.settingsButton);
        Button addComputer = home.findViewById(R.id.manuallyAddPc);

        assertNull(settings.getBackgroundTintList());
        assertBackgroundResource(settings, R.drawable.xr_home_action_background);
        assertBackgroundResource(home.findViewById(R.id.helpButton),
                R.drawable.xr_home_action_background);
        assertBackgroundResource(addComputer, R.drawable.xr_home_primary_action_background);
    }

    @Test
    public void connectionAndSessionActionsUseSemanticBackgrounds() {
        View connection = inflate(R.layout.activity_add_computer_manually);
        assertBackgroundResource(connection.findViewById(R.id.addPcBackButton),
                R.drawable.xr_home_action_background);
        assertBackgroundResource(connection.findViewById(R.id.hostTextView),
                R.drawable.xr_home_action_background);
        assertBackgroundResource(connection.findViewById(R.id.addPcButton),
                R.drawable.xr_home_primary_action_background);

        View apps = inflate(R.layout.activity_app_view);
        assertBackgroundResource(apps.findViewById(R.id.backButton),
                R.drawable.xr_home_action_background);
        assertBackgroundResource(apps.findViewById(R.id.appSearch),
                R.drawable.xr_home_action_background);
        assertBackgroundResource(apps.findViewById(R.id.settingsButton),
                R.drawable.xr_home_action_background);
        assertBackgroundResource(apps.findViewById(R.id.resumeSessionButton),
                R.drawable.xr_home_primary_action_background);
        assertBackgroundResource(apps.findViewById(R.id.endSessionButton),
                R.drawable.xr_home_destructive_action_background);

        View computer = inflate(R.layout.pc_grid_item);
        assertBackgroundResource(computer.findViewById(R.id.grid_primary_action),
                R.drawable.xr_home_primary_action_background);
        assertBackgroundResource(computer.findViewById(R.id.grid_more_button),
                R.drawable.xr_home_action_background);
    }

    @Test
    public void machineMoreActionIsCompactCenteredPlusButton() {
        View card = inflate(R.layout.pc_grid_item);
        Button primary = card.findViewById(R.id.grid_primary_action);
        Button more = card.findViewById(R.id.grid_more_button);
        float density = card.getResources().getDisplayMetrics().density;
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) more.getLayoutParams();

        assertEquals(1, more.getMaxLines());
        assertTrue(more.isSingleLine());
        assertEquals(Math.round(56 * density), params.width);
        assertEquals(Math.round(56 * density), params.height);
        assertEquals(more.getLayoutParams().height, primary.getLayoutParams().height);
        assertEquals(Gravity.CENTER_VERTICAL, params.gravity);
        assertEquals(card.getResources().getString(R.string.xr_setting_plus),
                more.getText().toString());
        assertTrue(more.getTextSize() >= 24 * card.getResources()
                .getDisplayMetrics().scaledDensity);
    }

    @Test
    public void singleMachineCardHasReadableConnectionSpeedRow() {
        View card = inflate(R.layout.pc_grid_item_hero);
        View speedRow = card.findViewById(R.id.grid_connection_speed);
        View speedValues = card.findViewById(R.id.grid_connection_speed_values);
        TextView download = card.findViewById(R.id.grid_download_speed);
        TextView upload = card.findViewById(R.id.grid_upload_speed);
        float density = card.getResources().getDisplayMetrics().density;

        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, speedRow.getLayoutParams().height);
        assertTrue(speedValues.getMinimumHeight() >= Math.round(30 * density));
        assertTrue(download.getTextSize() >= 17 * card.getResources()
                .getDisplayMetrics().scaledDensity);
        assertTrue(upload.getTextSize() >= 17 * card.getResources()
                .getDisplayMetrics().scaledDensity);
        assertEquals(1, download.getMaxLines());
        assertEquals(1, upload.getMaxLines());
    }

    @Test
    public void machineDetailsFitInsideFixedHeroCard() {
        View card = inflate(R.layout.pc_grid_item_hero);
        TextView address = card.findViewById(R.id.grid_address);
        View speedRow = card.findViewById(R.id.grid_connection_speed);
        TextView download = card.findViewById(R.id.grid_download_speed);
        TextView upload = card.findViewById(R.id.grid_upload_speed);
        TextView display = card.findViewById(R.id.grid_display_fact);
        TextView session = card.findViewById(R.id.grid_session_fact);
        View more = card.findViewById(R.id.grid_more_button);
        float density = card.getResources().getDisplayMetrics().density;

        address.setText("LAN · 192.168.100.200");
        download.setText("↓ 5.8 Gbps");
        upload.setText("↑ 5.8 Gbps");
        display.setText("Virtual display unavailable");
        session.setText("Ready for a new session");

        int width = Math.round(760 * density);
        int height = Math.round(250 * density);
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        card.layout(0, 0, width, height);

        assertTrue(speedRow.getBottom() <= display.getTop());
        assertTrue(display.getBottom() <= session.getTop());
        assertTrue(session.getBottom() <= height - card.getPaddingBottom());
        assertTrue(speedRow.getRight() <= more.getLeft());
        assertTrue(more.getRight() <= width - card.getPaddingRight());
    }

    @Test
    public void activeAppCardUsesCompactTopCornerQuitWithoutBlockingPrimaryAction() {
        View card = inflate(R.layout.app_grid_item);
        View primary = card.findViewById(R.id.grid_primary_action);
        View artworkHost = card.findViewById(R.id.grid_image_layout);
        View artwork = card.findViewById(R.id.grid_image);
        ImageButton quit = card.findViewById(R.id.grid_quit_button);
        Button more = card.findViewById(R.id.grid_more_button);
        float density = card.getResources().getDisplayMetrics().density;

        quit.setVisibility(View.VISIBLE);
        int cardWidth = card.getResources().getDimensionPixelSize(R.dimen.xr_app_card_width);
        int cardHeight = card.getResources().getDimensionPixelSize(R.dimen.xr_app_card_height);
        card.measure(View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(cardHeight, View.MeasureSpec.EXACTLY));
        card.layout(0, 0, cardWidth, cardHeight);

        assertTrue(primary.isClickable());
        assertFalse(card.isClickable());
        assertTrue(card.getClipToOutline());
        assertSame(card, quit.getParent());
        assertSame(card, more.getParent());
        assertNull(quit.getBackgroundTintList());
        assertNull(more.getBackgroundTintList());
        assertBackgroundResource(quit, R.drawable.xr_app_card_close_background);
        assertBackgroundResource(more, R.drawable.xr_home_action_background);
        assertEquals(1, more.getMaxLines());
        assertTrue(more.isSingleLine());
        assertEquals(card.getPaddingLeft(), primary.getLeft());
        assertEquals(card.getPaddingTop(), primary.getTop());
        assertEquals(cardWidth - card.getPaddingRight(), primary.getRight());
        assertEquals(cardHeight - card.getPaddingBottom(), primary.getBottom());
        assertEquals(0, artworkHost.getLeft());
        assertEquals(0, artworkHost.getTop());
        assertEquals(primary.getWidth(), artworkHost.getWidth());
        assertEquals(primary.getHeight(), artworkHost.getHeight());
        assertEquals(artworkHost.getWidth(), artwork.getWidth());
        assertEquals(artworkHost.getHeight(), artwork.getHeight());
        assertEquals(Math.round(56 * density), quit.getWidth());
        assertEquals(quit.getWidth(), quit.getHeight());
        assertEquals(cardWidth - card.getPaddingRight() - Math.round(12 * density),
                quit.getRight());
        assertEquals(card.getPaddingTop() + Math.round(12 * density), quit.getTop());
        assertTrue(quit.getDrawable() != null);
        assertEquals(Math.round(24 * density), quit.getDrawable().getIntrinsicWidth());
        assertEquals(Math.round(56 * density), more.getWidth());
        assertEquals(more.getWidth(), more.getHeight());
        assertEquals(cardWidth - card.getPaddingRight() - Math.round(12 * density),
                more.getRight());
        assertEquals(cardHeight - card.getPaddingBottom() - Math.round(12 * density),
                more.getBottom());
        assertTrue(quit.getRight() <= primary.getRight());
        assertTrue(more.getRight() <= primary.getRight());
        assertTrue(quit.getBottom() <= primary.getBottom());
        assertTrue(more.getBottom() <= primary.getBottom());
        assertTrue(quit.getBottom() <= more.getTop());
        assertEquals(card.getResources().getString(R.string.applist_menu_quit),
                quit.getContentDescription().toString());
        assertEquals(card.getResources().getString(R.string.xr_home_more_symbol),
                more.getText().toString());
        assertEquals(card.getResources().getString(R.string.xr_home_more),
                more.getContentDescription().toString());
    }

    @Test
    public void appGridUsesCompactCardsThatFitTheRunningSessionViewport() {
        GridView grid = (GridView) inflate(R.layout.app_grid_view);
        View card = inflate(R.layout.app_grid_item);
        float density = grid.getResources().getDisplayMetrics().density;
        // The live 1024 x 640 dp Galaxy XR AppView leaves 346 dp for the nested GridView when the
        // current-session banner is visible. Robolectric's platform font metrics under-measure that
        // weighted container, so exercise the verified device viewport directly.
        grid.measure(View.MeasureSpec.makeMeasureSpec(Math.round(964 * density),
                        View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.round(346 * density),
                        View.MeasureSpec.EXACTLY));

        int cardWidth = card.getResources().getDimensionPixelSize(R.dimen.xr_app_card_width);
        int cardHeight = card.getResources().getDimensionPixelSize(R.dimen.xr_app_card_height);

        assertEquals(Math.round(240 * density), cardWidth);
        assertTrue(grid.getColumnWidth() >= cardWidth);
        assertEquals(Math.round(320 * density), cardHeight);
        int requiredRowHeight = cardHeight + grid.getPaddingTop() + grid.getPaddingBottom();
        assertTrue("required row=" + requiredRowHeight
                        + ", measured grid=" + grid.getMeasuredHeight(),
                requiredRowHeight <= grid.getMeasuredHeight());
    }

    private static View inflate(int layout) {
        Context application = ApplicationProvider.getApplicationContext();
        Context themed = new ContextThemeWrapper(application, R.style.AppTheme);
        return LayoutInflater.from(themed).inflate(layout, null, false);
    }

    private static void assertBackgroundResource(View view, int expectedResource) {
        assertEquals(expectedResource, shadowOf(view.getBackground()).getCreatedFromResId());
    }

}
