package com.example.mybasic.signalguard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Circular D-pad styled like a real game controller / remote control.
 *
 * Appearance (matching reference image 1):
 *  - Large outer circle with subtle 3-D shading (light from top-left)
 *  - Four directional petals arranged in a cross — each petal slightly
 *    raised-looking with highlight on top edge
 *  - Centre circle (slightly recessed) with a crosshair icon
 *  - Pressed petal darkens for tactile feedback
 *  - Arrow triangles on each petal
 *
 * Touch: tap any quadrant for a single nudge; hold for repeat.
 */
public class DPadView extends View {

    public interface OnDirectionListener {
        /** dir: 0=up, 1=down, 2=left, 3=right, 4=centre */
        void onDirection(int dir);
    }

    private OnDirectionListener listener;
    private int pressedZone = -1;

    // Repeat-on-hold
    private final Handler repeatHandler = new Handler(Looper.getMainLooper());
    private Runnable repeatRunnable;
    private static final long REPEAT_DELAY  = 300L;
    private static final long REPEAT_PERIOD = 100L;

    // Paints — initialised lazily in onSizeChanged when we know the size
    private Paint outerRingPaint;
    private Paint outerShadowPaint;
    private Paint petalBasePaint;
    private Paint petalHighlightPaint;
    private Paint petalPressedPaint;
    private Paint centrePaint;
    private Paint centreRimPaint;
    private Paint arrowPaint;
    private Paint crosshairPaint;
    private Paint separatorPaint;

    // Geometry (computed in onSizeChanged)
    private float cx, cy, outerR, petalR, centreR, innerRingR;
    private boolean geometryReady = false;

    public DPadView(Context ctx) { super(ctx); }
    public DPadView(Context ctx, AttributeSet a) { super(ctx, a); }

    public void setOnDirectionListener(OnDirectionListener l) { this.listener = l; }

    // ── Geometry ─────────────────────────────────────────────────────────────

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        cx = w / 2f;
        cy = h / 2f;
        outerR    = Math.min(w, h) / 2f - 4f;
        petalR    = outerR * 0.92f;
        centreR   = outerR * 0.28f;
        innerRingR = outerR * 0.32f;
        initPaints();
        geometryReady = true;
    }

    private void initPaints() {
        // Outer ring — dark grey with gradient for 3-D effect
        outerRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        outerRingPaint.setShader(new RadialGradient(
                cx - outerR * 0.2f, cy - outerR * 0.3f, outerR * 1.4f,
                new int[]{0xFF4A4A55, 0xFF1E1E26, 0xFF0A0A10},
                new float[]{0f, 0.6f, 1f},
                Shader.TileMode.CLAMP));

        outerShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        outerShadowPaint.setStyle(Paint.Style.STROKE);
        outerShadowPaint.setStrokeWidth(3f);
        outerShadowPaint.setColor(0xFF000000);
        outerShadowPaint.setAlpha(120);

        // Petal base (normal state) — medium dark grey
        petalBasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        petalBasePaint.setColor(0xFF2E2E3A);

        // Petal highlight (top-left corner of each petal) — subtle lighter rim
        petalHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        petalHighlightPaint.setStyle(Paint.Style.STROKE);
        petalHighlightPaint.setStrokeWidth(1.5f);
        petalHighlightPaint.setColor(0xFF5A5A6A);

        // Pressed petal
        petalPressedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        petalPressedPaint.setColor(0xFF1A1A22);

        // Centre disc
        centrePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        centrePaint.setShader(new RadialGradient(
                cx - centreR * 0.2f, cy - centreR * 0.3f, centreR * 1.5f,
                new int[]{0xFF3A3A48, 0xFF1A1A24},
                null, Shader.TileMode.CLAMP));

        centreRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        centreRimPaint.setStyle(Paint.Style.STROKE);
        centreRimPaint.setStrokeWidth(2f);
        centreRimPaint.setColor(0xFF55556A);

        // Arrow triangles on petals
        arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arrowPaint.setColor(0xFFCCCCDD);
        arrowPaint.setStyle(Paint.Style.FILL);

        // Crosshair on centre
        crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        crosshairPaint.setColor(0xFFBBBBCC);
        crosshairPaint.setStyle(Paint.Style.STROKE);
        crosshairPaint.setStrokeWidth(1.8f);

        // Separator lines between petals
        separatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        separatorPaint.setStyle(Paint.Style.STROKE);
        separatorPaint.setStrokeWidth(1.5f);
        separatorPaint.setColor(0xFF000000);
        separatorPaint.setAlpha(180);
    }

    // ── Hit-testing ──────────────────────────────────────────────────────────

    private int zoneAt(float tx, float ty) {
        float dx = tx - cx, dy = ty - cy;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);

        // Must be within the outer circle
        if (dist > outerR) return -1;

        // Centre circle
        if (dist < innerRingR) return 4;

        // Determine quadrant by angle — but we use quadrant-style: whichever
        // axis (horizontal or vertical) the touch is further from centre on
        float ax = Math.abs(dx), ay = Math.abs(dy);
        if (ay > ax) {
            return dy < 0 ? 0 : 1; // up / down
        } else {
            return dx < 0 ? 2 : 3; // left / right
        }
    }

    // ── Touch ────────────────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                int zone = zoneAt(e.getX(), e.getY());
                if (zone < 0) return false;
                pressedZone = zone;
                invalidate();
                fire(zone);
                // Repeat for directional keys only
                if (zone != 4) {
                    repeatRunnable = new Runnable() {
                        @Override public void run() {
                            if (pressedZone >= 0 && pressedZone <= 3) {
                                fire(pressedZone);
                                repeatHandler.postDelayed(this, REPEAT_PERIOD);
                            }
                        }
                    };
                    repeatHandler.postDelayed(repeatRunnable, REPEAT_DELAY);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                repeatHandler.removeCallbacks(repeatRunnable);
                pressedZone = -1;
                invalidate();
                return true;
        }
        return false;
    }

    private void fire(int zone) {
        if (listener != null) listener.onDirection(zone);
    }

    // ── Drawing ──────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!geometryReady) return;

        // ── 1. Outer disc (base) ──────────────────────────────────────────
        canvas.drawCircle(cx, cy, outerR, outerRingPaint);
        // Outer shadow ring
        canvas.drawCircle(cx, cy, outerR - 1f, outerShadowPaint);

        // ── 2. Four petals ────────────────────────────────────────────────
        // Each petal is a circular sector. We draw 4 using clip + arc arcs.
        // Rather than clipping, we draw 4 path segments (pie slices minus centre).
        drawPetal(canvas, 0);  // up
        drawPetal(canvas, 1);  // down
        drawPetal(canvas, 2);  // left
        drawPetal(canvas, 3);  // right

        // ── 3. Separator lines (cross) ────────────────────────────────────
        // Thin dark lines between petals give the "cross" look
        canvas.drawLine(cx, cy - outerR, cx, cy - centreR, separatorPaint);
        canvas.drawLine(cx, cy + centreR, cx, cy + outerR, separatorPaint);
        canvas.drawLine(cx - outerR, cy, cx - centreR, cy, separatorPaint);
        canvas.drawLine(cx + centreR, cy, cx + outerR, cy, separatorPaint);

        // ── 4. Centre disc ────────────────────────────────────────────────
        canvas.drawCircle(cx, cy, centreR, centrePaint);
        canvas.drawCircle(cx, cy, centreR, centreRimPaint);
        // Inner concentric ring (recessed look)
        Paint innerRim = new Paint(Paint.ANTI_ALIAS_FLAG);
        innerRim.setStyle(Paint.Style.STROKE);
        innerRim.setStrokeWidth(1f);
        innerRim.setColor(0xFF333340);
        canvas.drawCircle(cx, cy, centreR * 0.65f, innerRim);
        // Pressed centre
        if (pressedZone == 4) {
            Paint pressedCentre = new Paint(Paint.ANTI_ALIAS_FLAG);
            pressedCentre.setColor(0xFF111118);
            canvas.drawCircle(cx, cy, centreR - 1f, pressedCentre);
        }
        // Crosshair icon on centre
        drawCrosshairIcon(canvas);

        // ── 5. Arrow on each petal ────────────────────────────────────────
        drawArrow(canvas, 0); // up
        drawArrow(canvas, 1); // down
        drawArrow(canvas, 2); // left
        drawArrow(canvas, 3); // right
    }

    /** Draw one petal as a filled arc (annulus sector 90°) */
    private void drawPetal(Canvas canvas, int dir) {
        // Each petal spans 80° centred on its axis (10° gap on each side)
        // Angles in Android: 0=right, 90=down, etc.
        float startAngle, sweepAngle = 80f;
        switch (dir) {
            case 0: startAngle = 270f - 40f; break; // up
            case 1: startAngle =  90f - 40f; break; // down
            case 2: startAngle = 180f - 40f; break; // left
            default:startAngle =   0f - 40f; break; // right
        }

        Path path = new Path();
        RectF outer = new RectF(cx - petalR, cy - petalR, cx + petalR, cy + petalR);
        RectF inner = new RectF(cx - innerRingR, cy - innerRingR,
                                cx + innerRingR, cy + innerRingR);

        path.arcTo(outer, startAngle, sweepAngle, false);
        // reverse back along inner arc
        path.arcTo(inner, startAngle + sweepAngle, -sweepAngle, false);
        path.close();

        boolean pressed = (pressedZone == dir);
        canvas.drawPath(path, pressed ? petalPressedPaint : petalBasePaint);

        // Highlight along the outer edge (gives raised look)
        Paint hlPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        hlPaint.setStyle(Paint.Style.STROKE);
        hlPaint.setStrokeWidth(1.5f);
        hlPaint.setColor(pressed ? 0xFF1E1E28 : 0xFF484856);
        RectF hlRect = new RectF(cx - petalR + 1, cy - petalR + 1,
                                  cx + petalR - 1, cy + petalR - 1);
        canvas.drawArc(hlRect, startAngle, sweepAngle, false, hlPaint);
    }

    /** Draw a filled triangle arrow on the given petal */
    private void drawArrow(Canvas canvas, int dir) {
        // Arrow positioned halfway between innerRingR and petalR
        float arrowDist = (innerRingR + petalR) / 2f;
        float arrowSize = petalR * 0.16f;

        boolean pressed = (pressedZone == dir);
        arrowPaint.setAlpha(pressed ? 140 : 210);

        Path p = new Path();
        switch (dir) {
            case 0: // up
                p.moveTo(cx,              cy - arrowDist - arrowSize);
                p.lineTo(cx - arrowSize,  cy - arrowDist + arrowSize * 0.6f);
                p.lineTo(cx + arrowSize,  cy - arrowDist + arrowSize * 0.6f);
                break;
            case 1: // down
                p.moveTo(cx,              cy + arrowDist + arrowSize);
                p.lineTo(cx - arrowSize,  cy + arrowDist - arrowSize * 0.6f);
                p.lineTo(cx + arrowSize,  cy + arrowDist - arrowSize * 0.6f);
                break;
            case 2: // left
                p.moveTo(cx - arrowDist - arrowSize, cy);
                p.lineTo(cx - arrowDist + arrowSize * 0.6f, cy - arrowSize);
                p.lineTo(cx - arrowDist + arrowSize * 0.6f, cy + arrowSize);
                break;
            case 3: // right
                p.moveTo(cx + arrowDist + arrowSize, cy);
                p.lineTo(cx + arrowDist - arrowSize * 0.6f, cy - arrowSize);
                p.lineTo(cx + arrowDist - arrowSize * 0.6f, cy + arrowSize);
                break;
        }
        p.close();
        canvas.drawPath(p, arrowPaint);
    }

    /** Small crosshair / target icon on the centre button */
    private void drawCrosshairIcon(Canvas canvas) {
        float r = centreR * 0.42f;
        float gap = r * 0.25f;
        // Outer circle
        canvas.drawCircle(cx, cy, r * 0.8f, crosshairPaint);
        // Cross lines with gap at centre
        canvas.drawLine(cx - r, cy, cx - gap, cy, crosshairPaint);
        canvas.drawLine(cx + gap, cy, cx + r, cy, crosshairPaint);
        canvas.drawLine(cx, cy - r, cx, cy - gap, crosshairPaint);
        canvas.drawLine(cx, cy + gap, cx, cy + r, crosshairPaint);
    }
}
