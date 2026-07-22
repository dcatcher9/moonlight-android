package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class GameLibraryNavigationTest {
    @Test
    public void streamLibraryActionTargetsCurrentPcLibrary() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent stream = new Intent()
                .putExtra(Game.EXTRA_PC_NAME, "Apollo XR")
                .putExtra(Game.EXTRA_PC_UUID, "pc-uuid");

        Intent library = Game.createLibraryIntent(context, stream);

        assertEquals(AppView.class.getName(), library.getComponent().getClassName());
        assertEquals("Apollo XR", library.getStringExtra(AppView.NAME_EXTRA));
        assertEquals("pc-uuid", library.getStringExtra(AppView.UUID_EXTRA));
        assertFalse(library.getBooleanExtra(AppView.NEW_PAIR_EXTRA, true));
        assertFalse(library.getBooleanExtra(AppView.SHOW_HIDDEN_APPS_EXTRA, true));
        assertTrue((library.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0);
    }
}
