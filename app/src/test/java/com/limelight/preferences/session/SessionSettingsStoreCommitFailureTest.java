package com.limelight.preferences.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.preferences.session.SessionSettingsStore.AppIdentity;
import com.limelight.preferences.session.SessionSettingsStore.PcIdentity;
import com.limelight.ui.PresentationMode;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * A SharedPreferences commit that fails (for example, a full disk) must be reported to the
 * caller and must leave the stored record unchanged.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33}, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class SessionSettingsStoreCommitFailureTest {
    private static final String FPS = "list_fps";

    private SharedPreferences storage;
    private FailingCommitPreferences failing;
    private SessionSettingsStore workingStore;
    private SessionSettingsStore failingStore;
    private PcIdentity pc;
    private AppIdentity app;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        storage = context.getSharedPreferences(
                SessionSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE);
        assertTrue(storage.edit().clear().commit());
        failing = new FailingCommitPreferences(storage);
        workingStore = new SessionSettingsStore(storage);
        failingStore = new SessionSettingsStore(failing);
        pc = new PcIdentity("pc-1", "192.0.2.1");
        app = new AppIdentity("7", "app-7", "Game");
    }

    @Test
    public void failedStartLeavesNoRecord() {
        assertFalse(failingStore.startNewSession(pc, app, "host-1", 1L));
        assertNull(workingStore.getCurrentSession(pc));
        assertTrue(storage.getAll().isEmpty());
    }

    @Test
    public void failedEditorCommitReturnsFalseAndKeepsTheRecord() {
        assertTrue(workingStore.startNewSession(pc, app, "host-1", 1L));
        String before = storage.getString("session." + pc.getStorageId(), null);

        assertFalse(failingStore.edit(pc, app)
                .setModeValue(PresentationMode.NORMAL, FPS, "120", "60")
                .setLastSuccessfulMode(PresentationMode.MOVIE_3D)
                .commit());

        assertEquals(before, storage.getString("session." + pc.getStorageId(), null));
        assertEquals(PresentationMode.NORMAL,
                workingStore.getCurrentSession(pc).getLastSuccessfulMode());
    }

    @Test
    public void failedResumeConfirmationReturnsNoRecord() {
        assertTrue(workingStore.startNewSession(pc, app, "host-1", 1L));

        assertNull(failingStore.confirmHostResume(pc, app, "host-1", 2L));

        assertFalse(workingStore.getCurrentSession(pc).getResumeMetadata()
                .isHostConfirmedResume());
    }

    @Test
    public void failedClearsAndRemovalsReturnFalseAndKeepTheRecord() {
        assertTrue(workingStore.startNewSession(pc, app, "host-1", 1L));
        assertTrue(workingStore.edit(pc, app)
                .setModeValue(PresentationMode.NORMAL, FPS, "120", "60").commit());

        assertFalse(failingStore.clearCurrentSession(pc));
        assertFalse(failingStore.removeAllForPc(pc));
        assertFalse(failingStore.clearValueOverridesForAllSessions(
                Collections.singletonList(FPS)));

        assertEquals("120", workingStore.getCurrentSession(pc)
                .getModeOverrides(PresentationMode.NORMAL).get(FPS));
    }

    /** Reads through to real storage; every commit fails and every apply is dropped. */
    private static final class FailingCommitPreferences implements SharedPreferences {
        private final SharedPreferences delegate;

        FailingCommitPreferences(SharedPreferences delegate) {
            this.delegate = delegate;
        }

        @Override
        public Map<String, ?> getAll() {
            return delegate.getAll();
        }

        @Override
        public String getString(String key, String defValue) {
            return delegate.getString(key, defValue);
        }

        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            return delegate.getStringSet(key, defValues);
        }

        @Override
        public int getInt(String key, int defValue) {
            return delegate.getInt(key, defValue);
        }

        @Override
        public long getLong(String key, long defValue) {
            return delegate.getLong(key, defValue);
        }

        @Override
        public float getFloat(String key, float defValue) {
            return delegate.getFloat(key, defValue);
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            return delegate.getBoolean(key, defValue);
        }

        @Override
        public boolean contains(String key) {
            return delegate.contains(key);
        }

        @Override
        public Editor edit() {
            return new FailingEditor();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
        }
    }

    private static final class FailingEditor implements SharedPreferences.Editor {
        @Override
        public SharedPreferences.Editor putString(String key, String value) {
            return this;
        }

        @Override
        public SharedPreferences.Editor putStringSet(String key, Set<String> values) {
            return this;
        }

        @Override
        public SharedPreferences.Editor putInt(String key, int value) {
            return this;
        }

        @Override
        public SharedPreferences.Editor putLong(String key, long value) {
            return this;
        }

        @Override
        public SharedPreferences.Editor putFloat(String key, float value) {
            return this;
        }

        @Override
        public SharedPreferences.Editor putBoolean(String key, boolean value) {
            return this;
        }

        @Override
        public SharedPreferences.Editor remove(String key) {
            return this;
        }

        @Override
        public SharedPreferences.Editor clear() {
            return this;
        }

        @Override
        public boolean commit() {
            return false;
        }

        @Override
        public void apply() {
        }
    }
}
