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
    private static final float TREND_ABSOLUTE_EPSILON = 0.0001f;
    private static final float TREND_RELATIVE_EPSILON = 0.01f;

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
        linePaint.setStrokeWidth(Math.max(1.0f, dp(2.0f)));
        baselinePaint.setStrokeWidth(Math.max(1.0f, dp(1.25f)));
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

    /** Produces a compact text equivalent for the values actually drawn by the sparkline. */
    public static String describeTrend(float[] samples, int count) {
        if (samples == null || count <= 0) {
            return "insufficient history";
        }
        int limit = Math.min(count, samples.length);
        int finiteCount = 0;
        float first = 0.0f;
        float last = 0.0f;
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        float maxAbs = 0.0f;
        int lastFiniteIndex = -1;
        for (int i = 0; i < limit; i++) {
            float value = samples[i];
            if (!Float.isFinite(value)) {
                continue;
            }
            if (finiteCount == 0) {
                first = value;
            }
            last = value;
            min = Math.min(min, value);
            max = Math.max(max, value);
            maxAbs = Math.max(maxAbs, Math.abs(value));
            lastFiniteIndex = i;
            finiteCount++;
        }
        if (finiteCount < 2) {
            return "insufficient history";
        }

        float epsilon = Math.max(TREND_ABSOLUTE_EPSILON,
                maxAbs * TREND_RELATIVE_EPSILON);
        float range = max - min;
        String shape;
        if (range <= epsilon * 2.0f) {
            shape = "steady";
        } else {
            float spikeThreshold = Math.max(epsilon * 3.0f, range * 0.60f);
            float priorMin = Float.MAX_VALUE;
            float priorMax = -Float.MAX_VALUE;
            for (int i = 0; i < lastFiniteIndex; i++) {
                float value = samples[i];
                if (Float.isFinite(value)) {
                    priorMin = Math.min(priorMin, value);
                    priorMax = Math.max(priorMax, value);
                }
            }
            boolean latestUpwardSpike = priorMax > -Float.MAX_VALUE
                    && last - priorMax >= spikeThreshold;
            boolean latestDownwardSpike = priorMin < Float.MAX_VALUE
                    && priorMin - last >= spikeThreshold;
            if (latestUpwardSpike) {
                shape = "latest upward spike";
            } else if (latestDownwardSpike) {
                shape = "latest downward spike";
            } else {
                float endpointDelta = last - first;
                float endpointTolerance = Math.max(epsilon * 2.0f, range * 0.15f);
                if (Math.abs(endpointDelta) <= endpointTolerance) {
                    float endpointBaseline = (first + last) * 0.5f;
                    float upwardExcursion = max - endpointBaseline;
                    float downwardExcursion = endpointBaseline - min;
                    if (upwardExcursion >= spikeThreshold
                            && upwardExcursion >= downwardExcursion) {
                        shape = "recent upward spike";
                    } else if (downwardExcursion >= spikeThreshold) {
                        shape = "recent downward spike";
                    } else {
                        shape = "fluctuating";
                    }
                } else {
                    float directionalThreshold =
                            Math.max(epsilon * 2.0f, range * 0.35f);
                    if (endpointDelta >= directionalThreshold) {
                        shape = "rising";
                    } else if (endpointDelta <= -directionalThreshold) {
                        shape = "falling";
                    } else {
                        shape = "fluctuating";
                    }
                }
            }
        }
        return shape + ", recent range " + compactTrendValue(min) + " to "
                + compactTrendValue(max) + ", latest " + compactTrendValue(last);
    }

    /**
     * Describes a reset in the raw cumulative counter that delta plotting intentionally suppresses.
     * The wording makes clear that this is context for the chart, not a falling line it displays.
     */
    public static String describeCounterRestart(float[] counter, int count) {
        if (counter == null || count <= 1) {
            return null;
        }
        int limit = Math.min(count, counter.length);
        float previous = 0.0f;
        boolean previousFinite = false;
        boolean restart = false;
        boolean latestStepRestart = false;
        int lastFiniteIndex = -1;
        for (int i = 0; i < limit; i++) {
            if (Float.isFinite(counter[i])) {
                lastFiniteIndex = i;
            }
        }
        for (int i = 0; i < limit; i++) {
            float value = counter[i];
            if (!Float.isFinite(value)) {
                previousFinite = false;
                continue;
            }
            if (previousFinite && value < previous - 0.5f) {
                restart = true;
                latestStepRestart = i == lastFiniteIndex;
            }
            previous = value;
            previousFinite = true;
        }
        if (!restart) {
            return null;
        }
        return latestStepRestart
                ? "latest counter restart omitted from the delta plot"
                : "earlier counter restart omitted from the delta plot";
    }

    private static String compactTrendValue(float value) {
        float rounded = (float) (Math.rint((double) value * 10000.0) / 10000.0);
        if (rounded == -0.0f) {
            rounded = 0.0f;
        }
        return Float.toString(rounded);
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
