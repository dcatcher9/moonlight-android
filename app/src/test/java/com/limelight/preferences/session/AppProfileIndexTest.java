package com.limelight.preferences.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.preferences.session.SessionSettingsStore.AppIdentity;
import com.limelight.preferences.session.SessionSettingsStore.PcIdentity;
import com.limelight.preferences.session.SessionSettingsStore.ResumeMetadata;
import com.limelight.preferences.session.SessionSettingsStore.SessionRecord;
import com.limelight.ui.PresentationMode;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33}, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class AppProfileIndexTest {
    private SharedPreferences storage;
    private AppProfileIndex index;
    private final PcIdentity pc = new PcIdentity("pc-1", "192.0.2.1");
    private final PcIdentity otherPc = new PcIdentity("pc-2", "192.0.2.2");

    @Before
    public void setUp() {
        storage = ApplicationProvider.<Context>getApplicationContext().getSharedPreferences(
                "app-profile-index-test", Context.MODE_PRIVATE);
        assertTrue(storage.edit().clear().commit());
        index = new AppProfileIndex(storage);
    }

    @Test
    public void strongerIdentityReplacesItsWeakerAliasAndIsFoundByEither() {
        put(pc, new AppIdentity("7", null, "Game"), "60");
        put(pc, new AppIdentity("7", "app-7", null), "90");

        assertEquals(1, keysFor(pc).size());
        assertTrue(keysFor(pc).get(0).startsWith("settings.uuid.pc-1.app.uuid.app-7."));
        SessionSettingsStore.SavedSettings byId = index.find(pc, new AppIdentity("7", null, null));
        assertEquals("90", byId.sharedOverrides.get("list_fps"));
        // The merged identity keeps the name the weaker profile knew.
        assertEquals("Game", byId.app.getDisplayName());
        assertEquals("app-7", byId.app.getAppUuid());
    }

    @Test
    public void lookupByAWeakerIdentityRefusesToChooseBetweenTwoUuids() {
        put(pc, new AppIdentity("7", "first", null), "60");
        put(pc, new AppIdentity("7", "second", null), "90");

        assertNull(index.find(pc, new AppIdentity("7", null, null)));
        assertEquals("90", index.find(pc, new AppIdentity(null, "second", null))
                .sharedOverrides.get("list_fps"));
    }

    @Test
    public void removeAllDropsOnlyThatPcsProfiles() {
        put(pc, new AppIdentity("7", "app-7", null), "60");
        put(pc, new AppIdentity("8", "app-8", null), "90");
        put(otherPc, new AppIdentity("7", "app-7", null), "120");

        SharedPreferences.Editor editor = storage.edit();
        index.removeAll(editor, pc);
        assertTrue(editor.commit());

        assertTrue(keysFor(pc).isEmpty());
        assertEquals(1, keysFor(otherPc).size());
        assertNull(index.find(pc, new AppIdentity("7", "app-7", null)));
    }

    private void put(PcIdentity target, AppIdentity app, String fps) {
        SessionRecord record = new SessionRecord("local", app,
                Collections.singletonMap("list_fps", fps), Collections.emptyMap(),
                PresentationMode.NORMAL, new ResumeMetadata(false, null, 0L));
        SharedPreferences.Editor editor = storage.edit();
        index.put(editor, target, record);
        assertTrue(editor.commit());
    }

    private List<String> keysFor(PcIdentity target) {
        List<String> keys = new ArrayList<>();
        for (String key : storage.getAll().keySet()) {
            if (key.startsWith("settings." + target.getStorageId() + ".app.")) {
                keys.add(key);
            }
        }
        return keys;
    }
}
