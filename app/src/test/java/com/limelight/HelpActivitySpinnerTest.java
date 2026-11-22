package com.limelight;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.webkit.WebView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;

/** Leaving help while a page loads dismisses its loading spinner. */
@Config(sdk = 33, shadows = {com.limelight.shadows.ShadowMoonBridge.class})
@RunWith(RobolectricTestRunner.class)
public final class HelpActivitySpinnerTest {
    @Test public void destroyWhileLoadingDismissesTheSpinner() {
        ActivityController<HelpActivity> controller = Robolectric.buildActivity(HelpActivity.class,
                new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid/help")))
                .create();
        HelpActivity activity = controller.get();
        WebView webView = ReflectionHelpers.getField(activity, "webView");
        shadowOf(webView).getWebViewClient().onPageStarted(webView, "https://example.invalid/help",
                null);
        shadowOf(Looper.getMainLooper()).idle();
        Dialog spinner = ShadowDialog.getLatestDialog();
        assertNotNull(spinner);
        assertTrue(spinner.isShowing());

        activity.finish();
        controller.destroy();

        assertFalse(spinner.isShowing());
    }
}
