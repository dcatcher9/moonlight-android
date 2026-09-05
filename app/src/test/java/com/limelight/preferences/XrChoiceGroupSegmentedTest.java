package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CompoundButton;
import android.widget.FrameLayout;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.appcompat.widget.AppCompatButton;
import androidx.core.view.ViewCompat;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35}, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class XrChoiceGroupSegmentedTest {
    private Context context;

    @Before
    public void setUp() {
        Context application = ApplicationProvider.getApplicationContext();
        // The production app currently defaults to LTR, but the widget itself deliberately uses
        // physical outer edges. Enable platform RTL resolution here so that path is testable.
        application.getApplicationInfo().flags |= ApplicationInfo.FLAG_SUPPORTS_RTL;
        context = new ContextThemeWrapper(application, R.style.AppTheme);
    }

    @Test
    public void wideChoicesUseEqualWidthEdgeToEdgeHorizontalSegments() {
        XrChoiceGroup group = createThreeChoiceGroup();

        measureAndLayout(group, dp(480));

        assertFalse(group.isStackedForTests());
        assertEquals(dp(480), group.getMeasuredWidth());
        AppCompatButton first = group.getButtonAt(0);
        AppCompatButton middle = group.getButtonAt(1);
        AppCompatButton last = group.getButtonAt(2);
        assertEquals(group.getPaddingLeft(), first.getLeft());
        assertEquals(first.getRight(), middle.getLeft());
        assertEquals(middle.getRight(), last.getLeft());
        assertEquals(group.getMeasuredWidth() - group.getPaddingRight(), last.getRight());
        assertEquals(first.getTop(), middle.getTop());
        assertEquals(first.getBottom(), middle.getBottom());
        assertEquals(first.getTop(), last.getTop());
        assertEquals(first.getBottom(), last.getBottom());
        assertTrue(Math.abs(first.getWidth() - middle.getWidth()) <= 1);
        assertTrue(Math.abs(middle.getWidth() - last.getWidth()) <= 1);
        assertEquals(XrChoiceGroup.CORNER_LEFT, group.segmentCornerMaskForTests(0));
        assertEquals(0, group.segmentCornerMaskForTests(1));
        assertEquals(XrChoiceGroup.CORNER_RIGHT, group.segmentCornerMaskForTests(2));
    }

    @Test
    public void longSegmentLabelsKeepReadableMinimumHeight() {
        XrChoiceGroup group = new XrChoiceGroup(context);
        group.setChoices(new CharSequence[] {"Maximum quality", "Balanced latency"},
                new CharSequence[] {"quality", "balanced"},
                "quality", null, value -> true);

        measureAndLayout(group, dp(420));

        assertFalse(group.isStackedForTests());
        int minimumHeight = context.getResources()
                .getDimensionPixelSize(XrSegmentButton.MIN_HEIGHT_DIMEN);
        for (int i = 0; i < group.getChildCount(); i++) {
            AppCompatButton button = group.getButtonAt(i);
            assertTrue(button.getMinimumHeight() >= minimumHeight);
            assertTrue(button.getMeasuredHeight() >= minimumHeight);
        }
        assertEquals(group.getButtonAt(0).getMeasuredHeight(),
                group.getButtonAt(1).getMeasuredHeight());
    }

    @Test
    public void narrowChoicesUseOneFullWidthConnectedVerticalStack() {
        XrChoiceGroup group = createThreeChoiceGroup();
        for (int i = 0; i < group.getChildCount(); i++) {
            group.getChildAt(i).getLayoutParams().width = dp(120);
        }

        measureAndLayout(group, dp(240));

        assertTrue(group.isStackedForTests());
        AppCompatButton first = group.getButtonAt(0);
        AppCompatButton middle = group.getButtonAt(1);
        AppCompatButton last = group.getButtonAt(2);
        assertEquals(group.getPaddingLeft(), first.getLeft());
        assertEquals(first.getLeft(), middle.getLeft());
        assertEquals(first.getLeft(), last.getLeft());
        assertEquals(group.getMeasuredWidth() - group.getPaddingRight(), first.getRight());
        assertEquals(first.getRight(), middle.getRight());
        assertEquals(first.getRight(), last.getRight());
        assertEquals(first.getBottom(), middle.getTop());
        assertEquals(middle.getBottom(), last.getTop());
        assertEquals(XrChoiceGroup.CORNER_TOP, group.segmentCornerMaskForTests(0));
        assertEquals(0, group.segmentCornerMaskForTests(1));
        assertEquals(XrChoiceGroup.CORNER_BOTTOM, group.segmentCornerMaskForTests(2));
    }

    @Test
    public void rtlUsesPhysicalOuterCornersAndKeepsSegmentsConnected() {
        XrChoiceGroup group = createThreeChoiceGroup();
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(context);
        activity.setContentView(root);
        root.addView(group);
        ViewCompat.setLayoutDirection(group, ViewCompat.LAYOUT_DIRECTION_RTL);

        measureAndLayout(group, dp(481));

        assertEquals(ViewCompat.LAYOUT_DIRECTION_RTL, ViewCompat.getLayoutDirection(group));
        assertFalse(group.isStackedForTests());
        AppCompatButton logicalFirst = group.getButtonAt(0);
        AppCompatButton logicalMiddle = group.getButtonAt(1);
        AppCompatButton logicalLast = group.getButtonAt(2);
        assertTrue(logicalFirst.getLeft() > logicalMiddle.getLeft());
        assertTrue(logicalMiddle.getLeft() > logicalLast.getLeft());
        assertEquals(logicalLast.getRight(), logicalMiddle.getLeft());
        assertEquals(logicalMiddle.getRight(), logicalFirst.getLeft());
        assertEquals(group.getPaddingLeft(), logicalLast.getLeft());
        assertEquals(group.getMeasuredWidth() - group.getPaddingRight(), logicalFirst.getRight());
        assertEquals(XrChoiceGroup.CORNER_RIGHT, group.segmentCornerMaskForTests(0));
        assertEquals(0, group.segmentCornerMaskForTests(1));
        assertEquals(XrChoiceGroup.CORNER_LEFT, group.segmentCornerMaskForTests(2));
    }

    @Test
    public void selectionUpdatePreservesSegmentInstancesAndInteractionState() {
        XrChoiceGroup group = createThreeChoiceGroup();
        AppCompatButton first = group.getButtonAt(0);
        AppCompatButton second = group.getButtonAt(1);
        AppCompatButton third = group.getButtonAt(2);

        assertTrue(first.isActivated());
        assertTrue(first.isSelected());
        assertEquals(Color.TRANSPARENT, group.segmentFillColorForTests(1));
        second.setHovered(true);
        assertTrue(group.setSelectedValue("balanced"));

        assertSame(first, group.getButtonAt(0));
        assertSame(second, group.getButtonAt(1));
        assertSame(third, group.getButtonAt(2));
        assertTrue(second.isHovered());
        assertFalse(first.isActivated());
        assertFalse(first.isSelected());
        assertTrue(second.isActivated());
        assertTrue(second.isSelected());
        assertNotEquals(Color.TRANSPARENT, group.segmentFillColorForTests(1));

        measureAndLayout(group, dp(180));
        assertTrue(group.isStackedForTests());
        assertSame(first, group.getButtonAt(0));
        assertSame(second, group.getButtonAt(1));
        assertTrue(second.isHovered());
        assertTrue(second.isSelected());
    }

    @Test
    public void choicesAreOrdinaryButtonsWithoutRadioOrCompoundButtonSemantics() {
        XrChoiceGroup group = createThreeChoiceGroup();

        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            assertTrue(child instanceof XrSegmentButton);
            assertTrue(child instanceof AppCompatButton);
            assertFalse(CompoundButton.class.isInstance(child));
        }
    }

    @Test
    public void exposesOneSingleSelectAccessibilityCollection() {
        XrChoiceGroup group = createThreeChoiceGroup();
        measureAndLayout(group, dp(480));

        AccessibilityNodeInfo groupInfo = AccessibilityNodeInfo.obtain();
        group.onInitializeAccessibilityNodeInfo(groupInfo);
        AccessibilityNodeInfo.CollectionInfo collection = groupInfo.getCollectionInfo();
        assertEquals(1, collection.getRowCount());
        assertEquals(3, collection.getColumnCount());
        assertEquals(AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_SINGLE,
                collection.getSelectionMode());

        AccessibilityNodeInfo childInfo = AccessibilityNodeInfo.obtain();
        group.getButtonAt(1).onInitializeAccessibilityNodeInfo(childInfo);
        AccessibilityNodeInfo.CollectionItemInfo item = childInfo.getCollectionItemInfo();
        assertEquals(0, item.getRowIndex());
        assertEquals(1, item.getColumnIndex());
        assertFalse(item.isSelected());

        groupInfo.recycle();
        childInfo.recycle();
    }

    private XrChoiceGroup createThreeChoiceGroup() {
        XrChoiceGroup group = new XrChoiceGroup(context);
        group.setChoices(new CharSequence[] {"Fast", "Balanced", "Quality"},
                new CharSequence[] {"fast", "balanced", "quality"},
                "fast", null, value -> true);
        return group;
    }

    private void measureAndLayout(XrChoiceGroup group, int width) {
        group.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        group.layout(0, 0, group.getMeasuredWidth(), group.getMeasuredHeight());
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
