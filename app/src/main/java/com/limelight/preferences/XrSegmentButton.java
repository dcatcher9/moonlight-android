package com.limelight.preferences;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatButton;
import androidx.core.view.ViewCompat;

/**
 * Ordinary Android button used inside {@link XrChoiceGroup}'s connected segmented surface.
 * The parent owns the row background so adjoining segments have no doubled rounded corners.
 */
public final class XrSegmentButton extends AppCompatButton {
    /** XR gaze targets need more vertical breathing room than the compact base button style. */
    static final int MIN_HEIGHT_DP = 80;

    public XrSegmentButton(@NonNull Context context) {
        this(context, null);
    }

    public XrSegmentButton(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, androidx.appcompat.R.attr.buttonStyle);
    }

    public XrSegmentButton(@NonNull Context context, @Nullable AttributeSet attrs,
                           int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setBackgroundColor(Color.TRANSPARENT);
        setSupportBackgroundTintList(null);
        ViewCompat.setBackgroundTintList(this, null);
        setStateListAnimator(null);
        setElevation(0f);
        setTextColor(segmentTextColors());
        setMinimumHeight(Math.max(getMinimumHeight(), dp(MIN_HEIGHT_DP)));
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        if (getParent() instanceof android.view.View) {
            ((android.view.View) getParent()).invalidate();
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (getParent() instanceof XrChoiceGroup) {
            XrChoiceGroup group = (XrChoiceGroup) getParent();
            info.setCollectionItemInfo(AccessibilityNodeInfo.CollectionItemInfo.obtain(
                    group.accessibilityRowForChild(this), 1,
                    group.accessibilityColumnForChild(this), 1,
                    false, isSelected()));
        }
    }

    private static ColorStateList segmentTextColors() {
        return new ColorStateList(new int[][] {
                new int[] {-android.R.attr.state_enabled},
                new int[] {android.R.attr.state_activated},
                new int[] {android.R.attr.state_focused},
                new int[] {android.R.attr.state_hovered},
                new int[0],
        }, new int[] {
                Color.rgb(128, 134, 139),
                Color.WHITE,
                Color.WHITE,
                Color.WHITE,
                Color.rgb(214, 229, 245),
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
