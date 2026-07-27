package com.limelight.ui.xrcontrols;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Minimal history plot for one stats metric.
 *
 * <p>Sits beside a value in the stats panel to answer the question a single reading cannot: is this
 * steady, drifting, or resetting. Deliberately unlabelled and unaxised -- it is a shape, not a
 * chart, and the exact number is already printed next to it.</p>
 *
 * <p>Draws on the UI thread inside a panel that redraws whenever stats refresh, so it allocates
 * nothing per frame: the path and paints are retained and rewound.</p>
 */
public final class XrSparklineView extends View {
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint baselinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private float[] values = new float[0];
    private int length;
    /** Fixed vertical range; NaN means scale to the data. */
    private float fixedMin = Float.NaN;
    private float fixedMax = Float.NaN;

    public XrSparklineView(Context context) {
        this(context, null);
    }

    public XrSparklineView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeWidth(Math.max(1.0f, dp(1.5f)));
        baselinePaint.setStrokeWidth(Math.max(1.0f, dp(1.0f)));
    }

    public void setColors(int lineColor, int baselineColor) {
        linePaint.setColor(lineColor);
        baselinePaint.setColor(baselineColor);
        invalidate();
    }

    /**
     * @param min fixed lower bound, or NaN to autoscale
     * @param max fixed upper bound, or NaN to autoscale
     */
    public void setRange(float min, float max) {
        fixedMin = min;
        fixedMax = max;
        invalidate();
    }

    public void setValues(float[] samples, int count) {
        if (samples == null || count <= 0) {
            length = 0;
            invalidate();
            return;
        }
        if (values.length < count) {
            values = new float[count];
        }
        System.arraycopy(samples, 0, values, 0, count);
        length = count;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float inset = linePaint.getStrokeWidth();
        canvas.drawLine(0, h - inset, w, h - inset, baselinePaint);
        if (length < 2) {
            return;
        }

        float min = fixedMin;
        float max = fixedMax;
        if (Float.isNaN(min) || Float.isNaN(max)) {
            min = Float.MAX_VALUE;
            max = -Float.MAX_VALUE;
            for (int i = 0; i < length; i++) {
                min = Math.min(min, values[i]);
                max = Math.max(max, values[i]);
            }
        }
        // A flat series has no range to normalize against; draw it on the baseline rather than
        // dividing by zero and painting noise that looks like signal.
        float span = max - min;
        if (span <= 0.000001f) {
            canvas.drawLine(0, h - inset, w, h - inset, linePaint);
            return;
        }

        path.rewind();
        float stepX = length > 1 ? (w - inset * 2.0f) / (length - 1) : 0.0f;
        for (int i = 0; i < length; i++) {
            float normalized = (values[i] - min) / span;
            float x = inset + i * stepX;
            float y = h - inset - normalized * (h - inset * 2.0f);
            if (i == 0) {
                path.moveTo(x, y);
            }
            else {
                path.lineTo(x, y);
            }
        }
        canvas.drawPath(path, linePaint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
