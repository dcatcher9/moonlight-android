package com.limelight.ui.xrcontrols;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.limelight.R;
import androidx.core.view.ViewCompat;

import java.util.Locale;

/**
 * Small passive visual cue for a named XR parameter row.
 *
 * <p>The adjacent text owns the accessible name, so this view is deliberately hidden from the
 * accessibility tree and never accepts input. Stable values are parsed only when
 * {@link #setParameter(Kind, String)} is called. Drawing reuses one paint, one rectangle, and a
 * fixed geometry buffer.</p>
 */
public final class XrParameterGlyphView extends View {
    public enum Kind {
        FPS_MOTION_BARS,
        HDR_SUN,
        VIDEO_RANGE,
        FRAME_PACING,
        AUDIO_LAYOUT,
        PRODUCER,
        STATUS
    }

    // Palette tokens, not hand-picked hues. These glyphs were the only XR icons carrying colours
    // of their own, which is what made them read as decoration beside the monochrome vector set.
    // Colour now means state and nothing else: ok/warn/danger for health, accent for "this
    // parameter is on", and the two text colours for everything that is merely an icon. The gold
    // that HDR used to get was the one hue with no such meaning, so it becomes accent like every
    // other enabled parameter.
    private final int colorPrimary;
    private final int colorMuted;
    private final int colorAccent;
    private final int colorOk;
    private final int colorWarn;
    private final int colorDanger;

    private static final int VARIANT_DEFAULT = 0;
    private static final int VARIANT_ONE = 1;
    private static final int VARIANT_TWO = 2;
    private static final int VARIANT_THREE = 3;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF scratchRect = new RectF();
    private final float[] geometry = new float[64];
    private final int preferredSize;
    private final float strokeWidth;
    private final float thinStrokeWidth;
    private final float cornerRadius;

    private Kind kind = Kind.STATUS;
    private String stableValue = "unknown";
    private int variant = VARIANT_THREE;
    private int semanticColor;
    private int audioDotCount;
    private int geometryCount;

    public XrParameterGlyphView(@NonNull Context context) {
        this(context, null);
    }

    public XrParameterGlyphView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public XrParameterGlyphView(@NonNull Context context, @Nullable AttributeSet attrs,
                                int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        colorPrimary = color(R.color.xr_text_primary);
        colorMuted = color(R.color.xr_text_secondary);
        colorAccent = color(R.color.xr_accent);
        colorOk = color(R.color.xr_status_ok);
        colorWarn = color(R.color.xr_status_warn);
        colorDanger = color(R.color.xr_danger);
        semanticColor = colorMuted;
        preferredSize = getResources().getDimensionPixelSize(R.dimen.xr_icon_tile);
        strokeWidth = dp(2);
        thinStrokeWidth = Math.max(1f, dp(1));
        cornerRadius = dp(2);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        setMinimumWidth(preferredSize);
        setMinimumHeight(preferredSize);
        setClickable(false);
        setLongClickable(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setContentDescription(null);
        ViewCompat.setImportantForAccessibility(this,
                ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_NO);
        resolveValue();
    }

    /** Sets both the glyph family and its stable persisted/runtime value. */
    public void setParameter(@NonNull Kind kind, @Nullable String stableValue) {
        if (kind == null) {
            throw new NullPointerException("kind");
        }
        String normalized = normalize(stableValue);
        if (this.kind == kind && this.stableValue.equals(normalized)) {
            return;
        }
        this.kind = kind;
        this.stableValue = normalized;
        resolveValue();
        rebuildGeometry(getWidth(), getHeight());
        invalidate();
    }

    @NonNull
    public Kind getKind() {
        return kind;
    }

    @NonNull
    public String getStableValue() {
        return stableValue;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(resolveSize(preferredSize, widthMeasureSpec),
                resolveSize(preferredSize, heightMeasureSpec));
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        rebuildGeometry(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        switch (kind) {
            case FPS_MOTION_BARS:
                drawFps(canvas);
                break;
            case HDR_SUN:
                drawHdr(canvas);
                break;
            case VIDEO_RANGE:
                drawVideoRange(canvas);
                break;
            case FRAME_PACING:
                drawFramePacing(canvas);
                break;
            case AUDIO_LAYOUT:
                drawAudio(canvas);
                break;
            case PRODUCER:
                drawProducer(canvas);
                break;
            case STATUS:
            default:
                drawStatus(canvas);
                break;
        }
    }

    private void resolveValue() {
        audioDotCount = 0;
        switch (kind) {
            case FPS_MOTION_BARS:
                int fps = parsePositiveInt(stableValue);
                variant = fps >= 90 ? VARIANT_THREE : fps >= 60 ? VARIANT_TWO : VARIANT_ONE;
                semanticColor = colorAccent;
                break;
            case HDR_SUN:
                if (matches("on", "true", "hdr")) {
                    variant = VARIANT_TWO;
                    semanticColor = colorAccent;
                }
                else if (matches("auto", "automatic")) {
                    variant = VARIANT_ONE;
                    semanticColor = colorAccent;
                }
                else {
                    variant = VARIANT_DEFAULT;
                    semanticColor = colorMuted;
                }
                break;
            case VIDEO_RANGE:
                variant = matches("full", "true") ? VARIANT_ONE : VARIANT_DEFAULT;
                semanticColor = variant == VARIANT_ONE ? colorPrimary : colorMuted;
                break;
            case FRAME_PACING:
                if (matches("balanced")) {
                    variant = VARIANT_ONE;
                }
                else if (matches("cap-fps", "fps-limit", "limit")) {
                    variant = VARIANT_TWO;
                }
                else if (matches("smoothness", "smooth", "smoothest")) {
                    variant = VARIANT_THREE;
                }
                else {
                    variant = VARIANT_DEFAULT;
                }
                semanticColor = colorAccent;
                break;
            case AUDIO_LAYOUT:
                if (matches("71", "7.1", "8")) {
                    audioDotCount = 8;
                    variant = VARIANT_THREE;
                }
                else if (matches("51", "5.1", "6")) {
                    audioDotCount = 6;
                    variant = VARIANT_TWO;
                }
                else {
                    audioDotCount = 2;
                    variant = VARIANT_ONE;
                }
                semanticColor = colorPrimary;
                break;
            case PRODUCER:
                variant = matches("headset", "client", "device", "xr")
                        ? VARIANT_ONE : VARIANT_DEFAULT;
                semanticColor = variant == VARIANT_ONE ? colorAccent : colorPrimary;
                break;
            case STATUS:
            default:
                if (matches("green", "ready", "ok", "success", "healthy")) {
                    variant = VARIANT_DEFAULT;
                    semanticColor = colorOk;
                }
                else if (matches("amber", "warning", "warn", "pending", "busy")) {
                    variant = VARIANT_ONE;
                    semanticColor = colorWarn;
                }
                else if (matches("red", "error", "failed", "failure", "unavailable")) {
                    variant = VARIANT_TWO;
                    semanticColor = colorDanger;
                }
                else {
                    variant = VARIANT_THREE;
                    semanticColor = colorMuted;
                }
                break;
        }
    }

    private void rebuildGeometry(int width, int height) {
        geometryCount = 0;
        if (width <= 0 || height <= 0) {
            return;
        }
        float size = Math.min(width, height);
        float left = (width - size) * 0.5f + size * 0.10f;
        float top = (height - size) * 0.5f + size * 0.10f;
        float right = left + size * 0.80f;
        float bottom = top + size * 0.80f;

        switch (kind) {
            case FPS_MOTION_BARS:
                buildFpsGeometry(left, top, right, bottom);
                break;
            case HDR_SUN:
                buildHdrGeometry(left, top, right, bottom);
                break;
            case VIDEO_RANGE:
                buildVideoRangeGeometry(left, top, right, bottom);
                break;
            case FRAME_PACING:
                buildPacingGeometry(left, top, right, bottom);
                break;
            case AUDIO_LAYOUT:
                buildAudioGeometry(left, top, right, bottom);
                break;
            case PRODUCER:
                buildProducerGeometry(left, top, right, bottom);
                break;
            case STATUS:
            default:
                buildStatusGeometry(left, top, right, bottom);
                break;
        }
    }

    private void buildFpsGeometry(float left, float top, float right, float bottom) {
        float height = bottom - top;
        for (int i = 0; i < 3; i++) {
            float y = top + height * (0.25f + i * 0.25f);
            float start = left + (right - left) * (0.08f + i * 0.13f);
            putLine(start, y, right - (right - left) * 0.08f, y);
        }
    }

    private void buildHdrGeometry(float left, float top, float right, float bottom) {
        float centerX = (left + right) * 0.5f;
        float centerY = (top + bottom) * 0.5f;
        float radius = (right - left) * 0.19f;
        put(centerX, centerY, radius);
        float inner = radius * 1.48f;
        float outer = radius * 2.05f;
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI * i / 4.0;
            float cosine = (float) Math.cos(angle);
            float sine = (float) Math.sin(angle);
            putLine(centerX + cosine * inner, centerY + sine * inner,
                    centerX + cosine * outer, centerY + sine * outer);
        }
    }

    private void buildVideoRangeGeometry(float left, float top, float right, float bottom) {
        float gap = (right - left) * 0.045f;
        float swatchWidth = (right - left - gap * 3f) / 4f;
        float swatchTop = top + (bottom - top) * 0.25f;
        float swatchBottom = bottom - (bottom - top) * 0.25f;
        for (int i = 0; i < 4; i++) {
            float swatchLeft = left + i * (swatchWidth + gap);
            put(swatchLeft, swatchTop, swatchLeft + swatchWidth, swatchBottom);
        }
    }

    private void buildPacingGeometry(float left, float top, float right, float bottom) {
        float centerY = (top + bottom) * 0.5f;
        putLine(left, centerY, right, centerY);
        float[] fractions;
        switch (variant) {
            case VARIANT_ONE:
                fractions = PACING_BALANCED;
                break;
            case VARIANT_TWO:
                fractions = PACING_CAPPED;
                break;
            case VARIANT_THREE:
                fractions = PACING_SMOOTH;
                break;
            case VARIANT_DEFAULT:
            default:
                fractions = PACING_LATENCY;
                break;
        }
        float tickHalfHeight = (bottom - top) * (variant == VARIANT_THREE ? 0.18f : 0.28f);
        for (float fraction : fractions) {
            float x = left + (right - left) * fraction;
            putLine(x, centerY - tickHalfHeight, x, centerY + tickHalfHeight);
        }
    }

    private void buildAudioGeometry(float left, float top, float right, float bottom) {
        float width = right - left;
        float height = bottom - top;
        put(left, top + height * 0.36f, left + width * 0.16f, top + height * 0.64f);
        putLine(left + width * 0.16f, top + height * 0.36f,
                left + width * 0.30f, top + height * 0.22f);
        putLine(left + width * 0.16f, top + height * 0.64f,
                left + width * 0.30f, top + height * 0.78f);

        int columns = audioDotCount <= 2 ? 2 : audioDotCount == 6 ? 3 : 4;
        int rows = audioDotCount / columns;
        float dotLeft = left + width * 0.48f;
        float dotRight = right;
        float dotTop = top + height * 0.34f;
        float dotBottom = bottom - height * 0.34f;
        float radius = Math.max(1f, width * 0.045f);
        for (int row = 0; row < rows; row++) {
            float y = rows == 1 ? (top + bottom) * 0.5f
                    : dotTop + (dotBottom - dotTop) * row / (rows - 1f);
            for (int column = 0; column < columns; column++) {
                float x = columns == 1 ? (dotLeft + dotRight) * 0.5f
                        : dotLeft + (dotRight - dotLeft) * column / (columns - 1f);
                put(x, y, radius);
            }
        }
    }

    private void buildProducerGeometry(float left, float top, float right, float bottom) {
        float width = right - left;
        float height = bottom - top;
        if (variant == VARIANT_ONE) {
            put(left + width * 0.12f, top + height * 0.08f,
                    right - width * 0.12f, bottom - height * 0.12f);
            put(left + width * 0.04f, top + height * 0.46f,
                    left + width * 0.23f, bottom - height * 0.06f);
            put(right - width * 0.23f, top + height * 0.46f,
                    right - width * 0.04f, bottom - height * 0.06f);
        }
        else {
            put(left + width * 0.06f, top + height * 0.12f,
                    right - width * 0.06f, bottom - height * 0.24f);
            float centerX = (left + right) * 0.5f;
            putLine(centerX, bottom - height * 0.24f, centerX, bottom - height * 0.08f);
            putLine(centerX - width * 0.18f, bottom - height * 0.08f,
                    centerX + width * 0.18f, bottom - height * 0.08f);
        }
    }

    private void buildStatusGeometry(float left, float top, float right, float bottom) {
        float radius = (right - left) * 0.24f;
        put((left + right) * 0.5f, (top + bottom) * 0.5f,
                radius, radius * 1.42f);
    }

    private void drawFps(Canvas canvas) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidth);
        for (int i = 0; i < 3; i++) {
            paint.setColor(i < variant ? semanticColor : colorMuted);
            paint.setAlpha(i < variant ? 255 : 90);
            int offset = i * 4;
            canvas.drawLine(geometry[offset], geometry[offset + 1],
                    geometry[offset + 2], geometry[offset + 3], paint);
        }
        paint.setAlpha(255);
    }

    private void drawHdr(Canvas canvas) {
        if (geometryCount < 3) {
            return;
        }
        paint.setColor(semanticColor);
        paint.setStrokeWidth(strokeWidth);
        paint.setStyle(variant == VARIANT_TWO ? Paint.Style.FILL : Paint.Style.STROKE);
        canvas.drawCircle(geometry[0], geometry[1], geometry[2], paint);
        paint.setStyle(Paint.Style.STROKE);
        for (int i = 3; i + 3 < geometryCount; i += 4) {
            canvas.drawLine(geometry[i], geometry[i + 1],
                    geometry[i + 2], geometry[i + 3], paint);
        }
    }

    private void drawVideoRange(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 4; i++) {
            int luminance = variant == VARIANT_ONE ? i * 85 : 32 + i * 64;
            paint.setColor(Color.rgb(luminance, luminance, luminance));
            int offset = i * 4;
            scratchRect.set(geometry[offset], geometry[offset + 1],
                    geometry[offset + 2], geometry[offset + 3]);
            canvas.drawRoundRect(scratchRect, cornerRadius, cornerRadius, paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(thinStrokeWidth);
        paint.setColor(colorMuted);
        scratchRect.set(geometry[0], geometry[1], geometry[14], geometry[15]);
        canvas.drawRoundRect(scratchRect, cornerRadius, cornerRadius, paint);
    }

    private void drawFramePacing(Canvas canvas) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(thinStrokeWidth);
        paint.setColor(semanticColor);
        canvas.drawLines(geometry, 0, geometryCount, paint);
    }

    private void drawAudio(Canvas canvas) {
        if (geometryCount < 12) {
            return;
        }
        paint.setColor(semanticColor);
        paint.setStrokeWidth(strokeWidth);
        paint.setStyle(Paint.Style.STROKE);
        scratchRect.set(geometry[0], geometry[1], geometry[2], geometry[3]);
        canvas.drawRoundRect(scratchRect, cornerRadius, cornerRadius, paint);
        canvas.drawLine(geometry[4], geometry[5], geometry[6], geometry[7], paint);
        canvas.drawLine(geometry[8], geometry[9], geometry[10], geometry[11], paint);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 12; i + 2 < geometryCount; i += 3) {
            canvas.drawCircle(geometry[i], geometry[i + 1], geometry[i + 2], paint);
        }
    }

    private void drawProducer(Canvas canvas) {
        if (geometryCount < 12) {
            return;
        }
        paint.setColor(semanticColor);
        paint.setStrokeWidth(strokeWidth);
        paint.setStyle(Paint.Style.STROKE);
        if (variant == VARIANT_ONE) {
            scratchRect.set(geometry[0], geometry[1], geometry[2], geometry[3]);
            canvas.drawArc(scratchRect, 200f, 140f, false, paint);
            scratchRect.set(geometry[4], geometry[5], geometry[6], geometry[7]);
            canvas.drawRoundRect(scratchRect, cornerRadius, cornerRadius, paint);
            scratchRect.set(geometry[8], geometry[9], geometry[10], geometry[11]);
            canvas.drawRoundRect(scratchRect, cornerRadius, cornerRadius, paint);
        }
        else {
            scratchRect.set(geometry[0], geometry[1], geometry[2], geometry[3]);
            canvas.drawRoundRect(scratchRect, cornerRadius, cornerRadius, paint);
            canvas.drawLine(geometry[4], geometry[5], geometry[6], geometry[7], paint);
            canvas.drawLine(geometry[8], geometry[9], geometry[10], geometry[11], paint);
        }
    }

    private void drawStatus(Canvas canvas) {
        if (geometryCount < 4) {
            return;
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(semanticColor);
        paint.setAlpha(58);
        canvas.drawCircle(geometry[0], geometry[1], geometry[3], paint);
        paint.setAlpha(255);
        canvas.drawCircle(geometry[0], geometry[1], geometry[2], paint);
    }

    private void put(float a, float b, float c) {
        geometry[geometryCount++] = a;
        geometry[geometryCount++] = b;
        geometry[geometryCount++] = c;
    }

    private void put(float a, float b, float c, float d) {
        geometry[geometryCount++] = a;
        geometry[geometryCount++] = b;
        geometry[geometryCount++] = c;
        geometry[geometryCount++] = d;
    }

    private void putLine(float x1, float y1, float x2, float y2) {
        put(x1, y1, x2, y2);
    }

    private boolean matches(String... candidates) {
        for (String candidate : candidates) {
            if (stableValue.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }

    private static int parsePositiveInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return Math.max(0, parsed);
        }
        catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private int color(int resourceId) {
        return androidx.core.content.ContextCompat.getColor(getContext(), resourceId);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // Deterministic package-test state. These methods do not participate in drawing.
    int resolvedVariant() {
        return variant;
    }

    int resolvedSemanticColor() {
        return semanticColor;
    }

    int resolvedAudioDotCount() {
        return audioDotCount;
    }

    int geometryCount() {
        return geometryCount;
    }

    float geometryAt(int index) {
        if (index < 0 || index >= geometryCount) {
            throw new IndexOutOfBoundsException("geometry index " + index);
        }
        return geometry[index];
    }

    private static final float[] PACING_LATENCY = {0.05f, 0.20f, 0.40f, 0.65f, 0.94f};
    private static final float[] PACING_BALANCED = {0.06f, 0.28f, 0.50f, 0.72f, 0.94f};
    private static final float[] PACING_CAPPED = {0.08f, 0.30f, 0.52f, 0.74f, 0.88f};
    private static final float[] PACING_SMOOTH = {0.06f, 0.28f, 0.50f, 0.72f, 0.94f};
}
