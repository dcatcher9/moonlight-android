package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import android.app.Activity;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.xr.scenecore.PanelEntity;

import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.xrcontrols.XrControlUiState;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
@LooperMode(LooperMode.Mode.PAUSED)
public class XrStreamPresenterStatsRefreshTest {
    private ActivityController<Activity> controller;
    private XrStreamPresenter presenter;
    private TableLayout table;
    private QueuedStatsRoot root;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.create();
        presenter = new XrStreamPresenter(activity,
                PreferenceConfiguration.readPreferences(activity), surface -> { }, visible -> { });
        table = new TableLayout(activity);
        root = new QueuedStatsRoot(activity);
        ReflectionHelpers.setField(presenter, "statsTable", table);
        ReflectionHelpers.setField(presenter, "statsContentRoot", root);
        ReflectionHelpers.setField(presenter, "statsPanel", mock(PanelEntity.class));
        ReflectionHelpers.setField(presenter, "statsVisible", true);
        XrControlUiState state = ReflectionHelpers.getField(presenter, "controlUiState");
        if (!state.isStatsVisible()) {
            state.toggleStats();
        }
    }

    @After
    public void tearDown() {
        presenter.onDestroy();
        controller.destroy();
    }

    @Test
    public void repeatedRowsKeepTextAndOnlyChangedValuesNotifyTheView() {
        renderRows("6.20 ms");
        TableRow section = (TableRow) table.getChildAt(0);
        TableRow metric = (TableRow) table.getChildAt(1);
        ChangeCounter headingChanges = watch((TextView) section.getChildAt(0));
        ChangeCounter labelChanges = watch((TextView) metric.getChildAt(0));
        ChangeCounter valueChanges = watch((TextView) metric.getChildAt(1));

        for (int i = 0; i < 3; i++) {
            renderRows(new String("6.20 ms"));
        }
        assertSame(section, table.getChildAt(0));
        assertSame(metric, table.getChildAt(1));
        assertEquals(0, headingChanges.changes);
        assertEquals(0, labelChanges.changes);
        assertEquals(0, valueChanges.changes);

        renderRows("5.90 ms");
        assertEquals(0, headingChanges.changes);
        assertEquals(0, labelChanges.changes);
        assertEquals(1, valueChanges.changes);
        assertEquals("5.90 ms", ((TextView) metric.getChildAt(1)).getText().toString());
    }

    @Test
    public void pendingRefreshesShareOneFitAndUseTheLatestContent() {
        scheduleFit();
        Runnable retainedFit = root.pending.get(0);
        scheduleFit();
        scheduleFit();
        assertEquals(1, root.pending.size());

        root.contentHeight = 900;
        root.runNext();
        assertEquals(1, root.measurements);
        assertEquals(900, root.getMeasuredHeight());
        assertFalse(ReflectionHelpers.getField(presenter, "statsPanelFitScheduled"));

        root.contentHeight = 1100;
        root.requestLayout();
        scheduleFit();
        assertSame(retainedFit, root.pending.get(0));
        root.runNext();
        assertEquals(2, root.measurements);
        assertEquals(1100, root.getMeasuredHeight());
    }

    @Test
    public void hidingCancelsFitAndReopeningCanScheduleAgain() {
        scheduleFit();
        Runnable staleFit = root.pending.get(0);
        presenter.toggleStats();
        assertFalse(presenter.isStatsVisible());
        assertTrue(root.pending.isEmpty());
        staleFit.run();
        scheduleFit();
        assertEquals(0, root.measurements);
        assertTrue(root.pending.isEmpty());

        presenter.toggleStats();
        assertTrue(presenter.isStatsVisible());
        scheduleFit();
        assertEquals(1, root.pending.size());
        root.runNext();
        assertEquals(1, root.measurements);
    }

    @Test
    public void destroyCancelsFitBeforeReleasingItsContentRoot() {
        scheduleFit();
        Runnable staleFit = root.pending.get(0);
        presenter.onDestroy();
        assertTrue(root.pending.isEmpty());
        staleFit.run();
        assertEquals(0, root.measurements);
        assertFalse(ReflectionHelpers.getField(presenter, "statsPanelFitScheduled"));
    }

    private void renderRows(String value) {
        ReflectionHelpers.callInstanceMethod(presenter, "beginStatsRows");
        ReflectionHelpers.callInstanceMethod(presenter, "addStatsSection",
                ClassParameter.from(String.class, new String("STREAM")));
        ReflectionHelpers.callInstanceMethod(presenter, "addStatsRow",
                ClassParameter.from(String.class, new String("Decode")),
                ClassParameter.from(String.class, value),
                ClassParameter.from(int.class, 0xFFFFFFFF));
        ReflectionHelpers.callInstanceMethod(presenter, "finishStatsRows");
    }

    private void scheduleFit() {
        ReflectionHelpers.callInstanceMethod(presenter, "scheduleStatsPanelFit");
    }

    private static ChangeCounter watch(TextView view) {
        ChangeCounter counter = new ChangeCounter();
        view.addTextChangedListener(counter);
        return counter;
    }

    private static final class ChangeCounter implements TextWatcher {
        int changes;

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            changes++;
        }

        @Override
        public void afterTextChanged(Editable s) { }
    }

    /** Captures only this root's posted fits while keeping production TextViews and measurement. */
    private static final class QueuedStatsRoot extends LinearLayout {
        final List<Runnable> pending = new ArrayList<>();
        int contentHeight = 900;
        int measurements;

        QueuedStatsRoot(Context context) {
            super(context);
        }

        @Override
        public boolean post(Runnable action) {
            pending.add(action);
            return true;
        }

        @Override
        public boolean removeCallbacks(Runnable action) {
            return pending.remove(action);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            measurements++;
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), contentHeight);
        }

        void runNext() {
            pending.remove(0).run();
        }
    }
}
