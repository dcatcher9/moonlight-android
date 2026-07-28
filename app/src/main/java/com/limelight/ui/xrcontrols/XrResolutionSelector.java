package com.limelight.ui.xrcontrols;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatButton;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;

import com.limelight.R;
import com.limelight.preferences.XrResolutionOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Compact visual resolution choices for an XR panel.
 *
 * <p>Every option is an ordinary Android button, so gaze hover, focus, pinch, controller, and
 * accessibility input all follow the hosted-view path. The selector is a flow layout rather than
 * a horizontal scroller: landscape cards form the first group and portrait cards always begin on
 * the next row. Either group can wrap further on a narrow panel.</p>
 */
public final class XrResolutionSelector extends ViewGroup {
    public static final String RESOLUTION_1080P = XrResolutionOptions.RESOLUTION_1080P;
    public static final String RESOLUTION_1440P = XrResolutionOptions.RESOLUTION_1440P;
    public static final String RESOLUTION_4K = XrResolutionOptions.RESOLUTION_4K;
    public static final String RESOLUTION_UW_1080P = XrResolutionOptions.RESOLUTION_UW_1080P;
    public static final String RESOLUTION_UW_1440P =
            XrResolutionOptions.RESOLUTION_UW_1440P;
    public static final String RESOLUTION_5K2K = XrResolutionOptions.RESOLUTION_5K2K;

    public static final String RESOLUTION_1080P_PORTRAIT =
            XrResolutionOptions.RESOLUTION_1080P_PORTRAIT;
    public static final String RESOLUTION_1440P_PORTRAIT =
            XrResolutionOptions.RESOLUTION_1440P_PORTRAIT;
    public static final String RESOLUTION_4K_PORTRAIT =
            XrResolutionOptions.RESOLUTION_4K_PORTRAIT;
    public static final String RESOLUTION_UW_1080P_PORTRAIT =
            XrResolutionOptions.RESOLUTION_UW_1080P_PORTRAIT;
    public static final String RESOLUTION_UW_1440P_PORTRAIT =
            XrResolutionOptions.RESOLUTION_UW_1440P_PORTRAIT;
    public static final String RESOLUTION_5K2K_PORTRAIT =
            XrResolutionOptions.RESOLUTION_5K2K_PORTRAIT;

    /**
     * Existing landscape families first, followed by their portrait counterparts in the same
     * deterministic order. Keeping the original six cards first preserves their established
     * placement and cycle order.
     *
     * <p>The shared source is also consumed by every presenter's settings model, so a card cannot
     * silently exist in only the global picker or only one presentation mode.</p>
     */
    private static final List<XrResolutionOptions.Option> STANDARD_OPTIONS =
            XrResolutionOptions.standardOptions();
    private static final int PORTRAIT_GROUP_START_INDEX =
            firstPortraitOptionIndex(STANDARD_OPTIONS);

    public interface OnResolutionSelectedListener {
        /** Returns true when the explicit resolution ID is accepted. */
        boolean onResolutionSelected(@NonNull String resolutionId);
    }

    private final List<ResolutionOption> visibleOptions = new ArrayList<>();
    private final List<ResolutionCard> cards = new ArrayList<>();
    private final int horizontalSpacing;
    private final int verticalSpacing;
    @Nullable
    private String selectedResolutionId;
    @Nullable
    private OnResolutionSelectedListener listener;

    public XrResolutionSelector(@NonNull Context context) {
        this(context, null);
    }

    public XrResolutionSelector(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public XrResolutionSelector(@NonNull Context context, @Nullable AttributeSet attrs,
                                int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        horizontalSpacing = getResources().getDimensionPixelSize(R.dimen.xr_space_sm);
        verticalSpacing = getResources().getDimensionPixelSize(R.dimen.xr_space_sm);
        setClipChildren(false);
        setClipToPadding(false);
        setFocusable(false);
        rebuildCards();
    }

    public void setOnResolutionSelectedListener(
            @Nullable OnResolutionSelectedListener listener) {
        this.listener = listener;
    }

    /**
     * Selects an exact persisted resolution ID. Unknown IDs remain visible as a selected Custom
     * card until the user explicitly chooses one of the standard values.
     */
    public void setSelectedResolutionId(@Nullable String resolutionId) {
        String normalized = normalizeId(resolutionId);
        if (Objects.equals(selectedResolutionId, normalized)) {
            updateSelection();
            return;
        }

        boolean hadCustomCard = isCustomId(selectedResolutionId);
        selectedResolutionId = normalized;
        boolean needsCustomCard = isCustomId(selectedResolutionId);
        if (hadCustomCard || needsCustomCard) {
            rebuildCards();
        }
        else {
            updateSelection();
        }
    }

    @Nullable
    public String getSelectedResolutionId() {
        return selectedResolutionId;
    }

    public int getCardCount() {
        return cards.size();
    }

    @NonNull
    public AppCompatButton getCardAt(int index) {
        return cards.get(index);
    }

    @NonNull
    public String getResolutionIdAt(int index) {
        return visibleOptions.get(index).id;
    }

    @NonNull
    public String getLabelAt(int index) {
        return visibleOptions.get(index).label;
    }

    public float getAspectRatioAt(int index) {
        return visibleOptions.get(index).aspectRatio();
    }

    @Nullable
    public AppCompatButton findCardByResolutionId(@Nullable String resolutionId) {
        if (resolutionId == null) {
            return null;
        }
        for (ResolutionCard card : cards) {
            if (resolutionId.equals(card.option.id)) {
                return card;
            }
        }
        return null;
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        for (ResolutionCard card : cards) {
            card.setEnabled(enabled);
        }
    }

    private void rebuildCards() {
        removeAllViews();
        visibleOptions.clear();
        cards.clear();

        for (XrResolutionOptions.Option option : STANDARD_OPTIONS) {
            addOption(new ResolutionOption(
                    option.id, option.label, option.width, option.height, false));
        }
        if (isCustomId(selectedResolutionId)) {
            addOption(customOption(selectedResolutionId));
        }
        updateSelection();
        requestLayout();
    }

    private void addOption(ResolutionOption option) {
        ResolutionCard card = new ResolutionCard(getContext(), option);
        card.setEnabled(isEnabled());
        if (!option.custom) {
            card.setOnClickListener(view -> selectFromCard((ResolutionCard) view));
        }
        visibleOptions.add(option);
        cards.add(card);
        addView(card, new MarginLayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    private void selectFromCard(ResolutionCard card) {
        String id = card.option.id;
        if (id.equals(selectedResolutionId)) {
            return;
        }
        if (listener != null && !listener.onResolutionSelected(id)) {
            return;
        }

        boolean removeCustomCard = isCustomId(selectedResolutionId);
        boolean restoreFocus = card.hasFocus();
        selectedResolutionId = id;
        if (removeCustomCard) {
            rebuildCards();
            if (restoreFocus) {
                AppCompatButton selectedCard = findCardByResolutionId(id);
                if (selectedCard != null) {
                    selectedCard.requestFocus();
                }
            }
        }
        else {
            updateSelection();
        }
    }

    private void updateSelection() {
        for (ResolutionCard card : cards) {
            boolean selected = card.option.id.equals(selectedResolutionId);
            card.setActivated(selected);
            card.setSelected(selected);
            card.updateContentDescription(selected);
        }
    }

    private boolean isCustomId(@Nullable String id) {
        return id != null && !isStandardId(id);
    }

    private static boolean isStandardId(String id) {
        return XrResolutionOptions.isStandardId(id);
    }

    @Nullable
    private static String normalizeId(@Nullable String id) {
        if (id == null) {
            return null;
        }
        String trimmed = id.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ResolutionOption customOption(String id) {
        int[] dimensions = parseDimensions(id);
        return new ResolutionOption(id, "Custom", dimensions[0], dimensions[1], true);
    }

    private static int[] parseDimensions(String id) {
        String normalized = id.trim().toLowerCase(Locale.US).replace('\u00d7', 'x');
        int separator = normalized.indexOf('x');
        if (separator > 0 && separator == normalized.lastIndexOf('x')) {
            try {
                int width = Integer.parseInt(normalized.substring(0, separator).trim());
                int height = Integer.parseInt(normalized.substring(separator + 1).trim());
                if (width > 0 && height > 0) {
                    return new int[] {width, height};
                }
            }
            catch (NumberFormatException ignored) {
                // Preserve the unknown ID and use a neutral screen cue below.
            }
        }
        return new int[] {16, 9};
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int availableWidth = widthMode == MeasureSpec.UNSPECIFIED
                ? Integer.MAX_VALUE
                : Math.max(0, widthSize - getPaddingLeft() - getPaddingRight());

        // Cards are a picker grid, so they must all be one size. Their natural WRAP_CONTENT size
        // is not: a portrait label carries an extra word, and the custom card carries a second
        // line, so measuring each card on its own text makes neighbours visibly different sizes.
        // Measure everything once to find the largest natural card, then give that size to all.
        int uniformWidth = 0;
        int uniformHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);
            uniformWidth = Math.max(uniformWidth, child.getMeasuredWidth());
            uniformHeight = Math.max(uniformHeight, child.getMeasuredHeight());
        }
        int uniformWidthSpec = MeasureSpec.makeMeasureSpec(uniformWidth, MeasureSpec.EXACTLY);
        int uniformHeightSpec = MeasureSpec.makeMeasureSpec(uniformHeight, MeasureSpec.EXACTLY);

        int lineWidth = 0;
        int lineHeight = 0;
        int widestLine = 0;
        int contentHeight = 0;
        boolean lineHasChild = false;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            child.measure(uniformWidthSpec, uniformHeightSpec);
            MarginLayoutParams params = (MarginLayoutParams) child.getLayoutParams();
            int childWidth = child.getMeasuredWidth() + params.leftMargin + params.rightMargin;
            int childHeight = child.getMeasuredHeight() + params.topMargin + params.bottomMargin;
            int nextWidth = lineHasChild
                    ? lineWidth + horizontalSpacing + childWidth : childWidth;

            if (lineHasChild
                    && (i == PORTRAIT_GROUP_START_INDEX || nextWidth > availableWidth)) {
                widestLine = Math.max(widestLine, lineWidth);
                contentHeight += lineHeight + verticalSpacing;
                lineWidth = childWidth;
                lineHeight = childHeight;
            }
            else {
                lineWidth = nextWidth;
                lineHeight = Math.max(lineHeight, childHeight);
            }
            lineHasChild = true;
        }

        if (lineHasChild) {
            widestLine = Math.max(widestLine, lineWidth);
            contentHeight += lineHeight;
        }

        int desiredWidth = widestLine + getPaddingLeft() + getPaddingRight();
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
        int x = rtl ? contentRight : contentLeft;
        int y = getPaddingTop();
        int lineHeight = 0;
        boolean lineHasChild = false;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            MarginLayoutParams params = (MarginLayoutParams) child.getLayoutParams();
            int childWidth = child.getMeasuredWidth();
            int childHeight = child.getMeasuredHeight();
            int occupiedWidth = childWidth + params.leftMargin + params.rightMargin;
            boolean needsWrap = lineHasChild
                    && (i == PORTRAIT_GROUP_START_INDEX
                    || (rtl
                    ? x - horizontalSpacing - occupiedWidth < contentLeft
                    : x + horizontalSpacing + occupiedWidth > contentRight));
            if (needsWrap) {
                x = rtl ? contentRight : contentLeft;
                y += lineHeight + verticalSpacing;
                lineHeight = 0;
                lineHasChild = false;
            }

            if (lineHasChild) {
                x += rtl ? -horizontalSpacing : horizontalSpacing;
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
            lineHeight = Math.max(lineHeight,
                    childHeight + params.topMargin + params.bottomMargin);
            lineHasChild = true;
        }
    }

    private static int firstPortraitOptionIndex(
            List<XrResolutionOptions.Option> options) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).portrait) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
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

    private static final class ResolutionOption {
        final String id;
        final String label;
        final int width;
        final int height;
        final boolean custom;

        ResolutionOption(String id, String label, int width, int height, boolean custom) {
            this.id = id;
            this.label = label;
            this.width = width;
            this.height = height;
            this.custom = custom;
        }

        float aspectRatio() {
            return width / (float) height;
        }

        /**
         * Banded by the shorter pixel axis, which an orientation pair shares.
         *
         * <p>Total pixel count would rank UW 1080p above 1080p and below 1440p, which reads as a
         * tier it is not: the ultrawide entries are the same short-axis class as their widescreen
         * namesakes and only differ in long-axis extent, which the glyph already shows through its
         * aspect. Short-axis banding also gives every portrait counterpart the same density cue as
         * its landscape source and leaves all retained landscape cards unchanged.</p>
         */
        int densityLevel() {
            int shortAxis = Math.min(width, height);
            if (shortAxis <= 720) {
                return 1;
            }
            if (shortAxis <= 1080) {
                return 2;
            }
            if (shortAxis <= 1440) {
                return 3;
            }
            return 4;
        }

        String detail() {
            return custom ? width + " \u00d7 " + height : "";
        }
    }

    /** Package-visible for focused geometry/state tests without exposing implementation publicly. */
    static final class ResolutionCard extends AppCompatButton {
        final ResolutionOption option;
        final ResolutionGlyphDrawable glyph;

        ResolutionCard(Context context, ResolutionOption option) {
            super(context);
            this.option = option;
            glyph = new ResolutionGlyphDrawable(
                    context, option.aspectRatio(), option.densityLevel());

            setBackground(new CardBackgroundDrawable(context));
            ViewCompat.setBackgroundTintList(this, null);
            setStateListAnimator(null);
            setElevation(0f);
            setGravity(Gravity.CENTER);
            setAllCaps(false);
            setText(option.custom ? option.label + "\n" + option.detail() : cardText(option));
            setTag(option.id);
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    context.getResources().getDimension(R.dimen.xr_text_title));
            setTextColor(cardTextColors(context));
            setMaxLines(2);
            setIncludeFontPadding(false);
            setMinWidth(dp(context, 112));
            setMinimumWidth(dp(context, 112));
            setMinHeight(dp(context, 104));
            setMinimumHeight(dp(context, 104));
            int horizontalPadding =
                    context.getResources().getDimensionPixelSize(R.dimen.xr_space_md);
            int verticalPadding =
                    context.getResources().getDimensionPixelSize(R.dimen.xr_space_sm);
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
            setCompoundDrawablePadding(
                    context.getResources().getDimensionPixelSize(R.dimen.xr_space_sm));
            setCompoundDrawablesWithIntrinsicBounds(null, glyph, null, null);
            setFocusable(true);
            // Gaze highlighting is driven by hover. Enabling touch-mode focus makes Android
            // consume the first pinch only to focus this card and defer its click until the next.
            setFocusableInTouchMode(false);
            setClickable(!option.custom);
            updateContentDescription(false);
        }

        /**
         * Breaks the orientation word onto its own line for portrait cards.
         *
         * <p>"UW 1440p Portrait" laid out on one line is far wider than the landscape card it sits
         * beside, which then drags every card to that width once the grid is uniformly sized. The
         * measured word is the same one the label already ends with, so the card still reads as its
         * landscape peer plus an orientation, and {@code option.label} stays a single clean string
         * for the content description and the other pickers.</p>
         */
        private static String cardText(ResolutionOption option) {
            if (option.height <= option.width) {
                return option.label;
            }
            int lastSpace = option.label.lastIndexOf(' ');
            if (lastSpace <= 0) {
                return option.label;
            }
            return option.label.substring(0, lastSpace) + "\n"
                    + option.label.substring(lastSpace + 1);
        }

        void updateContentDescription(boolean selected) {
            String dimensions = option.width + " by " + option.height;
            String description = option.label + " resolution, " + dimensions;
            setContentDescription(selected ? description + ", selected" : description);
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            if (glyph != null) {
                glyph.setState(getDrawableState());
            }
        }

        private static ColorStateList cardTextColors(Context context) {
            return new ColorStateList(new int[][] {
                    new int[] {-android.R.attr.state_enabled},
                    new int[] {android.R.attr.state_activated},
                    new int[] {android.R.attr.state_focused},
                    new int[] {android.R.attr.state_hovered},
                    new int[0],
            }, new int[] {
                    color(context, R.color.xr_text_disabled),
                    color(context, R.color.xr_text_primary),
                    color(context, R.color.xr_text_primary),
                    color(context, R.color.xr_text_primary),
                    color(context, R.color.xr_text_primary),
            });
        }
    }

    /** Draws a small monitor whose screen rectangle preserves the option's aspect ratio. */
    static final class ResolutionGlyphDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float aspectRatio;
        private final int densityLevel;
        private final int intrinsicWidth;
        private final int intrinsicHeight;
        private final float strokeWidth;
        private final float cornerRadius;
        private final float standHeight;
        private final int disabledColor;
        private final int activeColor;
        private final int defaultColor;
        private final RectF cachedScreenBounds = new RectF();
        private final float[] cachedDensityDotCenters;
        private int alpha = 255;

        ResolutionGlyphDrawable(Context context, float aspectRatio, int densityLevel) {
            this.aspectRatio = aspectRatio;
            this.densityLevel = Math.max(1, Math.min(4, densityLevel));
            cachedDensityDotCenters = new float[this.densityLevel * 4];
            intrinsicWidth = dp(context, 60);
            intrinsicHeight = dp(context, 40);
            strokeWidth = dp(context, 2);
            cornerRadius = dp(context, 2);
            standHeight = dp(context, 7);
            disabledColor = color(context, R.color.xr_text_disabled);
            activeColor = color(context, R.color.xr_accent_bright);
            defaultColor = color(context, R.color.xr_accent);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(strokeWidth);
            updateCachedGeometry(getBounds());
        }

        float getAspectRatio() {
            return aspectRatio;
        }

        int getDensityLevel() {
            return densityLevel;
        }

        RectF getScreenBounds() {
            return new RectF(cachedScreenBounds);
        }

        @Override
        protected void onBoundsChange(@NonNull Rect bounds) {
            super.onBoundsChange(bounds);
            updateCachedGeometry(bounds);
        }

        private void updateCachedGeometry(Rect bounds) {
            float inset = strokeWidth * 0.75f;
            float availableWidth = Math.max(1f, bounds.width() - inset * 2f);
            float availableHeight = Math.max(1f,
                    bounds.height() - standHeight - inset * 2f);
            float screenWidth = availableWidth;
            float screenHeight = screenWidth / aspectRatio;
            if (screenHeight > availableHeight) {
                screenHeight = availableHeight;
                screenWidth = screenHeight * aspectRatio;
            }
            float screenLeft = bounds.exactCenterX() - screenWidth / 2f;
            float screenTop = bounds.top + inset;
            cachedScreenBounds.set(screenLeft, screenTop,
                    screenLeft + screenWidth, screenTop + screenHeight);

            RectF screen = cachedScreenBounds;
            boolean portraitScreen = screen.height() > screen.width();
            float alongExtent = portraitScreen ? screen.height() : screen.width();
            float crossExtent = portraitScreen ? screen.width() : screen.height();
            float alongCenter = portraitScreen ? screen.centerY() : screen.centerX();
            float crossCenter = portraitScreen ? screen.centerX() : screen.centerY();
            float usableAlong = alongExtent * 0.56f;
            float firstAlong = alongCenter - usableAlong / 2f;
            float stepAlong = densityLevel == 1 ? 0f : usableAlong / (densityLevel - 1);
            // The narrow axis of a portrait rect would otherwise place the two rows close enough to
            // merge. The floor keeps a visible gap and never moves the wider landscape rows.
            float crossOffset = Math.max(densityDotRadius() * 1.8f, crossExtent * 0.12f);

            for (int index = 0; index < densityLevel; index++) {
                float along = densityLevel == 1 ? alongCenter : firstAlong + index * stepAlong;
                int offset = index * 4;
                if (portraitScreen) {
                    cachedDensityDotCenters[offset] = crossCenter - crossOffset;
                    cachedDensityDotCenters[offset + 1] = along;
                    cachedDensityDotCenters[offset + 2] = crossCenter + crossOffset;
                    cachedDensityDotCenters[offset + 3] = along;
                }
                else {
                    cachedDensityDotCenters[offset] = along;
                    cachedDensityDotCenters[offset + 1] = crossCenter - crossOffset;
                    cachedDensityDotCenters[offset + 2] = along;
                    cachedDensityDotCenters[offset + 3] = crossCenter + crossOffset;
                }
            }
        }

        float densityDotRadius() {
            return Math.max(1f, strokeWidth * 0.55f);
        }

        /**
         * Centres of the density matrix as flat x,y pairs, in draw order.
         *
         * <p>A denser matrix distinguishes 1080p through 4K even when every preset shares an aspect.
         * The dot row must therefore run along the screen's LONG axis: a portrait rect is only about
         * a third as wide as a landscape one, so a horizontal row of 2-4 dots collapses into a
         * single smudge and every portrait card ends up looking identical. Transposing keeps the
         * dot count and the spacing rule while leaving landscape glyphs pixel-identical.</p>
         */
        float[] densityDotCenters() {
            return cachedDensityDotCenters.clone();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            paint.setColor(glyphColorForState(getState()));
            paint.setAlpha(alpha);
            RectF screen = cachedScreenBounds;
            canvas.drawRoundRect(screen, cornerRadius, cornerRadius, paint);

            paint.setStyle(Paint.Style.FILL);
            float dotRadius = densityDotRadius();
            float[] centers = cachedDensityDotCenters;
            for (int i = 0; i < centers.length; i += 2) {
                canvas.drawCircle(centers[i], centers[i + 1], dotRadius, paint);
            }
            paint.setStyle(Paint.Style.STROKE);

            float standTop = screen.bottom;
            float standBottom = Math.min(getBounds().bottom - strokeWidth,
                    standTop + standHeight * 0.62f);
            canvas.drawLine(screen.centerX(), standTop, screen.centerX(), standBottom, paint);
            float footHalfWidth = Math.min(screen.width() * 0.14f, standHeight * 0.8f);
            canvas.drawLine(screen.centerX() - footHalfWidth, standBottom,
                    screen.centerX() + footHalfWidth, standBottom, paint);
        }

        @Override
        protected boolean onStateChange(int[] state) {
            invalidateSelf();
            return true;
        }

        @Override
        public boolean isStateful() {
            return true;
        }

        @Override
        public void setAlpha(int alpha) {
            this.alpha = alpha;
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return intrinsicWidth;
        }

        @Override
        public int getIntrinsicHeight() {
            return intrinsicHeight;
        }

        private int glyphColorForState(int[] state) {
            if (!hasState(state, android.R.attr.state_enabled)) {
                return disabledColor;
            }
            if (hasState(state, android.R.attr.state_activated)
                    || hasState(state, android.R.attr.state_focused)
                    || hasState(state, android.R.attr.state_hovered)
                    || hasState(state, android.R.attr.state_pressed)) {
                return activeColor;
            }
            return defaultColor;
        }
    }

    /** Flat stateful card surface with distinct selected, focus, hover, and press treatment. */
    private static final class CardBackgroundDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF cachedBounds = new RectF();
        private final RectF insetBounds = new RectF();
        private final float radius;
        private final float oneDp;
        private final int disabledFill;
        private final int disabledStroke;
        private final int activeFill;
        private final int activeFocusFill;
        private final int hoverFill;
        private final int pressedFill;
        private final int activeFocusStroke;
        private final int activeStroke;
        private final int defaultFill;
        private final int defaultStroke;
        private int alpha = 255;

        CardBackgroundDrawable(Context context) {
            radius = context.getResources().getDimension(R.dimen.xr_radius_card);
            oneDp = dp(context, 1);
            disabledFill = color(context, R.color.xr_segment_disabled);
            disabledStroke = color(context, R.color.xr_border);
            activeFill = color(context, R.color.xr_accent_deep);
            activeFocusFill = ColorUtils.compositeColors(
                    color(context, R.color.xr_accent_focus_overlay), activeFill);
            activeFocusStroke = color(context, R.color.xr_accent_bright);
            activeStroke = color(context, R.color.xr_accent);
            defaultFill = color(context, R.color.xr_surface_raised);
            hoverFill = ColorUtils.compositeColors(
                    color(context, R.color.xr_accent_focus_overlay), defaultFill);
            pressedFill = ColorUtils.compositeColors(
                    color(context, R.color.xr_accent_pressed_overlay), activeFill);
            defaultStroke = color(context, R.color.xr_border);
        }

        @Override
        protected void onBoundsChange(@NonNull Rect bounds) {
            super.onBoundsChange(bounds);
            cachedBounds.set(bounds);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            int[] state = getState();
            boolean enabled = hasState(state, android.R.attr.state_enabled);
            boolean pressed = hasState(state, android.R.attr.state_pressed);
            boolean activated = hasState(state, android.R.attr.state_activated);
            boolean focused = hasState(state, android.R.attr.state_focused);
            boolean hovered = hasState(state, android.R.attr.state_hovered);

            int fill;
            int stroke;
            float strokeWidth;
            if (!enabled) {
                fill = disabledFill;
                stroke = disabledStroke;
                strokeWidth = oneDp;
            }
            else if (pressed) {
                fill = pressedFill;
                stroke = activeFocusStroke;
                strokeWidth = oneDp * 2f;
            }
            else if (activated && (focused || hovered)) {
                fill = activeFocusFill;
                stroke = activeFocusStroke;
                strokeWidth = oneDp * 3f;
            }
            else if (focused || hovered) {
                fill = hoverFill;
                stroke = activeStroke;
                strokeWidth = oneDp * 3f;
            }
            else if (activated) {
                fill = activeFill;
                stroke = activeStroke;
                strokeWidth = oneDp * 2f;
            }
            else {
                fill = defaultFill;
                stroke = defaultStroke;
                strokeWidth = oneDp;
            }

            paint.setAlpha(alpha);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(fill);
            canvas.drawRoundRect(cachedBounds, radius, radius, paint);

            float inset = strokeWidth / 2f;
            insetBounds.set(cachedBounds);
            insetBounds.inset(inset, inset);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(strokeWidth);
            paint.setColor(stroke);
            canvas.drawRoundRect(insetBounds, radius, radius, paint);
        }

        @Override
        protected boolean onStateChange(int[] state) {
            invalidateSelf();
            return true;
        }

        @Override
        public boolean isStateful() {
            return true;
        }

        @Override
        public void setAlpha(int alpha) {
            this.alpha = alpha;
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private static boolean hasState(int[] states, int target) {
        for (int state : states) {
            if (state == target) {
                return true;
            }
        }
        return false;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static int color(Context context, int resourceId) {
        return ContextCompat.getColor(context, resourceId);
    }
}
