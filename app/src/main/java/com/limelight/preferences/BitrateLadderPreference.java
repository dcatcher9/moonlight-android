package com.limelight.preferences;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.limelight.R;
import com.limelight.ui.xrcontrols.SessionSettingsModel;
import com.limelight.ui.xrcontrols.XrBitrateRecommendation;
import com.limelight.ui.xrcontrols.XrSegmentedLadder;

import java.util.ArrayList;
import java.util.List;

/**
 * Global bitrate setting, on the same discrete ladder the in-session picker uses.
 *
 * <p>Replaces a seek bar that offered 600 positions in 0.5 Mbps steps. Two problems: dragging is a
 * poor fit for gaze-and-pinch, and the values between rungs were meaningless — a ceiling of
 * 187.5 Mbps says nothing a nearby rung does not. Sharing {@link XrBitrateRecommendation} keeps
 * both surfaces on one ladder, so a value chosen here is always selectable in-session too.</p>
 */
public class BitrateLadderPreference extends Preference {
    private int currentValue;

    public BitrateLadderPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_xr_bitrate_ladder);
        setSelectable(false);
        currentValue = defaultRung();
    }

    private static int defaultRung() {
        List<Integer> ladder = XrBitrateRecommendation.LADDER_KBPS;
        // 200 Mbps: the rung recommended for the shipped 4K host-SBS default.
        return ladder.contains(200000) ? 200000 : ladder.get(ladder.size() - 1);
    }

    @Override
    protected void onSetInitialValue(@Nullable Object suppliedDefaultValue) {
        int fallback = suppliedDefaultValue instanceof Number
                ? ((Number) suppliedDefaultValue).intValue() : defaultRung();
        currentValue = migrateToLadder(getPersistedInt(fallback));
    }

    /**
     * Snaps a stored rate onto the ladder, rewriting storage when it was off-ladder.
     *
     * <p>The seek bar could persist any value in 0.5 Mbps steps, and the old thirteen-rung list
     * offered rates this one does not. Those are retired outright rather than carried as extra
     * segments: a one-off value the user cannot reselect after changing it once is clutter, and
     * leaving it in storage means the two surfaces disagree about what is selectable.</p>
     */
    private int migrateToLadder(int storedKbps) {
        int snapped = snapToLadder(storedKbps);
        if (snapped != storedKbps && shouldPersist()) {
            persistInt(snapped);
        }
        return snapped;
    }

    /**
     * Nearest rung to {@code kbps}. Ties go to the HIGHER rung: the value is a ceiling, so rounding
     * up only widens the headroom the encoder may use and costs nothing when the content does not
     * need it, whereas rounding down would quietly lower a limit the user had set. A retired
     * 250000 sits exactly between 200000 and 300000 and takes the latter.
     */
    static int snapToLadder(int kbps) {
        List<Integer> rungs = XrBitrateRecommendation.LADDER_KBPS;
        int best = rungs.get(0);
        long bestDistance = Long.MAX_VALUE;
        for (int rung : rungs) {
            long distance = Math.abs((long) rung - kbps);
            // Rungs ascend, so <= lets the higher of two equidistant rungs win.
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = rung;
            }
        }
        return best;
    }

    /** Stored rate in kbps, which may legitimately not be a ladder rung. */
    public int getCurrentValue() {
        return currentValue;
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.itemView.setClickable(false);
        holder.itemView.setFocusable(false);

        if (shouldPersist()) {
            currentValue = migrateToLadder(getPersistedInt(currentValue));
        }

        XrSegmentedLadder ladder =
                (XrSegmentedLadder) holder.findViewById(R.id.xr_bitrate_ladder);
        if (ladder == null) {
            return;
        }
        List<SessionSettingsModel.Choice> choices = new ArrayList<>();
        for (int rung : XrBitrateRecommendation.LADDER_KBPS) {
            choices.add(new SessionSettingsModel.Choice(
                    String.valueOf(rung), XrBitrateRecommendation.label(rung)));
        }
        ladder.setEnabled(isEnabled());
        ladder.setChoices(choices, String.valueOf(currentValue), null, null,
                (choice, index, count) -> choice.label,
                choiceId -> {
                    int selected = Integer.parseInt(choiceId);
                    if (!callChangeListener(selected)) {
                        return false;
                    }
                    currentValue = selected;
                    persistInt(selected);
                    return true;
                });
    }
}
