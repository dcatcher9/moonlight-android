package com.limelight;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.limelight.nvstream.HostSessionLaunchRequest;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.preferences.XrSessionSettingsController;
import com.limelight.preferences.session.SessionSettingsStore;
import com.limelight.ui.PresentationMode;

/**
 * Resolves a stream launch into its durable session: the PC and app identity, the active-session
 * record (confirming a Resume or starting a new session), the startup presentation mode and the
 * startup preferences; and records the host session once the connection establishes it.
 *
 * <p>Stateless. {@link #prepare} runs on the main thread from {@code Game.onCreate()};
 * {@link #recordEstablishedHostSession} runs on the connection thread. Both serialize their
 * store writes through {@link SessionSettingsStore}'s own write lock.</p>
 */
final class XrSessionBootstrap {
    /** One launch's session identity and startup preferences. */
    static final class Startup {
        /** The launch request's token capability, before the host confirms it. */
        final boolean hostSessionIdSupported;
        /** Null, as are the fields below, when the launch has no settings identity. */
        final SessionSettingsStore store;
        final SessionSettingsStore.PcIdentity pc;
        final SessionSettingsStore.AppIdentity app;
        final String localSessionId;
        final String hostSessionId;
        final XrSessionSettingsController controller;
        /** The startup preferences; Global Settings when the launch has no settings identity. */
        final SharedPreferences preferences;

        private Startup(boolean hostSessionIdSupported, SessionSettingsStore store,
                        SessionSettingsStore.PcIdentity pc, SessionSettingsStore.AppIdentity app,
                        String localSessionId, String hostSessionId,
                        XrSessionSettingsController controller, SharedPreferences preferences) {
            this.hostSessionIdSupported = hostSessionIdSupported;
            this.store = store;
            this.pc = pc;
            this.app = app;
            this.localSessionId = localSessionId;
            this.hostSessionId = hostSessionId;
            this.controller = controller;
            this.preferences = preferences;
        }
    }

    private XrSessionBootstrap() { }

    static Startup prepare(Context context, Intent intent) {
        SharedPreferences globalPreferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        String pcUuid = intent.getStringExtra(Game.EXTRA_PC_UUID);
        String fallbackHost = intent.getStringExtra(Game.EXTRA_HOST);
        String launchAppName = intent.getStringExtra(Game.EXTRA_APP_NAME);
        String launchAppUuid = intent.getStringExtra(Game.EXTRA_APP_UUID);
        int launchAppId = Game.readAppId(intent);
        HostSessionLaunchRequest launchRequest = Game.getHostSessionLaunchRequest(intent);
        String publishedHostSessionId = launchRequest.expectedToken;
        boolean hostSessionIdSupported = launchRequest.tokenSupported;

        SessionSettingsStore.PcIdentity sessionPc;
        SessionSettingsStore.AppIdentity sessionApp;
        try {
            sessionPc = new SessionSettingsStore.PcIdentity(pcUuid, fallbackHost);
            sessionApp = new SessionSettingsStore.AppIdentity(
                    launchAppId != StreamConfiguration.INVALID_APP_ID
                            ? Integer.toString(launchAppId) : null,
                    launchAppUuid, launchAppName);
        }
        catch (IllegalArgumentException missingIdentity) {
            LimeLog.warning("Unable to establish XR session settings identity: "
                    + missingIdentity.getMessage());
            return new Startup(hostSessionIdSupported, null, null, null, null, null, null,
                    globalPreferences);
        }

        SessionSettingsStore sessionSettingsStore = new SessionSettingsStore(context);
        // Only a resume that matches this PC's local session record continues its mode.
        boolean resume = false;
        if (launchRequest.kind == HostSessionLaunchRequest.Kind.RESUME) {
            SessionSettingsStore.SessionRecord restored = hostSessionIdSupported
                    ? sessionSettingsStore.confirmHostResume(sessionPc, sessionApp,
                            publishedHostSessionId, System.currentTimeMillis())
                    : sessionSettingsStore.confirmLegacyHostResume(
                            sessionPc, sessionApp, System.currentTimeMillis());
            resume = restored != null;
            if (restored == null && (!hostSessionIdSupported
                    || SessionSettingsStore.ResumeMetadata.isValidHostSessionId(
                            publishedHostSessionId))) {
                // The user explicitly selected Resume from a fresh serverinfo snapshot. A new
                // token generation (or a standard host with no saved local record) may be
                // resumed with fresh ownership and this app's saved settings. No local record
                // proves its presentation, so it starts in 2D like any new session.
                sessionSettingsStore.startNewSession(sessionPc, sessionApp,
                        hostSessionIdSupported ? publishedHostSessionId : null,
                        System.currentTimeMillis());
            }
        }
        else {
            // A non-resume launch gets a fresh local generation and host capability. Its settings
            // come from this app's profile on this PC, independently of the ended session identity.
            // Its presentation mode does not: a new host session starts in 2D (see below).
            sessionSettingsStore.startNewSession(sessionPc, sessionApp, null,
                    System.currentTimeMillis());
        }

        SessionSettingsStore.Snapshot snapshot =
                sessionSettingsStore.snapshot(sessionPc, globalPreferences);
        PresentationMode startupModeOverride = null;
        PresentationMode savedMode = snapshot.getRecord() != null
                ? snapshot.getRecord().getLastSuccessfulMode() : PresentationMode.NORMAL;
        // Only a confirmed resume continues the previous presentation mode. A new host session,
        // including an unconfirmed Resume, starts in 2D whatever the app's profile last used;
        // its saved per-mode quality is kept.
        boolean freshSessionStartsNormal = !resume && savedMode != PresentationMode.NORMAL;
        boolean hostAiUnavailable = savedMode == PresentationMode.HOST_SBS_AI
                && !hostSessionIdSupported;
        if (snapshot.getRecord() != null && (freshSessionStartsNormal || hostAiUnavailable)) {
            if (freshSessionStartsNormal) {
                LimeLog.info("New host session starts in Normal; saved mode " + savedMode
                        + " applies only to a resume");
            }
            else {
                LimeLog.warning("Host SBS AI is unavailable on a standard Sunshine/Apollo host; "
                        + "restoring Normal");
            }
            boolean repaired = sessionSettingsStore.edit(
                            sessionPc, sessionApp,
                            snapshot.getRecord().getLocalSessionId())
                    .setLastSuccessfulMode(PresentationMode.NORMAL)
                    .commit();
            if (repaired) {
                snapshot = sessionSettingsStore.snapshot(sessionPc, globalPreferences);
            }
            else {
                startupModeOverride = PresentationMode.NORMAL;
                LimeLog.warning("Unable to persist the Normal startup mode; "
                        + "forcing Normal for this launch");
            }
        }
        String localSessionId = snapshot.getRecord() != null
                ? snapshot.getRecord().getLocalSessionId() : null;
        SessionSettingsStore.ResumeMetadata resumeMetadata = snapshot.getRecord() != null
                ? snapshot.getRecord().getResumeMetadata() : null;
        String hostSessionId = hostSessionIdSupported && resumeMetadata != null
                ? resumeMetadata.getHostSessionId() : null;
        XrSessionSettingsController controller = new XrSessionSettingsController(
                sessionSettingsStore, sessionPc, sessionApp, globalPreferences, snapshot,
                startupModeOverride);
        return new Startup(hostSessionIdSupported, sessionSettingsStore, sessionPc, sessionApp,
                localSessionId, hostSessionId, controller, controller.getStartupPreferences());
    }

    /**
     * Persists the host session the connection established. {@code restoreNormal} also records
     * Normal as the last successful mode, for a host whose capabilities no longer allow the
     * selected mode. A launch without a session record persists nothing.
     */
    static void recordEstablishedHostSession(SessionSettingsStore store,
                                             SessionSettingsStore.PcIdentity pc,
                                             SessionSettingsStore.AppIdentity app,
                                             String localSessionId,
                                             SessionSettingsStore.ResumeMetadata metadata,
                                             boolean restoreNormal) {
        if (store == null || pc == null || app == null || localSessionId == null) {
            LimeLog.warning("No XR session record; host session capability is not persisted");
            return;
        }
        SessionSettingsStore.Editor sessionEditor = store.edit(pc, app, localSessionId)
                .setResumeMetadata(metadata);
        if (restoreNormal) {
            // Discovery can race a host restart/downgrade. Persist the safe interpretation
            // before replacing the Activity so a mono standard-host stream can never remain
            // stuck in a disabled Host SBS AI presentation.
            sessionEditor.setLastSuccessfulMode(PresentationMode.NORMAL);
        }
        if (!sessionEditor.commit()) {
            LimeLog.warning("Unable to persist the established host session capability");
        }
    }
}
