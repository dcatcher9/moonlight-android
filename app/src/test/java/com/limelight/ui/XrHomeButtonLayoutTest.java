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
    public void activeAppActionsAreOrderedLargeAndSingleLine() {
        View card = inflate(R.layout.app_grid_item);
        View primary = card.findViewById(R.id.grid_primary_action);
        ViewGroup actions = card.findViewById(R.id.grid_action_row);
        Button resume = card.findViewById(R.id.grid_resume_button);
        Button quit = card.findViewById(R.id.grid_quit_button);
        Button more = card.findViewById(R.id.grid_more_button);
        float density = card.getResources().getDisplayMetrics().density;

        assertTrue(primary.isClickable());
        assertFalse(card.isClickable());
        assertSame(resume, actions.getChildAt(0));
        assertSame(quit, actions.getChildAt(1));
        assertSame(more, actions.getChildAt(2));
        assertNull(resume.getBackgroundTintList());
        assertNull(quit.getBackgroundTintList());
        assertNull(more.getBackgroundTintList());
        assertBackgroundResource(resume, R.drawable.xr_home_primary_action_background);
        assertBackgroundResource(quit, R.drawable.xr_home_destructive_action_background);
        assertBackgroundResource(more, R.drawable.xr_home_action_background);
        assertEquals(1, resume.getMaxLines());
        assertEquals(1, quit.getMaxLines());
        assertEquals(1, more.getMaxLines());
        assertTrue(more.isSingleLine());
        assertTrue(resume.getLayoutParams().height >= Math.round(56 * density));
        assertTrue(quit.getLayoutParams().height >= Math.round(56 * density));
        assertTrue(more.getLayoutParams().height >= Math.round(64 * density));
        assertTrue(more.getLayoutParams().width >= Math.round(96 * density));
        int occupiedWidth = actionWidthWithMargins(resume)
                + actionWidthWithMargins(quit) + actionWidthWithMargins(more);
        int innerCardWidth = card.getResources().getDimensionPixelSize(
                R.dimen.xr_app_card_width) - card.getPaddingLeft() - card.getPaddingRight();
        assertTrue(innerCardWidth - occupiedWidth >= Math.round(8 * density));
        assertEquals(card.getResources().getString(R.string.xr_home_resume_short),
                resume.getText().toString());
        assertEquals(card.getResources().getString(R.string.xr_home_quit_short),
                quit.getText().toString());
        assertEquals(card.getResources().getString(R.string.xr_home_more),
                more.getText().toString());
    }

    @Test
    public void appGridUsesReadableWidenedCards() {
        GridView grid = (GridView) inflate(R.layout.app_grid_view);
        float density = grid.getResources().getDisplayMetrics().density;
        grid.measure(View.MeasureSpec.makeMeasureSpec(Math.round(1200 * density),
                        View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.round(800 * density),
                        View.MeasureSpec.EXACTLY));

        assertTrue(grid.getColumnWidth() >= Math.round(280 * density));
    }

    private static View inflate(int layout) {
        Context application = ApplicationProvider.getApplicationContext();
        Context themed = new ContextThemeWrapper(application, R.style.AppTheme);
        return LayoutInflater.from(themed).inflate(layout, null, false);
    }

    private static void assertBackgroundResource(View view, int expectedResource) {
        assertEquals(expectedResource, shadowOf(view.getBackground()).getCreatedFromResId());
    }

    private static int actionWidthWithMargins(View view) {
        ViewGroup.MarginLayoutParams params =
                (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        return params.width + params.leftMargin + params.rightMargin;
    }
}
