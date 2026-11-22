package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatButton;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.preferences.XrChoiceGroup;
import com.limelight.preferences.XrSegmentButton;
import com.limelight.ui.StreamContainer;
import com.limelight.ui.xrcontrols.XrResolutionSelector;
import com.limelight.ui.xrcontrols.XrSegmentedLadder;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Every app layout inflates under the app theme into the root type its owner expects and carries
 * every id its owner looks up, as the type the owner casts it to. The table names every layout in
 * {@code R.layout}, so a new or renamed layout fails here until its owner's expectations are
 * written down.
 */
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class LayoutInflationTest {
    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    /** What the owning code relies on in one inflated layout. */
    private static final class Expectation {
        final String name;
        final Class<? extends View> rootType;
        /** A {@code <merge>} layout is inflated into a parent instead of returning a root. */
        final boolean merge;
        int rootId = View.NO_ID;
        final Map<Integer, Class<? extends View>> ids = new LinkedHashMap<>();

        Expectation(String name, Class<? extends View> rootType, boolean merge) {
            this.name = name;
            this.rootType = rootType;
            this.merge = merge;
        }

        Expectation rootId(int id) {
            rootId = id;
            return this;
        }

        Expectation ids(int... required) {
            for (int id : required) {
                ids.put(id, View.class);
            }
            return this;
        }

        Expectation id(int id, Class<? extends View> type) {
            ids.put(id, type);
            return this;
        }
    }

    private static Map<Integer, Expectation> expectations() {
        Map<Integer, Expectation> table = new LinkedHashMap<>();
        // preferences/AddComputerManually
        put(table, R.layout.activity_add_computer_manually,
                new Expectation("activity_add_computer_manually", LinearLayout.class, false)
                        .ids(R.id.addPcBackButton, R.id.addPcButton, R.id.connectionProgress)
                        .id(R.id.hostTextView, TextView.class)
                        .id(R.id.connectionStageTitle, TextView.class)
                        .id(R.id.connectionStageDetail, TextView.class));
        // AppView
        put(table, R.layout.activity_app_view,
                new Expectation("activity_app_view", LinearLayout.class, false)
                        .ids(R.id.backButton, R.id.appSearch, R.id.settingsButton,
                                R.id.currentSessionPanel, R.id.resumeSessionButton,
                                R.id.endSessionButton, R.id.appFragmentContainer)
                        .id(R.id.appListText, TextView.class)
                        .id(R.id.computerStatusText, TextView.class)
                        .id(R.id.currentSessionAppText, TextView.class));
        // DebugInfoActivity
        put(table, R.layout.activity_axitest,
                new Expectation("activity_axitest", LinearLayout.class, false)
                        .ids(R.id.bt_vibrator, R.id.bt_update_gamepad, R.id.bt_vibrator_gamepad,
                                R.id.bt_vibrator_value, R.id.bt_vibrator_cancle)
                        .id(R.id.tx_content, TextView.class)
                        .id(R.id.tx_game_pad_info, TextView.class));
        // Game: a <merge> under the Activity's content frame.
        put(table, R.layout.activity_game,
                new Expectation("activity_game", FrameLayout.class, true)
                        .ids(R.id.backgroundTouchView, R.id.floatingMenuButton,
                                R.id.overlayToggleZoomButton)
                        .id(R.id.streamContainer, StreamContainer.class)
                        .id(R.id.notificationOverlay, TextView.class));
        // PcView
        put(table, R.layout.activity_pc_view,
                new Expectation("activity_pc_view", LinearLayout.class, false)
                        .ids(R.id.settingsButton, R.id.helpButton, R.id.manuallyAddPc,
                                R.id.homeConnectionStage, R.id.homeConnectionStageProgress,
                                R.id.homeConnectionStageDismiss, R.id.no_pc_found_layout,
                                R.id.pcFragmentContainer)
                        .id(R.id.homeTitle, TextView.class)
                        .id(R.id.homeConnectionStageTitle, TextView.class)
                        .id(R.id.homeConnectionStageDetail, TextView.class)
                        .id(R.id.machineSectionTitle, TextView.class));
        // preferences/StreamSettings
        put(table, R.layout.activity_stream_settings,
                new Expectation("activity_stream_settings", LinearLayout.class, false)
                        .ids(R.id.settingsBackFab, R.id.settingsAttribution,
                                R.id.settingsStreaming, R.id.settingsAudioInput,
                                R.id.settingsGeneral, R.id.settingsDiagnostics,
                                R.id.settings_container));
        // grid/AppGridAdapter through GenericGridAdapter
        put(table, R.layout.app_grid_item,
                new Expectation("app_grid_item", RelativeLayout.class, false)
                        .ids(R.id.grid_primary_action, R.id.grid_quit_button,
                                R.id.grid_more_button)
                        .id(R.id.grid_image, ImageView.class)
                        .id(R.id.grid_mask, RelativeLayout.class)
                        .id(R.id.grid_overlay, ImageView.class)
                        .id(R.id.grid_text, TextView.class)
                        .id(R.id.grid_app_name, TextView.class)
                        .id(R.id.grid_status, TextView.class));
        // ui/AdapterFragment hands R.id.fragmentView to receiveAbsListView.
        put(table, R.layout.app_grid_view,
                new Expectation("app_grid_view", GridView.class, false)
                        .rootId(R.id.fragmentView)
                        .id(R.id.fragmentView, AbsListView.class));
        // KeyBoardLayoutController; its row structure is checked separately below.
        put(table, R.layout.layout_axixi_keyboard,
                new Expectation("layout_axixi_keyboard", LinearLayout.class, false));
        // grid/PcGridAdapter tells the two cards apart by their root ids.
        put(table, R.layout.pc_grid_item,
                new Expectation("pc_grid_item", LinearLayout.class, false)
                        .rootId(R.id.pc_card_standard)
                        .ids(R.id.grid_primary_action, R.id.grid_more_button)
                        .id(R.id.grid_image, ImageView.class)
                        .id(R.id.grid_overlay, ImageView.class)
                        .id(R.id.grid_spinner, ProgressBar.class)
                        .id(R.id.grid_text, TextView.class)
                        .id(R.id.grid_status, TextView.class));
        put(table, R.layout.pc_grid_item_hero,
                new Expectation("pc_grid_item_hero", LinearLayout.class, false)
                        .rootId(R.id.pc_card_hero)
                        .ids(R.id.grid_primary_action, R.id.grid_more_button,
                                R.id.grid_connection_speed)
                        .id(R.id.grid_image, ImageView.class)
                        .id(R.id.grid_overlay, ImageView.class)
                        .id(R.id.grid_spinner, ProgressBar.class)
                        .id(R.id.grid_text, TextView.class)
                        .id(R.id.grid_status, TextView.class)
                        .id(R.id.grid_hint, TextView.class)
                        .id(R.id.grid_address, TextView.class)
                        .id(R.id.grid_download_speed, TextView.class)
                        .id(R.id.grid_upload_speed, TextView.class)
                        .id(R.id.grid_display_fact, TextView.class)
                        .id(R.id.grid_session_fact, TextView.class));
        put(table, R.layout.pc_grid_view,
                new Expectation("pc_grid_view", GridView.class, false)
                        .rootId(R.id.fragmentView)
                        .id(R.id.fragmentView, AbsListView.class));
        // androidx Preference binds title, summary and icon by their framework ids.
        put(table, R.layout.preference_xr_bitrate_ladder,
                preferenceRow("preference_xr_bitrate_ladder")
                        .id(R.id.xr_bitrate_ladder, XrSegmentedLadder.class));
        put(table, R.layout.preference_xr_inline_list,
                preferenceRow("preference_xr_inline_list")
                        .id(R.id.xr_choice_group, XrChoiceGroup.class)
                        .id(R.id.xr_resolution_selector, XrResolutionSelector.class));
        put(table, R.layout.preference_xr_inline_seekbar,
                preferenceRow("preference_xr_inline_seekbar")
                        .id(R.id.xr_seekbar, SeekBar.class)
                        .id(R.id.xr_seekbar_value, TextView.class)
                        .id(R.id.xr_seekbar_decrease, AppCompatButton.class)
                        .id(R.id.xr_seekbar_increase, AppCompatButton.class));
        // preferences/XrChoiceGroup casts each inflated choice to AppCompatButton.
        put(table, R.layout.xr_choice_button,
                new Expectation("xr_choice_button", XrSegmentButton.class, false));
        return table;
    }

    private static Expectation preferenceRow(String name) {
        return new Expectation(name, LinearLayout.class, false)
                .id(android.R.id.title, TextView.class)
                .id(android.R.id.summary, TextView.class)
                .id(android.R.id.icon, ImageView.class);
    }

    private static void put(Map<Integer, Expectation> table, int layoutId,
                            Expectation expectation) {
        if (table.put(layoutId, expectation) != null) {
            throw new AssertionError("Layout listed twice: " + expectation.name);
        }
    }

    @Test
    public void everyLayoutHasWrittenExpectations() throws IllegalAccessException {
        TreeSet<String> missing = new TreeSet<>();
        Map<Integer, Expectation> table = expectations();
        Map<Integer, String> declared = declaredLayouts();
        for (Map.Entry<Integer, String> layout : declared.entrySet()) {
            Expectation expectation = table.get(layout.getKey());
            if (expectation == null) {
                missing.add(layout.getValue());
            } else {
                assertEquals("table name for R.layout." + layout.getValue(),
                        layout.getValue(), expectation.name);
            }
        }
        assertTrue("Layouts without expectations: " + missing, missing.isEmpty());
        assertEquals(declared.size(), table.size());
    }

    @Test
    public void everyLayoutInflatesIntoItsOwnersRootTypeWithItsOwnersIds() {
        Context context = themedContext();
        for (Map.Entry<Integer, Expectation> entry : expectations().entrySet()) {
            Expectation expected = entry.getValue();
            View root;
            if (expected.merge) {
                FrameLayout parent = new FrameLayout(context);
                View returned = LayoutInflater.from(context)
                        .inflate(entry.getKey(), parent, true);
                assertSame(expected.name + " returns its parent", parent, returned);
                assertTrue(expected.name + " adds children", parent.getChildCount() > 0);
                root = parent;
            } else {
                root = LayoutInflater.from(context).inflate(entry.getKey(), null);
            }
            assertEquals(expected.name + " root type", expected.rootType, root.getClass());
            if (expected.rootId != View.NO_ID) {
                assertEquals(expected.name + " root id", expected.rootId, root.getId());
            }
            for (Map.Entry<Integer, Class<? extends View>> id : expected.ids.entrySet()) {
                String idName = context.getResources().getResourceEntryName(id.getKey());
                View child = root.findViewById(id.getKey());
                assertNotNull(expected.name + " is missing @id/" + idName, child);
                assertTrue(expected.name + " @id/" + idName + " is a "
                                + child.getClass().getName() + ", not a "
                                + id.getValue().getName(),
                        id.getValue().isInstance(child));
            }
        }
    }

    @Test
    public void keyboardRowsCarryOnlyKeyTagsTheControllerCanParse() {
        // KeyBoardLayoutController casts every child of the root to a LinearLayout row and every
        // child of a row to a key whose tag is "hide" or an Android key code.
        ViewGroup keyboard = (ViewGroup) LayoutInflater.from(themedContext())
                .inflate(R.layout.layout_axixi_keyboard, null);
        assertTrue(keyboard.getChildCount() > 0);
        List<String> unparsable = new ArrayList<>();
        int keys = 0;
        for (int row = 0; row < keyboard.getChildCount(); row++) {
            View rowView = keyboard.getChildAt(row);
            assertTrue("keyboard child " + row + " is a " + rowView.getClass().getName(),
                    rowView instanceof LinearLayout);
            LinearLayout keyRow = (LinearLayout) rowView;
            assertTrue("keyboard row " + row + " is empty", keyRow.getChildCount() > 0);
            for (int index = 0; index < keyRow.getChildCount(); index++) {
                Object tag = keyRow.getChildAt(index).getTag();
                keys++;
                if (!(tag instanceof String)) {
                    unparsable.add(row + "/" + index + "=" + tag);
                    continue;
                }
                if ("hide".equals(tag)) {
                    continue;
                }
                try {
                    Integer.parseInt((String) tag);
                } catch (NumberFormatException e) {
                    unparsable.add(row + "/" + index + "=" + tag);
                }
            }
        }
        assertTrue("Keys the controller cannot parse: " + unparsable, unparsable.isEmpty());
        assertTrue(keys > 0);
    }

    private static Context themedContext() {
        Context base = ApplicationProvider.getApplicationContext();
        return new androidx.appcompat.view.ContextThemeWrapper(base, R.style.AppTheme);
    }

    private static Map<Integer, String> declaredLayouts() throws IllegalAccessException {
        Map<Integer, String> layouts = new LinkedHashMap<>();
        for (Field field : R.layout.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class) {
                layouts.put(field.getInt(null), field.getName());
            }
        }
        return layouts;
    }
}
