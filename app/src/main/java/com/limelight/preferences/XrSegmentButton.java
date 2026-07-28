package com.limelight.preferences;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatButton;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.limelight.R;

/**
 * Ordinary Android button used inside {@link XrChoiceGroup}'s connected segmented surface.
 * The parent owns the row background so adjoining segments have no doubled rounded corners.
 */
public final class XrSegmentButton extends AppCompatButton {
    /** XR gaze targets need more vertical breathing room than the compact base button style. */
    static final int MIN_HEIGHT_DIMEN = R.dimen.xr_control_choice;

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
        ViewCompat.setBackgroundTintList(this, null);
        setStateListAnimator(null);
        setElevation(0f);
        setTextColor(segmentTextColors(context));
        setMinimumHeight(Math.max(getMinimumHeight(),
                getResources().getDimensionPixelSize(MIN_HEIGHT_DIMEN)));
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

    private static ColorStateList segmentTextColors(Context context) {
        return new ColorStateList(new int[][] {
                new int[] {-android.R.attr.state_enabled},
                new int[] {android.R.attr.state_activated},
                new int[] {android.R.attr.state_focused},
                new int[] {android.R.attr.state_hovered},
                new int[0],
        }, new int[] {
                ContextCompat.getColor(context, R.color.xr_text_disabled),
                ContextCompat.getColor(context, R.color.xr_text_primary),
                ContextCompat.getColor(context, R.color.xr_text_primary),
                ContextCompat.getColor(context, R.color.xr_text_primary),
                ContextCompat.getColor(context, R.color.xr_accent_bright),
        });
    }
}
