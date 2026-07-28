package com.limelight.grid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35}, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class AppGridAdapterActionStateTest {
    @Test
    public void runningCardKeepsArtworkClearAndUsesExplicitStatus() {
        View card = inflateCard();
        ImageView overlay = card.findViewById(R.id.grid_overlay);
        RelativeLayout mask = card.findViewById(R.id.grid_mask);
        TextView status = card.findViewById(R.id.grid_status);

        AppGridAdapter.bindCardSessionState(overlay, mask, status, true, true);

        assertEquals(View.GONE, overlay.getVisibility());
        assertEquals(View.VISIBLE, status.getVisibility());
        assertEquals(card.getResources().getString(R.string.xr_home_status_running),
                status.getText().toString());
        assertEquals(ContextCompat.getColor(card.getContext(), R.color.xr_scrim_clear),
                ((ColorDrawable) mask.getBackground()).getColor());
    }

    @Test
    public void onlineRunningCardShowsAndDispatchesCornerQuit() {
        View card = inflateCard();
        ImageView quit = card.findViewById(R.id.grid_quit_button);
        AtomicInteger quitCount = new AtomicInteger();

        AppGridAdapter.bindSessionQuitButton(card, true, true, quitCount::incrementAndGet);

        assertEquals(View.VISIBLE, quit.getVisibility());
        assertTrue(quit.isEnabled());
        assertTrue(quit.performClick());
        assertEquals(1, quitCount.get());
    }

    @Test
    public void recycledInactiveCardClearsSessionActionsAndListeners() {
        View card = inflateCard();
        ImageView quit = card.findViewById(R.id.grid_quit_button);
        View more = card.findViewById(R.id.grid_more_button);
        AtomicInteger callbackCount = new AtomicInteger();

        AppGridAdapter.bindSessionQuitButton(card, true, true,
                callbackCount::incrementAndGet);
        AppGridAdapter.bindSessionQuitButton(card, false, true,
                callbackCount::incrementAndGet);

        assertEquals(View.GONE, quit.getVisibility());
        assertFalse(quit.isEnabled());
        assertFalse(quit.isClickable());
        assertFalse(quit.hasOnClickListeners());
        assertEquals(View.VISIBLE, more.getVisibility());
        assertTrue(more.isEnabled());
        assertEquals(0, callbackCount.get());
    }

    @Test
    public void offlineRunningCardKeepsCornerQuitVisibleButDisabled() {
        View card = inflateCard();
        ImageView quit = card.findViewById(R.id.grid_quit_button);

        AppGridAdapter.bindSessionQuitButton(card, true, false, () -> { });

        assertEquals(View.VISIBLE, quit.getVisibility());
        assertFalse(quit.isEnabled());
        assertFalse(quit.isClickable());
        assertFalse(quit.hasOnClickListeners());
    }

    private static View inflateCard() {
        Context application = ApplicationProvider.getApplicationContext();
        Context themed = new ContextThemeWrapper(application, R.style.AppTheme);
        return LayoutInflater.from(themed).inflate(R.layout.app_grid_item, null, false);
    }
}
