package com.limelight.preferences.session;

import android.content.SharedPreferences;

import com.google.gson.JsonParseException;
import com.limelight.preferences.session.SessionSettingsStore.AppIdentity;
import com.limelight.preferences.session.SessionSettingsStore.PcIdentity;
import com.limelight.preferences.session.SessionSettingsStore.SavedSettings;
import com.limelight.preferences.session.SessionSettingsStore.SessionRecord;

import java.util.Locale;
import java.util.Map;

/**
 * The saved settings of each application on each PC: one SharedPreferences string per app,
 * stored under {@code settings.<pc>.app.<app>}, keyed by the strongest app identity known.
 *
 * <p>This class owns no lock. Every method reads the store's SharedPreferences, so
 * {@link SessionSettingsStore} calls it only while holding its {@code WRITE_LOCK}. Writes are
 * queued into the caller's editor, so a profile commits together with the current-session record
 * it belongs to.</p>
 */
final class AppProfileIndex {
    static final String KEY_PREFIX = "settings.";

    private final SharedPreferences preferences;

    AppProfileIndex(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    /**
     * Queues {@code record}'s settings as the profile of its application, merging identity fields
     * the existing profile already knew.
     */
    void put(SharedPreferences.Editor editor, PcIdentity pc, SessionRecord record) {
        AppIdentity recordApp = record.getCurrentApp();
        SavedSettings previous = find(pc, recordApp);
        AppIdentity identity = previous == null ? recordApp : new AppIdentity(
                recordApp.getAppId() != null ? recordApp.getAppId() : previous.app.getAppId(),
                recordApp.getAppUuid() != null
                        ? recordApp.getAppUuid() : previous.app.getAppUuid(),
                recordApp.getDisplayName() != null
                        ? recordApp.getDisplayName() : previous.app.getDisplayName());
        String key = key(pc, identity);
        // Stronger host app identity can arrive later (for example, a UUID after a numeric ID).
        // Retire compatible aliases so they cannot restore older settings on a later launch.
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (entry.getKey().startsWith(prefix(pc)) && !entry.getKey().equals(key)) {
                SavedSettings saved = parse(entry.getValue());
                if (saved != null && saved.app.isSameApplication(identity)
                        && (identity.getAppUuid() != null || saved.app.getAppUuid() == null)
                        && (identity.getAppId() != null || saved.app.getAppId() == null)) {
                    editor.remove(entry.getKey());
                }
            }
        }
        editor.putString(key, SessionRecordCodec.encodeProfile(record, identity));
    }

    /** The profile saved for {@code app} on {@code pc}, or null when none matches unambiguously. */
    SavedSettings find(PcIdentity pc, AppIdentity app) {
        String key = key(pc, app);
        SavedSettings saved = parse(preferences.getAll().get(key));
        if (saved != null && saved.app.isSameApplication(app)) {
            return saved;
        }
        SavedSettings match = null;
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (entry.getKey().startsWith(prefix(pc)) && !entry.getKey().equals(key)) {
                saved = parse(entry.getValue());
                if (saved != null && saved.app.isSameApplication(app)) {
                    if (match != null && !match.app.isSameApplication(saved.app)) {
                        // A weaker lookup must not choose between two distinct UUID identities.
                        return null;
                    }
                    match = saved;
                }
            }
        }
        return match;
    }

    /** Queues the removal of every profile saved under {@code pc}. */
    void removeAll(SharedPreferences.Editor editor, PcIdentity pc) {
        String profilePrefix = prefix(pc);
        for (String key : preferences.getAll().keySet()) {
            if (key.startsWith(profilePrefix)) {
                editor.remove(key);
            }
        }
    }

    private static SavedSettings parse(Object json) {
        if (!(json instanceof String)) {
            return null;
        }
        try {
            return SessionRecordCodec.decodeProfile((String) json);
        } catch (JsonParseException | IllegalArgumentException | ClassCastException ignored) {
            return null;
        }
    }

    private static String prefix(PcIdentity pc) {
        return KEY_PREFIX + pc.getStorageId() + ".app.";
    }

    private static String key(PcIdentity pc, AppIdentity app) {
        String identity = app.getAppUuid() != null
                ? "uuid." + app.getAppUuid().toLowerCase(Locale.US)
                : app.getAppId() != null ? "id." + app.getAppId()
                : "name." + app.getDisplayName().toLowerCase(Locale.US);
        return prefix(pc) + SessionSettingsStore.safeComponent(identity, true);
    }
}
