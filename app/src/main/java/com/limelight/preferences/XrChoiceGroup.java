package com.limelight.preferences;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatButton;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;

import com.limelight.R;

/**
 * A connected segmented group of ordinary Android buttons for XR preference choices.
 *
 * The group deliberately avoids a scrolling container. Compact choices share one horizontal
 * rounded surface; when their intrinsic labels no longer fit, the same segments become one
 * full-width vertical stack instead of producing a ragged wrap or horizontal scrolling.
 */
public final class XrChoiceGroup extends ViewGroup {
    public interface OnChoiceSelectedListener {
        /** Returns true when the choice was accepted. */
        boolean onChoiceSelected(@NonNull String value);
    }

    static final int CORNER_LEFT = 1;
    static final int CORNER_TOP = 1 << 1;
    static final int CORNER_RIGHT = 1 << 2;
    static final int CORNER_BOTTOM = 1 << 3;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path segmentPath = new Path();
    private final RectF groupBounds = new RectF();
    private final RectF segmentBounds = new RectF();
    private final RectF outlineBounds = new RectF();
    private final float[] cornerRadii = new float[8];
    private final float cornerRadius;
    private final float borderWidth;
    private final float focusWidth;
    private final int baseColor;
    private final int disabledColor;
    private final int disabledSelectedColor;
    private final int selectedColor;
    private final int selectedFocusColor;
    private final int hoverColor;
    private final int pressedColor;
    private final int borderColor;
    private final int dividerColor;
    private final int focusColor;
    private String selectedValue;
    private OnChoiceSelectedListener listener;
    private boolean stacked;

    public XrChoiceGroup(@NonNull Context context) {
        this(context, null);
    }

    public XrChoiceGroup(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public XrChoiceGroup(@NonNull Context context, @Nullable AttributeSet attrs,
                         int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        cornerRadius = getResources().getDimension(R.dimen.xr_radius_card);
        borderWidth = dp(1);
        focusWidth = dp(3);
        baseColor = color(R.color.xr_surface_raised);
        disabledColor = color(R.color.xr_segment_disabled);
        disabledSelectedColor = color(R.color.xr_border_panel);
        selectedColor = color(R.color.xr_accent_deep);
        // Resolve the translucent interaction roles once. Drawing stays allocation-free, while
        // hover, press and selected-focus remain visibly distinct without inventing new colours.
        selectedFocusColor = ColorUtils.compositeColors(
                color(R.color.xr_accent_focus_overlay), selectedColor);
        hoverColor = ColorUtils.compositeColors(
                color(R.color.xr_accent_focus_overlay), baseColor);
        pressedColor = ColorUtils.compositeColors(
                color(R.color.xr_accent_pressed_overlay), selectedColor);
        borderColor = color(R.color.xr_border);
        dividerColor = color(R.color.xr_border);
        focusColor = color(R.color.xr_accent_bright);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setWillNotDraw(false);
        setFocusable(false);
        setClipChildren(false);
        setClipToPadding(false);
    }

    public void setChoices(@Nullable CharSequence[] entries,
                           @Nullable CharSequence[] entryValues,
                           @Nullable String selectedValue,
                           @Nullable CharSequence customEntry,
                           @Nullable OnChoiceSelectedListener listener) {
        removeAllViews();
        this.selectedValue = selectedValue;
        this.listener = listener;

        int count = entries == null || entryValues == null
                ? 0 : Math.min(entries.length, entryValues.length);
        boolean selectedValueIsKnown = false;
        for (int i = 0; i < count; i++) {
            if (entryValues[i] != null && entryValues[i].toString().equals(selectedValue)) {
                selectedValueIsKnown = true;
                break;
            }
        }

        if (selectedValue != null && !selectedValueIsKnown && customEntry != null) {
            addChoice(customEntry, selectedValue, false);
        }
        for (int i = 0; i < count; i++) {
            CharSequence entry = entries[i];
            CharSequence value = entryValues[i];
            if (entry != null && value != null) {
                addChoice(entry, value.toString(), true);
            }
        }

        updateSelection();
        requestLayout();
    }

    @Nullable
    public String getSelectedValue() {
        return selectedValue;
    }

    /** Updates only the visible selection, preserving the existing buttons and gaze focus. */
    public boolean setSelectedValue(@Nullable String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < getChildCount(); i++) {
            Object tag = getChildAt(i).getTag();
            if (tag != null && value.equals(tag.toString())) {
                selectedValue = value;
                updateSelection();
                return true;
            }
        }
        return false;
    }

    @NonNull
    public AppCompatButton getButtonAt(int index) {
        return (AppCompatButton) getChildAt(index);
    }

    private void addChoice(CharSequence entry, String value, boolean selectable) {
        AppCompatButton button = (AppCompatButton) LayoutInflater.from(getContext())
                .inflate(R.layout.xr_choice_button, this, false);
        button.setText(entry);
        button.setTag(value);
        button.setEnabled(isEnabled());
        if (selectable) {
            button.setOnClickListener(view -> selectChoice((String) view.getTag()));
        }
        else {
            // The temporary custom choice describes an existing value. It must remain visible and
            // selected without turning a harmless gaze tap into a preference rewrite.
            button.setClickable(false);
            button.setFocusable(false);
        }
        addView(button);
    }

    private void selectChoice(String value) {
        if (value.equals(selectedValue)) {
            return;
        }
        if (listener == null || listener.onChoiceSelected(value)) {
            selectedValue = value;
            updateSelection();
        }
    }

    private void updateSelection() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            boolean active = child.getTag() != null
                    && child.getTag().toString().equals(selectedValue);
            child.setActivated(active);
            child.setSelected(active);
        }
        invalidate();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).setEnabled(enabled);
        }
        invalidate();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        int count = getVisibleChildCount();
        if (count == 0) {
            return;
        }
        info.setCollectionInfo(AccessibilityNodeInfo.CollectionInfo.obtain(
                stacked ? count : 1,
                stacked ? 1 : count,
                false,
                AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_SINGLE));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int availableWidth = widthMode == MeasureSpec.UNSPECIFIED
                ? Integer.MAX_VALUE
                : Math.max(0, widthSize - getPaddingLeft() - getPaddingRight());
        int maxNaturalWidth = 0;
        int visibleCount = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            if (child instanceof AppCompatButton) {
                ((AppCompatButton) child).setMaxLines(1);
            }
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);
            MarginLayoutParams params = (MarginLayoutParams) child.getLayoutParams();
            int naturalWidth = child.getMeasuredWidth()
                    + params.leftMargin + params.rightMargin;
            maxNaturalWidth = Math.max(maxNaturalWidth, naturalWidth);
            visibleCount++;
        }

        stacked = visibleCount > 1 && availableWidth != Integer.MAX_VALUE
                && (long) maxNaturalWidth * visibleCount > availableWidth;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof AppCompatButton) {
                ((AppCompatButton) child).setMaxLines(stacked ? 2 : 1);
            }
        }
        int contentWidth;
        int contentHeight = 0;
        if (stacked) {
            contentWidth = availableWidth == Integer.MAX_VALUE
                    ? maxNaturalWidth : availableWidth;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) {
                    continue;
                }
                MarginLayoutParams params = (MarginLayoutParams) child.getLayoutParams();
                int childWidth = Math.max(0,
                        contentWidth - params.leftMargin - params.rightMargin);
                child.measure(MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                        childHeightMeasureSpec(params, heightMeasureSpec));
                contentHeight += child.getMeasuredHeight()
                        + params.topMargin + params.bottomMargin;
            }
        }
        else {
            contentWidth = widthMode == MeasureSpec.EXACTLY
                    ? availableWidth : maxNaturalWidth * visibleCount;
            int baseWidth = visibleCount == 0 ? 0 : contentWidth / visibleCount;
            int remainder = visibleCount == 0 ? 0 : contentWidth % visibleCount;
            int assignedTotal = 0;
            int maxOccupiedHeight = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) {
                    continue;
                }
                MarginLayoutParams params = (MarginLayoutParams) child.getLayoutParams();
                int occupiedWidth = baseWidth + (remainder-- > 0 ? 1 : 0);
                int childWidth = Math.max(0,
                        occupiedWidth - params.leftMargin - params.rightMargin);
                child.measure(MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                        childHeightMeasureSpec(params, heightMeasureSpec));
                assignedTotal += child.getMeasuredWidth()
                        + params.leftMargin + params.rightMargin;
                maxOccupiedHeight = Math.max(maxOccupiedHeight, child.getMeasuredHeight()
                        + params.topMargin + params.bottomMargin);
            }
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) {
                    continue;
                }
                MarginLayoutParams params = (MarginLayoutParams) child.getLayoutParams();
                int normalizedHeight = Math.max(0,
                        maxOccupiedHeight - params.topMargin - params.bottomMargin);
                child.measure(MeasureSpec.makeMeasureSpec(
                                child.getMeasuredWidth(), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(normalizedHeight, MeasureSpec.EXACTLY));
            }
            contentHeight = maxOccupiedHeight;
            contentWidth = assignedTotal;
        }

        int desiredWidth = contentWidth + getPaddingLeft() + getPaddingRight();
        int desiredHeight = contentHeight + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(resolveSize(Math.max(desiredWidth, getSuggestedMinimumWidth()),
                        widthMeasureSpec),
                resolveSize(Math.max(desiredHeight, getSuggestedMinimumHeight()),
                        heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        boolean rtl = ViewCompat.getLayoutDirection(this) == ViewCompat.LAYOUT_DIRECTION_RTL;
        int contentLeft = getPaddingLeft();
        int contentRight = right - left - getPaddingRight();
        int y = getPaddingTop();
        int x = rtl ? contentRight : contentLeft;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            MarginLayoutParams params = (MarginLayoutParams) child.getLayoutParams();
            int childWidth = child.getMeasuredWidth();
            int childHeight = child.getMeasuredHeight();
            if (stacked) {
                int childLeft = contentLeft + params.leftMargin;
                int childTop = y + params.topMargin;
                child.layout(childLeft, childTop,
                        childLeft + childWidth, childTop + childHeight);
                y = childTop + childHeight + params.bottomMargin;
                continue;
            }
            int childLeft;
            if (rtl) {
                childLeft = x - params.rightMargin - childWidth;
                x = childLeft - params.leftMargin;
            }
            else {
                childLeft = x + params.leftMargin;
                x = childLeft + childWidth + params.rightMargin;
            }
            int childTop = y + params.topMargin;
            child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!resolveGroupBounds()) {
            return;
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(isEnabled() ? baseColor : disabledColor);
        canvas.drawRoundRect(groupBounds, cornerRadius, cornerRadius, paint);

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            int fill = segmentFillColor(child);
            if (fill != Color.TRANSPARENT) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(fill);
                setSegmentBounds(child);
                buildSegmentPath(segmentBounds, segmentCornerMask(i), 0f);
                canvas.drawPath(segmentPath, paint);
            }
        }

        drawDividers(canvas);

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE || (!child.isFocused() && !child.isHovered())) {
                continue;
            }
            setSegmentBounds(child);
            segmentBounds.inset(focusWidth / 2f, focusWidth / 2f);
            buildSegmentPath(segmentBounds, segmentCornerMask(i), focusWidth / 2f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(focusWidth);
            paint.setColor(focusColor);
            canvas.drawPath(segmentPath, paint);
        }

        outlineBounds.set(groupBounds);
        outlineBounds.inset(borderWidth / 2f, borderWidth / 2f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(borderWidth);
        paint.setColor(borderColor);
        canvas.drawRoundRect(outlineBounds, cornerRadius, cornerRadius, paint);
    }

    private int childHeightMeasureSpec(MarginLayoutParams params, int parentSpec) {
        return getChildMeasureSpec(parentSpec,
                getPaddingTop() + getPaddingBottom()
                        + params.topMargin + params.bottomMargin,
                params.height);
    }

    private boolean resolveGroupBounds() {
        boolean found = false;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            if (!found) {
                groupBounds.set(child.getLeft(), child.getTop(),
                        child.getRight(), child.getBottom());
                found = true;
            }
            else {
                groupBounds.union(child.getLeft(), child.getTop(),
                        child.getRight(), child.getBottom());
            }
        }
        return found;
    }

    private void setSegmentBounds(View child) {
        segmentBounds.set(child.getLeft(), child.getTop(), child.getRight(), child.getBottom());
    }

    private int segmentFillColor(View child) {
        if (!child.isEnabled()) {
            return child.isActivated() ? disabledSelectedColor : Color.TRANSPARENT;
        }
        if (child.isPressed()) {
            return pressedColor;
        }
        if (child.isActivated() && (child.isFocused() || child.isHovered())) {
            return selectedFocusColor;
        }
        if (child.isActivated()) {
            return selectedColor;
        }
        if (child.isFocused() || child.isHovered()) {
            return hoverColor;
        }
        return Color.TRANSPARENT;
    }

    private void drawDividers(Canvas canvas) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(borderWidth);
        paint.setColor(dividerColor);
        float inset = cornerRadius * 0.48f;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            if (stacked) {
                if (child.getTop() > groupBounds.top) {
                    canvas.drawLine(groupBounds.left + inset, child.getTop(),
                            groupBounds.right - inset, child.getTop(), paint);
                }
            }
            else if (child.getLeft() > groupBounds.left) {
                canvas.drawLine(child.getLeft(), groupBounds.top + inset,
                        child.getLeft(), groupBounds.bottom - inset, paint);
            }
        }
    }

    private void buildSegmentPath(RectF bounds, int cornerMask, float radiusInset) {
        float radius = Math.max(0f, cornerRadius - radiusInset);
        float topLeft = (cornerMask & (CORNER_LEFT | CORNER_TOP)) != 0 ? radius : 0f;
        float topRight = (cornerMask & (CORNER_RIGHT | CORNER_TOP)) != 0 ? radius : 0f;
        float bottomRight = (cornerMask & (CORNER_RIGHT | CORNER_BOTTOM)) != 0 ? radius : 0f;
        float bottomLeft = (cornerMask & (CORNER_LEFT | CORNER_BOTTOM)) != 0 ? radius : 0f;
        cornerRadii[0] = topLeft;
        cornerRadii[1] = topLeft;
        cornerRadii[2] = topRight;
        cornerRadii[3] = topRight;
        cornerRadii[4] = bottomRight;
        cornerRadii[5] = bottomRight;
        cornerRadii[6] = bottomLeft;
        cornerRadii[7] = bottomLeft;
        segmentPath.reset();
        segmentPath.addRoundRect(bounds, cornerRadii, Path.Direction.CW);
    }

    boolean isStackedForTests() {
        return stacked;
    }

    int segmentCornerMaskForTests(int childIndex) {
        return segmentCornerMask(childIndex);
    }

    int segmentFillColorForTests(int childIndex) {
        return segmentFillColor(getChildAt(childIndex));
    }

    int accessibilityRowForChild(View child) {
        return stacked ? visibleIndexOf(child) : 0;
    }

    int accessibilityColumnForChild(View child) {
        return stacked ? 0 : visibleIndexOf(child);
    }

    private int getVisibleChildCount() {
        int count = 0;
        for (int i = 0; i < getChildCount(); i++) {
            if (getChildAt(i).getVisibility() != GONE) {
                count++;
            }
        }
        return count;
    }

    private int visibleIndexOf(View target) {
        int visibleIndex = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            if (child == target) {
                return visibleIndex;
            }
            visibleIndex++;
        }
        return 0;
    }

    private int segmentCornerMask(int childIndex) {
        if (childIndex < 0 || childIndex >= getChildCount()
                || getChildAt(childIndex).getVisibility() == GONE || !resolveGroupBounds()) {
            return 0;
        }
        View child = getChildAt(childIndex);
        int mask = 0;
        if (stacked) {
            if (child.getTop() == (int) groupBounds.top) {
                mask |= CORNER_TOP;
            }
            if (child.getBottom() == (int) groupBounds.bottom) {
                mask |= CORNER_BOTTOM;
            }
        }
        else {
            if (child.getLeft() == (int) groupBounds.left) {
                mask |= CORNER_LEFT;
            }
            if (child.getRight() == (int) groupBounds.right) {
                mask |= CORNER_RIGHT;
            }
        }
        return mask;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private int color(int resourceId) {
        return ContextCompat.getColor(getContext(), resourceId);
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new MarginLayoutParams(getContext(), attrs);
    }

    @Override
    protected LayoutParams generateLayoutParams(LayoutParams params) {
        return new MarginLayoutParams(params);
    }

    @Override
    protected boolean checkLayoutParams(LayoutParams params) {
        return params instanceof MarginLayoutParams;
    }
}
