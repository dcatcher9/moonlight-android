package com.limelight.preferences.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Two store instances over the same preferences commit on two threads. The first commit is held
 * at its SharedPreferences write, after it has read the record inside the store's write lock. The
 * second must block entering its own commit and then build on the first result, so neither change
 * is lost. Without the lock the second commit runs to completion on the stale record and the first
 * write then replaces it, which fails both checks.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33}, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class SessionSettingsStoreConcurrencyTest {
    private static final String FPS = "list_fps";
    private static final long TIMEOUT_SECONDS = 10;

    private SharedPreferences storage;
    private PcIdentity pc;
    private AppIdentity app;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        storage = context.getSharedPreferences(
                SessionSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE);
        assertTrue(storage.edit().clear().commit());
        pc = new PcIdentity("pc-1", "192.0.2.1");
        app = new AppIdentity("7", "app-7", "Game");
    }

    @Test
    public void concurrentCommitsSerializeSoNeitherChangeIsLost() throws Exception {
        assertTrue(new SessionSettingsStore(storage).startNewSession(pc, app, "host-1", 1L));
        CountDownLatch firstWriteEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        HoldingPreferences holding =
                new HoldingPreferences(storage, firstWriteEntered, releaseFirstWrite);

        // Both editors capture the session id here, before either thread starts.
        SessionSettingsStore.Editor first = new SessionSettingsStore(holding).edit(pc, app)
                .setModeValue(PresentationMode.NORMAL, FPS, "120", "60");
        SessionSettingsStore.Editor second = new SessionSettingsStore(storage).edit(pc, app)
                .setModeValue(PresentationMode.MOVIE_3D, FPS, "90", "60")
                .setLastSuccessfulMode(PresentationMode.MOVIE_3D);

        AtomicReference<Boolean> firstResult = new AtomicReference<>();
        AtomicReference<Boolean> secondResult = new AtomicReference<>();
        Thread firstThread = new Thread(() -> firstResult.set(first.commit()), "first-commit");
        Thread secondThread = new Thread(() -> secondResult.set(second.commit()),
                "second-commit");
        try {
            firstThread.start();
            assertTrue("first commit never reached its write",
                    firstWriteEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            secondThread.start();
            awaitBlockedEnteringCommitOrTerminated(secondThread);
            assertTrue("second commit must wait while the first holds the write lock",
                    isBlockedEnteringCommit(secondThread));
        } finally {
            releaseFirstWrite.countDown();
            firstThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            secondThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        }
        assertFalse(firstThread.isAlive());
        assertFalse(secondThread.isAlive());
        assertEquals(Boolean.TRUE, firstResult.get());
        assertEquals(Boolean.TRUE, secondResult.get());

        SessionSettingsStore.SessionRecord record =
                new SessionSettingsStore(storage).getCurrentSession(pc);
        assertNotNull(record);
        assertEquals("120", record.getModeOverrides(PresentationMode.NORMAL).get(FPS));
        assertEquals("90", record.getModeOverrides(PresentationMode.MOVIE_3D).get(FPS));
        assertEquals(PresentationMode.MOVIE_3D, record.getLastSuccessfulMode());
    }

    /**
     * Spins, without sleeping, until the thread is blocked on a monitor in Editor.commit itself
     * or has finished. The only monitor commit enters directly is the write lock; a thread
     * blocked anywhere else (class loading, the preferences' own locks) has a different top
     * frame and keeps the wait going.
     */
    private static void awaitBlockedEnteringCommitOrTerminated(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (thread.getState() != Thread.State.TERMINATED && !isBlockedEnteringCommit(thread)) {
            assertTrue("second commit is stuck in " + thread.getState(),
                    System.nanoTime() < deadline);
            Thread.yield();
        }
    }

    private static boolean isBlockedEnteringCommit(Thread thread) {
        if (thread.getState() != Thread.State.BLOCKED) {
            return false;
        }
        StackTraceElement[] stack = thread.getStackTrace();
        return stack.length > 0
                && SessionSettingsStore.Editor.class.getName().equals(stack[0].getClassName())
                && "commit".equals(stack[0].getMethodName())
                && thread.getState() == Thread.State.BLOCKED;
    }

    /** Holds the first editor commit until released; every other call goes straight through. */
    private static final class HoldingPreferences implements SharedPreferences {
        private final SharedPreferences delegate;
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private final AtomicBoolean held = new AtomicBoolean();

        HoldingPreferences(SharedPreferences delegate, CountDownLatch entered,
                           CountDownLatch release) {
            this.delegate = delegate;
            this.entered = entered;
            this.release = release;
        }

        @Override public Map<String, ?> getAll() {
            return delegate.getAll();
        }

        @Override public String getString(String key, String defValue) {
            return delegate.getString(key, defValue);
        }

        @Override public Set<String> getStringSet(String key, Set<String> defValues) {
            return delegate.getStringSet(key, defValues);
        }

        @Override public int getInt(String key, int defValue) {
            return delegate.getInt(key, defValue);
        }

        @Override public long getLong(String key, long defValue) {
            return delegate.getLong(key, defValue);
        }

        @Override public float getFloat(String key, float defValue) {
            return delegate.getFloat(key, defValue);
        }

        @Override public boolean getBoolean(String key, boolean defValue) {
            return delegate.getBoolean(key, defValue);
        }

        @Override public boolean contains(String key) {
            return delegate.contains(key);
        }

        @Override public Editor edit() {
            return new HoldingEditor(delegate.edit());
        }

        @Override public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
            delegate.registerOnSharedPreferenceChangeListener(listener);
        }

        @Override public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
            delegate.unregisterOnSharedPreferenceChangeListener(listener);
        }

        private final class HoldingEditor implements Editor {
            private final Editor editor;

            HoldingEditor(Editor editor) {
                this.editor = editor;
            }

            @Override public Editor putString(String key, String value) {
                editor.putString(key, value);
                return this;
            }

            @Override public Editor putStringSet(String key, Set<String> values) {
                editor.putStringSet(key, values);
                return this;
            }

            @Override public Editor putInt(String key, int value) {
                editor.putInt(key, value);
                return this;
            }

            @Override public Editor putLong(String key, long value) {
                editor.putLong(key, value);
                return this;
            }

            @Override public Editor putFloat(String key, float value) {
                editor.putFloat(key, value);
                return this;
            }

            @Override public Editor putBoolean(String key, boolean value) {
                editor.putBoolean(key, value);
                return this;
            }

            @Override public Editor remove(String key) {
                editor.remove(key);
                return this;
            }

            @Override public Editor clear() {
                editor.clear();
                return this;
            }

            @Override public boolean commit() {
                if (held.compareAndSet(false, true)) {
                    entered.countDown();
                    try {
                        if (!release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                            return false;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                return editor.commit();
            }

            @Override public void apply() {
                editor.apply();
            }
        }
    }
}
