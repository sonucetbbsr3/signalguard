package com.example.mybasic.signalguard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Transparent overlay drawn over the camera preview.
 *
 * Features:
 *  • Shows all detected red/bright blobs as pulsing rings.
 *  • Shows a moveable crosshair/target box at the locked point.
 *  • User can tap to move the lock point to any red blob nearby,
 *    or anywhere on screen.
 *  • Crosshair turns green when signal clears (alarm state).
 */
public class OverlayView extends View {

    // ── Blob data (set from MainActivity every sample cycle) ─────────────────
    public static class Blob {
        public float cx, cy;   // centre in view coords (0..1 normalised)
        public int signal;     // SIG_* constant
        public Blob(float cx, float cy, int signal) {
            this.cx = cx; this.cy = cy; this.signal = signal;
        }
    }

    private final List<Blob> blobs = new ArrayList<>();

    // ── Locked target ────────────────────────────────────────────────────────
    private float lockX = 0.5f;   // 0..1 normalised
    private float lockY = 0.5f;
    private boolean hasLock = false;
    private int lockedSignal = 0;
    private boolean alarmState = false;

    // ── Paints ───────────────────────────────────────────────────────────────
    private final Paint crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blobRingPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lockBoxPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ── Pulse animation ──────────────────────────────────────────────────────
    private float pulsePhase = 0f;
    private final Runnable pulseRunnable = new Runnable() {
        @Override public void run() {
            pulsePhase = (pulsePhase + 0.08f) % (float)(Math.PI * 2);
            invalidate();
            postDelayed(this, 40);
        }
    };

    // ── Touch callback ───────────────────────────────────────────────────────
    public interface OnTargetSelectedListener {
        void onTargetSelected(float normX, float normY);
    }
    private OnTargetSelectedListener listener;

    public OverlayView(Context ctx) { super(ctx); init(); }
    public OverlayView(Context ctx, AttributeSet attrs) { super(ctx, attrs); init(); }

    private void init() {
        crosshairPaint.setStyle(Paint.Style.STROKE);
        crosshairPaint.setStrokeWidth(2.5f);
        crosshairPaint.setColor(Color.WHITE);

        blobRingPaint.setStyle(Paint.Style.STROKE);
        blobRingPaint.setStrokeWidth(3f);

        lockBoxPaint.setStyle(Paint.Style.STROKE);
        lockBoxPaint.setStrokeWidth(3f);

        labelPaint.setTextSize(28f);
        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setShadowLayer(4f, 0, 0, Color.BLACK);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(pulseRunnable);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(pulseRunnable);
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public void setOnTargetSelectedListener(OnTargetSelectedListener l) {
        this.listener = l;
    }

    /** Called from sampling thread result — pass detected blobs */
    public void updateBlobs(List<Blob> newBlobs) {
        blobs.clear();
        blobs.addAll(newBlobs);
        postInvalidate();
    }

    /** Move the lock point (called on user tap or programmatically) */
    public void setLockPoint(float normX, float normY) {
        lockX = normX;
        lockY = normY;
        hasLock = true;
        invalidate();
    }

    /** Signal state at locked point */
    public void setLockedSignal(int signal, boolean alarm) {
        this.lockedSignal = signal;
        this.alarmState = alarm;
        postInvalidate();
    }

    public float getLockX() { return lockX; }
    public float getLockY() { return lockY; }
    public boolean hasLock() { return hasLock; }

    // ── Touch ────────────────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            float nx = e.getX() / getWidth();
            float ny = e.getY() / getHeight();

            // Snap to nearest blob within 80px if one exists
            float bestDist = 80f;
            float snapX = nx, snapY = ny;
            for (Blob b : blobs) {
                float bx = b.cx * getWidth();
                float by = b.cy * getHeight();
                float dist = (float) Math.hypot(e.getX() - bx, e.getY() - by);
                if (dist < bestDist) {
                    bestDist = dist;
                    snapX = b.cx;
                    snapY = b.cy;
                }
            }

            setLockPoint(snapX, snapY);
            if (listener != null) listener.onTargetSelected(snapX, snapY);
            return true;
        }
        return false;
    }

    // ── Drawing ──────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        float pulse = (float)(0.5 + 0.5 * Math.sin(pulsePhase));

        // 1. Draw pulsing ring around each detected blob
        for (Blob b : blobs) {
            float bx = b.cx * w;
            float by = b.cy * h;
            int ringColor = signalColor(b.signal);
            blobRingPaint.setColor(ringColor);
            blobRingPaint.setAlpha((int)(150 + 100 * pulse));
            float r = 18f + 8f * pulse;
            canvas.drawCircle(bx, by, r, blobRingPaint);
            // small solid dot at centre
            blobRingPaint.setStyle(Paint.Style.FILL);
            blobRingPaint.setAlpha(200);
            canvas.drawCircle(bx, by, 5f, blobRingPaint);
            blobRingPaint.setStyle(Paint.Style.STROKE);
        }

        // 2. Draw locked target box + crosshair
        float lx = lockX * w;
        float ly = lockY * h;
        float boxSize = 40f;

        // Crosshair lines
        int chColor = alarmState ? Color.GREEN : Color.WHITE;
        crosshairPaint.setColor(chColor);
        crosshairPaint.setAlpha(hasLock ? 220 : 120);
        // horizontal line with gap at centre
        canvas.drawLine(0, ly, lx - boxSize, ly, crosshairPaint);
        canvas.drawLine(lx + boxSize, ly, w, ly, crosshairPaint);
        // vertical line with gap
        canvas.drawLine(lx, 0, lx, ly - boxSize, crosshairPaint);
        canvas.drawLine(lx, ly + boxSize, lx, h, crosshairPaint);

        // Corner brackets of target box
        lockBoxPaint.setColor(hasLock ? chColor : Color.GRAY);
        lockBoxPaint.setAlpha(hasLock ? 255 : 160);
        float bs = boxSize;
        float arm = 14f;
        // top-left
        canvas.drawLine(lx - bs, ly - bs, lx - bs + arm, ly - bs, lockBoxPaint);
        canvas.drawLine(lx - bs, ly - bs, lx - bs, ly - bs + arm, lockBoxPaint);
        // top-right
        canvas.drawLine(lx + bs, ly - bs, lx + bs - arm, ly - bs, lockBoxPaint);
        canvas.drawLine(lx + bs, ly - bs, lx + bs, ly - bs + arm, lockBoxPaint);
        // bottom-left
        canvas.drawLine(lx - bs, ly + bs, lx - bs + arm, ly + bs, lockBoxPaint);
        canvas.drawLine(lx - bs, ly + bs, lx - bs, ly + bs - arm, lockBoxPaint);
        // bottom-right
        canvas.drawLine(lx + bs, ly + bs, lx + bs - arm, ly + bs, lockBoxPaint);
        canvas.drawLine(lx + bs, ly + bs, lx + bs, ly + bs - arm, lockBoxPaint);

        // Alarm: pulsing red glow around box
        if (alarmState) {
            Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            glowPaint.setStyle(Paint.Style.STROKE);
            glowPaint.setStrokeWidth(4f + 4f * pulse);
            glowPaint.setColor(Color.GREEN);
            glowPaint.setAlpha((int)(100 + 155 * pulse));
            RectF rect = new RectF(lx - bs - 8, ly - bs - 8, lx + bs + 8, ly + bs + 8);
            canvas.drawRect(rect, glowPaint);
        }

        // Label if locked on a signal
        if (hasLock && lockedSignal != 0) {
            String label = signalLabel(lockedSignal);
            labelPaint.setColor(signalColor(lockedSignal));
            canvas.drawText(label, lx, ly - bs - 10f, labelPaint);
        }
    }

    private int signalColor(int sig) {
        switch (sig) {
            case 1: return Color.RED;
            case 2: return Color.GREEN;
            case 3: return Color.YELLOW;
            default: return Color.GRAY;
        }
    }

    private String signalLabel(int sig) {
        switch (sig) {
            case 1: return "RED";
            case 2: return "GREEN";
            case 3: return "YELLOW";
            default: return "";
        }
    }
}
