package com.example.mybasic.signalguard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Horizontal barrel zoom dial.
 *
 * Looks like a real camera lens barrel:
 *  - Dark rounded rectangle background
 *  - Vertical tick marks that scroll left/right as you drag, simulating a
 *    rotating barrel — long centre tick, shorter side ticks
 *  - Current zoom level shown as a central indicator line
 *  - Gradient shading on left/right edges for depth effect
 *
 * Drag LEFT  → zoom in (larger number)
 * Drag RIGHT → zoom out (smaller number)
 *
 * Zoom range: 1.0× – 8.0×
 * Callback fires every time zoom changes.
 */
public class ZoomDialView extends View {

    public interface OnZoomChangedListener {
        void onZoomChanged(float zoom);
    }

    private OnZoomChangedListener listener;

    // Zoom state
    private float zoom       = 1.0f;
    private float minZoom    = 1.0f;
    private float maxZoom    = 8.0f;

    // Drag tracking
    private float lastTouchX = 0f;
    // How many pixels of drag = 1× zoom change
    private static final float PX_PER_ZOOM = 60f;

    // Visual: offset of the barrel ticks (pixels, wraps)
    private float barrelOffset = 0f;
    // Spacing between ticks
    private static final float TICK_SPACING = 18f;

    // Paints
    private final Paint bgPaint       = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centrePaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint       gradientPaint;  // initialised in onSizeChanged

    public ZoomDialView(Context ctx) { super(ctx); init(); }
    public ZoomDialView(Context ctx, AttributeSet a) { super(ctx, a); init(); }

    private void init() {
        bgPaint.setColor(0xCC1A1A2E);
        bgPaint.setStyle(Paint.Style.FILL);

        tickPaint.setColor(0xFFAABBCC);
        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeWidth(2f);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);

        centrePaint.setColor(0xFFFFDD44); // yellow centre indicator
        centrePaint.setStyle(Paint.Style.STROKE);
        centrePaint.setStrokeWidth(3f);
        centrePaint.setStrokeCap(Paint.Cap.ROUND);

        borderPaint.setColor(0xFF445566);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1.5f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        // Side gradient to give barrel depth effect
        gradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gradientPaint.setShader(new LinearGradient(
                0, 0, w, 0,
                new int[]{0xCC000000, 0x00000000, 0x00000000, 0xCC000000},
                new float[]{0f, 0.25f, 0.75f, 1f},
                Shader.TileMode.CLAMP));
    }

    public void setOnZoomChangedListener(OnZoomChangedListener l) {
        this.listener = l;
    }

    public float getZoom() { return zoom; }

    public void setMaxZoom(float max) {
        maxZoom = Math.max(1f, max);
        zoom = Math.min(zoom, maxZoom);
    }

    public void resetZoom() {
        zoom = 1.0f;
        barrelOffset = 0f;
        invalidate();
    }

    // ── Touch ────────────────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = e.getX();
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - lastTouchX;
                lastTouchX = e.getX();

                // Drag LEFT = zoom IN (zoom increases, barrel turns one way)
                // Drag RIGHT = zoom OUT
                float zoomDelta = -dx / PX_PER_ZOOM;
                float newZoom = Math.max(minZoom, Math.min(maxZoom, zoom + zoomDelta));

                if (Math.abs(newZoom - zoom) > 0.001f) {
                    // Move barrel offset opposite to drag for realistic rotation feel
                    barrelOffset -= dx;
                    zoom = newZoom;
                    if (listener != null) listener.onZoomChanged(zoom);
                    invalidate();
                }
                return true;
            }
        }
        return false;
    }

    // ── Drawing ──────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int W = getWidth(), H = getHeight();
        if (W == 0 || H == 0) return;

        RectF rect = new RectF(0, 0, W, H);

        // Background
        canvas.drawRoundRect(rect, 14, 14, bgPaint);

        // ── Scrolling tick marks ──────────────────────────────────────────
        // Clip to the view bounds
        canvas.save();
        canvas.clipRect(4, 0, W - 4, H);

        float midX = W / 2f;
        float midY = H / 2f;

        // Calculate which ticks are visible
        // barrelOffset scrolls continuously; we use modulo for wrapping
        float startOffset = (barrelOffset % TICK_SPACING + TICK_SPACING) % TICK_SPACING;
        float startX = -startOffset;

        int tickIdx = 0;
        for (float tx = startX; tx < W + TICK_SPACING; tx += TICK_SPACING, tickIdx++) {
            // Every 5th tick is a major tick (taller)
            // Every 1st of major group gets a tiny top label
            boolean major = (tickIdx % 5 == 0);
            float tickH = major ? H * 0.55f : H * 0.30f;

            // Fade ticks near edges
            float distFromCentre = Math.abs(tx - midX) / midX;
            int alpha = (int)(255 * (1f - distFromCentre * 0.7f));
            alpha = Math.max(30, Math.min(255, alpha));
            tickPaint.setAlpha(alpha);
            tickPaint.setStrokeWidth(major ? 2.5f : 1.5f);

            float top    = midY - tickH / 2f;
            float bottom = midY + tickH / 2f;
            canvas.drawLine(tx, top, tx, bottom, tickPaint);
        }

        canvas.restore();

        // ── Centre indicator (yellow line) ────────────────────────────────
        float indH = H * 0.75f;
        canvas.drawLine(midX, midY - indH / 2f, midX, midY + indH / 2f, centrePaint);

        // Small triangle pointer at top centre
        Paint triPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        triPaint.setColor(0xFFFFDD44);
        triPaint.setStyle(Paint.Style.FILL);
        android.graphics.Path tri = new android.graphics.Path();
        tri.moveTo(midX, 3f);
        tri.lineTo(midX - 6f, 0f);
        tri.lineTo(midX + 6f, 0f);
        tri.close();
        canvas.drawPath(tri, triPaint);

        // ── Gradient overlay (barrel depth effect) ────────────────────────
        if (gradientPaint != null) {
            canvas.drawRoundRect(rect, 14, 14, gradientPaint);
        }

        // ── Border ────────────────────────────────────────────────────────
        canvas.drawRoundRect(rect, 14, 14, borderPaint);

        // ── Zoom label inside dial ────────────────────────────────────────
        Paint textP = new Paint(Paint.ANTI_ALIAS_FLAG);
        textP.setColor(0xFFFFFFFF);
        textP.setTextSize(H * 0.32f);
        textP.setTextAlign(Paint.Align.CENTER);
        textP.setShadowLayer(3f, 0, 0, Color.BLACK);
        // Draw label slightly below centre to not clash with indicator line
        canvas.drawText(String.format("%.1f×", zoom),
                W * 0.82f, midY + textP.getTextSize() * 0.35f, textP);

        // ── "ZOOM" label on left ──────────────────────────────────────────
        Paint smallP = new Paint(Paint.ANTI_ALIAS_FLAG);
        smallP.setColor(0xFF8899AA);
        smallP.setTextSize(H * 0.24f);
        smallP.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("ZOOM", 8f, midY + smallP.getTextSize() * 0.35f, smallP);
    }
}
