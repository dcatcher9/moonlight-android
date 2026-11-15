package com.limelight;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.os.Build;
import android.os.PersistableBundle;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.limelight.nvstream.http.NvHTTP;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Copies clipboard text between the headset and the host over the host's HTTPS clipboard action.
 *
 * <p>Clips this class reads or writes carry {@link #CLIPBOARD_IDENTIFIER}, so the same text is not
 * sent back. {@link #sendClipboard} and {@link #getClipboard} are called on the main thread; each
 * host request runs on its own thread and posts its toast back through the activity.</p>
 */
final class StreamClipboardSync {
    static final String CLIPBOARD_IDENTIFIER = "ArtemisStreaming";

    private final Activity activity;
    private final PreferenceConfiguration prefConfig;
    private final ClipboardManager clipboardManager;
    private final Supplier<NvHTTP> httpConnSupplier;
    private final BooleanSupplier menuOpen;
    private boolean clipboardSyncRunning = false;

    StreamClipboardSync(Activity activity, PreferenceConfiguration prefConfig,
                        ClipboardManager clipboardManager, Supplier<NvHTTP> httpConnSupplier,
                        BooleanSupplier menuOpen) {
        this.activity = activity;
        this.prefConfig = prefConfig;
        this.clipboardManager = clipboardManager;
        this.httpConnSupplier = httpConnSupplier;
        this.menuOpen = menuOpen;
    }

    // Method to get clipboard content
    String getClipboardContent(boolean force) {
        // Check if there is any clipboard data
        if (clipboardManager.hasPrimaryClip()) {
            ClipDescription clipDescription = clipboardManager.getPrimaryClipDescription();
            if (!force && clipDescription != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    PersistableBundle extras = clipDescription.getExtras();
                    if (extras != null && extras.getBoolean(CLIPBOARD_IDENTIFIER)) {
                        // We're getting the clipboard data we just set/read a while ago
                        return null;
                    }
                } else {
                    CharSequence clipLabel = clipDescription.getLabel();
                    if (clipLabel != null && clipLabel.equals(CLIPBOARD_IDENTIFIER)) {
                        // We're getting the clipboard data we set a while ago
                        return null;
                    }
                }
            }

            ClipData clipData = clipboardManager.getPrimaryClip();

            if (clipData != null && clipData.getItemCount() > 0) {
                // Get the first item from the clipboard data
                ClipData.Item item = clipData.getItemAt(0);

                // Mark the clip as visited
                if (clipDescription != null) {
                    ClipData clonedClip = cloneClipData(clipDescription, item);
                    clipboardManager.setPrimaryClip(clonedClip);
                }

                // Get the text data from the clipboard item
                CharSequence clipText = item.getText();
                if (clipText == null) {
                    return  null;
                }
                return clipText.toString();
            }
        }

        return null;
    }

    private static @NonNull ClipData cloneClipData(ClipDescription clipDescription, ClipData.Item item) {
        ClipDescription clonedDescription = new ClipDescription(clipDescription);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            PersistableBundle extras = clipDescription.getExtras();
            if (extras == null) {
                extras = new PersistableBundle();
            }
            extras.putBoolean(CLIPBOARD_IDENTIFIER, true);
            clonedDescription.setExtras(extras);
        }

        return new ClipData(clonedDescription, item);
    }

    boolean sendClipboard(boolean force) {
        NvHTTP httpConn = httpConnSupplier.get();
        if (httpConn == null) {
            LimeLog.warning("httpConn not ready, cannot send clipboard!");
            return false;
        }

        String clipboardText = getClipboardContent(force);
        if (clipboardText != null) {
            new Thread() {
                public void run() {
                    try {
                        if (!httpConn.sendClipboard(clipboardText)) {
                            if (prefConfig.smartClipboardSyncToast) {
                                activity.runOnUiThread(() -> Toast.makeText(activity, activity.getString(R.string.clipboard_sync_unsupported), Toast.LENGTH_SHORT).show());
                            }
                        } else {
                            if (prefConfig.smartClipboardSyncToast) {
                                activity.runOnUiThread(() -> Toast.makeText(activity, activity.getString(R.string.send_clipboard_success), Toast.LENGTH_SHORT).show());
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        if (prefConfig.smartClipboardSyncToast) {
                            activity.runOnUiThread(() -> Toast.makeText(activity, activity.getString(R.string.send_clipboard_failed) + e.getMessage(), Toast.LENGTH_SHORT).show());
                        }
                    }
                }
            }.start();

            return true;
        }

        return false;
    }

    boolean getClipboard(int delay) {
        NvHTTP httpConn = httpConnSupplier.get();
        if (httpConn == null) {
            LimeLog.warning("httpConn not ready, cannot get clipboard!");
            return false;
        }

        if (delay == 0 && menuOpen.getAsBoolean()) {
            return false;
        }

        new Thread() {
            public void run() {
                if (clipboardSyncRunning) {
                    return;
                }

                clipboardSyncRunning = true;
                try {
                    if (delay > 0) {
                        sleep(delay);
                    }
                    String clipboardContent = httpConn.getClipboard();
                    ClipData clipData = ClipData.newPlainText(CLIPBOARD_IDENTIFIER, clipboardContent);

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        ClipDescription clipDescription = clipData.getDescription();
                        PersistableBundle newExtras = new PersistableBundle();
                        newExtras.putBoolean(CLIPBOARD_IDENTIFIER, true);
                        if (prefConfig.hideClipboardContent) {
                            // We don't know if the message is sensitive or not, to be safe mark them all as sensitive.
                            newExtras.putBoolean("android.content.extra.IS_SENSITIVE", true);
                        }
                        clipDescription.setExtras(newExtras);
                    }

                    clipboardManager.setPrimaryClip(clipData);
                    if (prefConfig.smartClipboardSyncToast) {
                        activity.runOnUiThread(() -> Toast.makeText(activity, activity.getString(R.string.get_clipboard_success), Toast.LENGTH_SHORT).show());
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    if (prefConfig.smartClipboardSyncToast) {
                        activity.runOnUiThread(() -> Toast.makeText(activity, activity.getString(R.string.get_clipboard_failed) + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }
                clipboardSyncRunning = false;
            }
        }.start();

        return true;
    }
}
