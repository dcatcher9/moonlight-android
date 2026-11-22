package com.limelight.preferences;

import com.limelight.ui.xrcontrols.SessionSettingsModel;
import com.limelight.ui.xrcontrols.XrBitrateRecommendation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The in-session choice catalog: every ordered option list the XR settings panel offers, with the
 * stored value as each choice's id. {@link XrSessionSettingsController} stages and validates
 * against these lists; {@code XrSettingChoicesParityTest} pins the Global Settings arrays in
 * {@code arrays.xml} to the same ids and order.
 */
final class XrSettingChoices {
    static final String CODEC_AUTO = "auto";
    static final String CODEC_AV1 = "forceav1";
    static final String CODEC_HEVC = "forceh265";
    static final String CODEC_H264 = "neverh265";
    // Six rungs, derived by scoring candidates against every resolution x fps x codec x
    // packed/flat combination the picker can produce. The value is a ceiling rather than a target,
    // so reach matters more than resolution: the old thirteen-rung list spent six rungs below
    // 50 Mbps that no configuration on a local link ever wants, and put 120 and 130 -- an
    // eight percent difference nobody can see -- next to each other.
    static final List<Integer> BITRATES = XrBitrateRecommendation.LADDER_KBPS;
    static final List<SessionSettingsModel.Choice> RESOLUTION_CHOICES =
            createResolutionChoices();
    // 72 is a native Galaxy XR panel mode (the display reports 60/72/90), so it is offered as a
    // first-class rate: matching the stream to the panel avoids the uneven frame pacing a 90 fps
    // stream produces while the headset is running its 72 Hz mode.
    static final List<SessionSettingsModel.Choice> FRAME_RATE_CHOICES = choices(
            choice("30", "30"),
            choice("60", "60"),
            choice("72", "72"),
            choice("90", "90"),
            choice("120", "120"));
    static final List<SessionSettingsModel.Choice> BITRATE_CHOICES =
            createBitrateChoices();
    static final List<SessionSettingsModel.Choice> ON_OFF_CHOICES = choices(
            choice("false", "Off"), choice("true", "On"));
    static final List<SessionSettingsModel.Choice> VIDEO_RANGE_CHOICES = choices(
            choice("false", "Limited"), choice("true", "Full"));
    static final List<SessionSettingsModel.Choice> GAMMA_CHOICES = choices(
            choice("default", "default"), choice("2.2", "2.2"), choice("2.4", "2.4"));
    static final List<SessionSettingsModel.Choice> CODEC_CHOICES = choices(
            choice(CODEC_AUTO, "Auto"),
            choice(CODEC_AV1, "AV1"),
            choice(CODEC_HEVC, "HEVC"),
            choice(CODEC_H264, "H.264"));
    static final List<SessionSettingsModel.Choice> PACING_CHOICES = choices(
            choice("latency", "Latency"),
            choice("balanced", "Balanced"),
            choice("cap-fps", "FPS cap"),
            choice("smoothness", "Smooth"));
    static final List<SessionSettingsModel.Choice> AUDIO_CHOICES = choices(
            choice("2", "Stereo"), choice("51", "5.1"), choice("71", "7.1"));

    static List<SessionSettingsModel.Choice> baseChoicesFor(
            SessionSettingsModel.Key key) {
        switch (key) {
            case RESOLUTION:
                return RESOLUTION_CHOICES;
            case FRAME_RATE:
                return FRAME_RATE_CHOICES;
            case BITRATE:
                return BITRATE_CHOICES;
            case HDR:
            case PLAY_AUDIO_ON_PC:
                return ON_OFF_CHOICES;
            case VIDEO_RANGE:
                return VIDEO_RANGE_CHOICES;
            case STREAM_GAMMA:
                return GAMMA_CHOICES;
            case CODEC:
                return CODEC_CHOICES;
            case FRAME_PACING:
                return PACING_CHOICES;
            case AUDIO_LAYOUT:
                return AUDIO_CHOICES;
            default:
                throw new IllegalArgumentException("Unsupported setting: " + key);
        }
    }

    static SessionSettingsModel.Choice choice(String id, String label) {
        return new SessionSettingsModel.Choice(id, label);
    }

    private static List<SessionSettingsModel.Choice> createResolutionChoices() {
        ArrayList<SessionSettingsModel.Choice> choices = new ArrayList<>();
        for (XrResolutionOptions.Option option : XrResolutionOptions.standardOptions()) {
            choices.add(choice(option.id, option.label));
        }
        return Collections.unmodifiableList(choices);
    }

    private static List<SessionSettingsModel.Choice> choices(
            SessionSettingsModel.Choice... choices) {
        return Collections.unmodifiableList(Arrays.asList(choices));
    }

    private static List<SessionSettingsModel.Choice> createBitrateChoices() {
        ArrayList<SessionSettingsModel.Choice> choices = new ArrayList<>(BITRATES.size());
        for (int bitrate : BITRATES) {
            choices.add(choice(String.valueOf(bitrate), XrBitrateRecommendation.label(bitrate)));
        }
        return Collections.unmodifiableList(choices);
    }

    private XrSettingChoices() {
    }
}
