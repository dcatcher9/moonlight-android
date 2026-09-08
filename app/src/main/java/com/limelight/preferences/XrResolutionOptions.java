package com.limelight.preferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Authoritative XR resolution ladder shared by the global and in-session quality pickers.
 *
 * <p>The six established landscape entries retain their original order, followed by phone/tablet
 * source dimensions. Explicit portrait counterparts follow in the same order. IDs are real requested
 * stream dimensions; selecting one never relies on Android's display-rotation inversion preference.</p>
 */
public final class XrResolutionOptions {
    public static final String RESOLUTION_1080P = "1920x1080";
    public static final String RESOLUTION_1440P = "2560x1440";
    public static final String RESOLUTION_4K = "3840x2160";
    public static final String RESOLUTION_UW_1080P = "2560x1080";
    public static final String RESOLUTION_UW_1440P = "3440x1440";
    public static final String RESOLUTION_5K2K = "5120x2160";

    public static final String RESOLUTION_PHONE_18_9 = "2160x1080";
    public static final String RESOLUTION_PHONE_19_5_9 = "2340x1080";
    public static final String RESOLUTION_PHONE_20_9 = "2400x1080";
    public static final String RESOLUTION_PHONE_WIDE = "2424x1080";
    public static final String RESOLUTION_TABLET_1200P = "1920x1200";
    public static final String RESOLUTION_TABLET_1600P = "2560x1600";
    public static final String RESOLUTION_TABLET_4_3 = "2048x1536";
    public static final String RESOLUTION_TABLET_4_3_HIGH = "2732x2048";
    public static final String RESOLUTION_TABLET_3_2 = "2160x1440";
    public static final String RESOLUTION_TABLET_1640P = "2360x1640";
    public static final String RESOLUTION_TABLET_1668P = "2388x1668";
    public static final String RESOLUTION_TABLET_1668P_HIGH = "2420x1668";

    public static final String RESOLUTION_1080P_PORTRAIT = "1080x1920";
    public static final String RESOLUTION_1440P_PORTRAIT = "1440x2560";
    public static final String RESOLUTION_4K_PORTRAIT = "2160x3840";
    public static final String RESOLUTION_UW_1080P_PORTRAIT = "1080x2560";
    public static final String RESOLUTION_UW_1440P_PORTRAIT = "1440x3440";
    public static final String RESOLUTION_5K2K_PORTRAIT = "2160x5120";

    private static final List<Option> LANDSCAPE_OPTIONS = Collections.unmodifiableList(
            Arrays.asList(
                    new Option(RESOLUTION_1080P, "1080p", 1920, 1080, false),
                    new Option(RESOLUTION_1440P, "1440p", 2560, 1440, false),
                    new Option(RESOLUTION_4K, "4K", 3840, 2160, false),
                    new Option(RESOLUTION_UW_1080P, "UW 1080p", 2560, 1080, false),
                    new Option(RESOLUTION_UW_1440P, "UW 1440p", 3440, 1440, false),
                    new Option(RESOLUTION_5K2K, "5K2K", 5120, 2160, false),
                    new Option(RESOLUTION_PHONE_18_9, "Phone 18:9", 2160, 1080, false),
                    new Option(RESOLUTION_PHONE_19_5_9, "Phone 19.5:9", 2340, 1080, false),
                    new Option(RESOLUTION_PHONE_20_9, "Phone 20:9", 2400, 1080, false),
                    new Option(RESOLUTION_PHONE_WIDE, "Phone wide", 2424, 1080, false),
                    new Option(RESOLUTION_TABLET_1200P, "Tablet 1200p", 1920, 1200, false),
                    new Option(RESOLUTION_TABLET_1600P, "Tablet 1600p", 2560, 1600, false),
                    new Option(RESOLUTION_TABLET_4_3, "Tablet 4:3", 2048, 1536, false),
                    new Option(RESOLUTION_TABLET_4_3_HIGH, "Tablet 4:3+", 2732, 2048, false),
                    new Option(RESOLUTION_TABLET_3_2, "Tablet 3:2", 2160, 1440, false),
                    new Option(RESOLUTION_TABLET_1640P, "Tablet 1640p", 2360, 1640, false),
                    new Option(RESOLUTION_TABLET_1668P, "Tablet 1668p", 2388, 1668, false),
                    new Option(RESOLUTION_TABLET_1668P_HIGH, "Tablet 1668p+", 2420, 1668, false)));

    private static final List<Option> STANDARD_OPTIONS =
            buildWithPortraitCounterparts(LANDSCAPE_OPTIONS);

    private XrResolutionOptions() {
    }

    public static List<Option> standardOptions() {
        return STANDARD_OPTIONS;
    }

    public static boolean isStandardId(String id) {
        if (id == null) {
            return false;
        }
        for (Option option : STANDARD_OPTIONS) {
            if (option.id.equals(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds one swapped-dimension counterpart for each landscape seed.
     *
     * <p>A linked map makes both the order and collision policy explicit: seed entries win, and a
     * square seed or an already-present swapped ID is not duplicated.</p>
     */
    static List<Option> buildWithPortraitCounterparts(List<Option> landscapeOptions) {
        Objects.requireNonNull(landscapeOptions, "landscapeOptions");
        Map<String, Option> ordered = new LinkedHashMap<>();
        for (Option option : landscapeOptions) {
            Option value = Objects.requireNonNull(option, "option");
            ordered.putIfAbsent(value.id, value);
        }
        for (Option landscape : landscapeOptions) {
            Option portrait = landscape.asPortraitCounterpart();
            ordered.putIfAbsent(portrait.id, portrait);
        }
        return Collections.unmodifiableList(new ArrayList<>(ordered.values()));
    }

    public static final class Option {
        public final String id;
        public final String label;
        public final int width;
        public final int height;
        public final boolean portrait;

        Option(String id, String label, int width, int height, boolean portrait) {
            this.id = requireText(id, "id");
            this.label = requireText(label, "label");
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Resolution dimensions must be positive");
            }
            String expectedId = width + "x" + height;
            if (!expectedId.equals(id)) {
                throw new IllegalArgumentException(
                        "Resolution ID must match dimensions: " + id + " != " + expectedId);
            }
            this.width = width;
            this.height = height;
            this.portrait = portrait;
        }

        private Option asPortraitCounterpart() {
            return new Option(height + "x" + width, label + " Portrait",
                    height, width, width > height);
        }

        private static String requireText(String value, String name) {
            Objects.requireNonNull(value, name);
            if (value.trim().isEmpty()) {
                throw new IllegalArgumentException(name + " must not be empty");
            }
            return value;
        }
    }
}
