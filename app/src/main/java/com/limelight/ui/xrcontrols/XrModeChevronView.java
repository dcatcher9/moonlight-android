package com.limelight.ui.xrcontrols;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.limelight.R;

/**
 * Passive expand/collapse cue centered along the bottom edge of an XR mode tile.
 *
 * <p>The parent mode tile remains the only input and accessibility target. This view precomputes
 * its shallow chevron path when its size or orientation changes, so drawing performs no geometry
 * allocation.</p>
 */
public final class XrModeChevronView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int preferredWidth;
    private final int preferredHeight;
    private final float strokeWidth;
    private final int collapsedColor;
    private final int expandedColor;

    private boolean expanded;
    private final float[] geometry = new float[6];

    public XrModeChevronView(@NonNull Context context) {
        this(context, null);
    }

    public XrModeChevronView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public XrModeChevronView(@NonNull Context context, @Nullable AttributeSet attrs,
                             int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        preferredWidth = getResources().getDimensionPixelSize(R.dimen.xr_control_compact);
        preferredHeight = getResources().getDimensionPixelSize(R.dimen.xr_space_lg);
        strokeWidth = dp(3);
        collapsedColor = ContextCompat.getColor(context, R.color.xr_accent_bright);
        expandedColor = ContextCompat.getColor(context, R.color.xr_text_primary);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidth);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(collapsedColor);

        setMinimumWidth(preferredWidth);
        setMinimumHeight(preferredHeight);
        setClickable(false);
        setLongClickable(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setContentDescription(null);
        ViewCompat.setImportantForAccessibility(this,
                ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Shows an upward chevron while the mode's subpane is expanded. */
    public void setExpanded(boolean expanded) {
        if (this.expanded == expanded) {
            return;
        }
        this.expanded = expanded;
        paint.setColor(expanded ? expandedColor : collapsedColor);
        rebuildPath(getWidth(), getHeight());
        invalidate();
    }

    public boolean isExpanded() {
        return expanded;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(resolveSize(preferredWidth, widthMeasureSpec),
                resolveSize(preferredHeight, heightMeasureSpec));
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        rebuildPath(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawPath(path, paint);
    }

    private void rebuildPath(int width, int height) {
        path.reset();
        if (width <= 0 || height <= 0) {
            for (int i = 0; i < geometry.length; i++) {
                geometry[i] = 0.0f;
            }
            return;
        }

        // Keep a familiar compact chevron aspect. The containing tile positions this view along
        // its bottom edge; the cue itself must not turn into an edge-to-edge underline.
        float horizontalInset = Math.max(strokeWidth, width * 0.15f);
        float upperY = Math.max(strokeWidth, height * 0.24f);
        float lowerY = Math.min(height - strokeWidth, height * 0.76f);
        float outsideY = expanded ? lowerY : upperY;
        float centerY = expanded ? upperY : lowerY;

        geometry[0] = horizontalInset;
        geometry[1] = outsideY;
        geometry[2] = width * 0.5f;
        geometry[3] = centerY;
        geometry[4] = width - horizontalInset;
        geometry[5] = outsideY;

        path.moveTo(geometry[0], geometry[1]);
        path.lineTo(geometry[2], geometry[3]);
        path.lineTo(geometry[4], geometry[5]);
    }

    float geometryAt(int index) {
        return geometry[index];
    }

    float resolvedStrokeWidth() {
        return strokeWidth;
    }

    Paint.Cap resolvedStrokeCap() {
        return paint.getStrokeCap();
    }

    Paint.Join resolvedStrokeJoin() {
        return paint.getStrokeJoin();
    }

    int resolvedColor() {
        return paint.getColor();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
