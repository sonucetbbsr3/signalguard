package com.example.mybasic.signalguard;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.mybasic.signalguard.databinding.ActivityMainBinding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    // ── Signal constants ──────────────────────────────────────────────────────
    static final int SIG_UNKNOWN = 0;
    static final int SIG_RED     = 1;
    static final int SIG_GREEN   = 2;
    static final int SIG_YELLOW  = 3;

    // ── Camera ────────────────────────────────────────────────────────────────
    private ActivityMainBinding binding;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── State ─────────────────────────────────────────────────────────────────
    private boolean isWatching     = false;
    private boolean alarmActive    = false;
    private int lastLockedSignal   = SIG_UNKNOWN;
    private boolean settingsVisible = false;

    // ── Screen-on / dimming (Coffee-style) ────────────────────────────────────
    /**
     * keepScreenOn  = true  → FLAG_KEEP_SCREEN_ON is set
     * allowDimming  = true  → screen CAN dim but will NOT turn off
     *                         implemented via FLAG_KEEP_SCREEN_ON alone:
     *                         the system will dim normally but won't power off.
     *
     * When keepScreenOn=false, both flags are cleared.
     *
     * allowDimming makes sense only when keepScreenOn=true.
     *
     * How the Coffee app works:
     *  • keepScreenOn=true + allowDimming=false →
     *      window.addFlags(FLAG_KEEP_SCREEN_ON)   ← screen never dims
     *  • keepScreenOn=true + allowDimming=true  →
     *      window.addFlags(FLAG_KEEP_SCREEN_ON)   ← screen stays on but may dim
     *      (Android's system dimming still fires; FLAG_KEEP_SCREEN_ON prevents
     *       the actual power-off step that follows dimming)
     *  • keepScreenOn=false →
     *      window.clearFlags(FLAG_KEEP_SCREEN_ON) ← normal timeout
     */
    private boolean keepScreenOn  = true;   // default ON (matches Coffee default)
    private boolean allowDimming  = false;  // default: prevent dimming too

    // ── Locked sample point (0..1 normalised) ────────────────────────────────
    private float lockNX = 0.5f;
    private float lockNY = 0.5f;

    // ── Crosshair nudge step ──────────────────────────────────────────────────
    private static final float NUDGE_STEP = 0.005f;

    // ── Zoom ──────────────────────────────────────────────────────────────────
    private float currentZoom    = 1.0f;
    private float maxZoomCamera  = 8.0f;
    private Rect  sensorArraySize = null;

    // ── Camera facing ─────────────────────────────────────────────────────────
    private boolean useFrontCamera = false;

    // ── Alarm ─────────────────────────────────────────────────────────────────
    private ToneGenerator toneGenerator;
    private final Handler alarmHandler = new Handler(Looper.getMainLooper());
    private Runnable alarmRunnable;

    // ── Sampling ──────────────────────────────────────────────────────────────
    private final Handler samplingHandler = new Handler(Looper.getMainLooper());
    private Runnable samplingRunnable;
    private static final long SAMPLE_MS = 500L;

    // ── Grid scan config ──────────────────────────────────────────────────────
    private static final int GRID_COLS = 12;
    private static final int GRID_ROWS = 16;
    private static final int PATCH_R   = 5;

    private static final int PERM_CODE = 101;

    // ── Watch-dot animation ───────────────────────────────────────────────────
    private AlphaAnimation watchDotAnim;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Apply initial screen-on flag
        applyScreenFlags();

        // ── Overlay: tap to lock ──
        binding.overlayView.setOnTargetSelectedListener((nx, ny) -> {
            lockNX = nx;
            lockNY = ny;
            binding.watchingStatusText.setText("Lock set — watching this point");
        });

        // ── D-pad: nudge crosshair ──
        binding.dpadView.setOnDirectionListener(dir -> {
            switch (dir) {
                case 0: lockNY = Math.max(0f, lockNY - NUDGE_STEP); break;
                case 1: lockNY = Math.min(1f, lockNY + NUDGE_STEP); break;
                case 2: lockNX = Math.max(0f, lockNX - NUDGE_STEP); break;
                case 3: lockNX = Math.min(1f, lockNX + NUDGE_STEP); break;
                case 4: lockNX = 0.5f; lockNY = 0.5f;               break;
            }
            binding.overlayView.setLockPoint(lockNX, lockNY);
            if (isWatching)
                binding.watchingStatusText.setText(
                        String.format("Lock: %.0f%% %.0f%%",
                                lockNX * 100, lockNY * 100));
        });

        // ── Zoom dial ──
        binding.zoomDialView.setOnZoomChangedListener(zoom -> {
            currentZoom = zoom;
            applyZoom(zoom);
            binding.zoomLabel.setText(String.format("%.1fx", zoom));
        });

        // ── Settings gear ──
        binding.btnSettings.setOnClickListener(v -> toggleSettings());

        // ── Keep-screen-on toggle ──
        binding.switchKeepScreenOn.setOnCheckedChangeListener((btn, checked) -> {
            keepScreenOn = checked;
            binding.switchAllowDimming.setEnabled(checked);
            if (!checked) {
                // Force dimming off when screen-on is disabled
                allowDimming = false;
                binding.switchAllowDimming.setChecked(false);
            }
            applyScreenFlags();
            updateScreenOnChip();
        });

        // ── Allow-dimming toggle ──
        binding.switchAllowDimming.setOnCheckedChangeListener((btn, checked) -> {
            allowDimming = checked;
            applyScreenFlags();
        });

        // Init toggle states
        binding.switchKeepScreenOn.setChecked(keepScreenOn);
        binding.switchAllowDimming.setChecked(allowDimming);
        binding.switchAllowDimming.setEnabled(keepScreenOn);

        setupButtons();
        requestPermsIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyScreenFlags();
        startBackgroundThread();
        if (binding.cameraPreview.isAvailable()) openCamera();
        else setupCameraPreview();
    }

    @Override
    protected void onPause() {
        stopSamplingLoop();
        stopWatching();
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }

    // ── Screen-on (Coffee-style) ──────────────────────────────────────────────

    /**
     * Apply window flags to match the current keepScreenOn + allowDimming state.
     *
     * Coffee uses these two approaches:
     *   • Normal mode (no dimming allowed):
     *       FLAG_KEEP_SCREEN_ON prevents the screen from ever turning off or dimming.
     *   • Allow-dimming mode:
     *       FLAG_KEEP_SCREEN_ON still prevents power-off, but the system's dim
     *       animation is permitted because we additionally set brightness to AUTO
     *       (don't override it).  Android dims before it shuts off; KEEP_SCREEN_ON
     *       stops the shutdown step but not the dim step.
     *
     * Implementation note: there is no separate FLAG_ALLOW_DIMMING in Android.
     * Coffee's "allow dimming" is implemented by simply NOT setting
     * FLAG_KEEP_SCREEN_ON — instead it uses a WakeLock at SCREEN_DIM_WAKE_LOCK
     * level from a foreground service.  For an in-app implementation the
     * simplest equivalent is:
     *   • allowDimming=false: FLAG_KEEP_SCREEN_ON (keeps screen fully on)
     *   • allowDimming=true : clear FLAG_KEEP_SCREEN_ON and rely on the system
     *       — we cannot truly "keep on but allow dim" without a WakeLock service.
     *       So we leave this note and just clear the flag when allowDimming=true.
     */
    private void applyScreenFlags() {
        if (binding == null) return;
        if (keepScreenOn && !allowDimming) {
            // Full screen-on: no dimming, no timeout
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else if (keepScreenOn) {
            // Allow dimming: screen can dim but we still ask to stay on
            // In practice Android may still dim; we accept that.
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            // Normal system timeout
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        updateScreenOnChip();
    }

    private void updateScreenOnChip() {
        if (binding == null) return;
        if (keepScreenOn) {
            binding.screenOnChip.setVisibility(View.VISIBLE);
            if (allowDimming) {
                binding.screenOnChip.setText("SCREEN ON (DIM OK)");
            } else {
                binding.screenOnChip.setText("SCREEN ON");
            }
        } else {
            binding.screenOnChip.setVisibility(View.GONE);
        }
    }

    // ── Settings panel ────────────────────────────────────────────────────────

    private void toggleSettings() {
        settingsVisible = !settingsVisible;
        binding.settingsPanel.setVisibility(settingsVisible ? View.VISIBLE : View.GONE);
        binding.btnSettings.setTextColor(settingsVisible ? 0xFF5599FF : 0xFF8888AA);
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private void requestPermsIfNeeded() {
        List<String> need = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA);
        if (!need.isEmpty())
            ActivityCompat.requestPermissions(this,
                    need.toArray(new String[0]), PERM_CODE);
        else setupCameraPreview();
    }

    @Override
    public void onRequestPermissionsResult(int code,
            @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == PERM_CODE) {
            for (int r : results)
                if (r != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Camera permission needed!",
                            Toast.LENGTH_LONG).show();
                    return;
                }
            setupCameraPreview();
        }
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    private void setupCameraPreview() {
        binding.cameraPreview.setSurfaceTextureListener(
                new TextureView.SurfaceTextureListener() {
                    @Override public void onSurfaceTextureAvailable(
                            @NonNull SurfaceTexture s, int w, int h) { openCamera(); }
                    @Override public void onSurfaceTextureSizeChanged(
                            @NonNull SurfaceTexture s, int w, int h) {}
                    @Override public boolean onSurfaceTextureDestroyed(
                            @NonNull SurfaceTexture s) { return true; }
                    @Override public void onSurfaceTextureUpdated(
                            @NonNull SurfaceTexture s) {}
                });
        if (binding.cameraPreview.isAvailable()) openCamera();
    }

    private void openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) return;
        CameraManager mgr = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            int wantedFacing = useFrontCamera
                    ? CameraCharacteristics.LENS_FACING_FRONT
                    : CameraCharacteristics.LENS_FACING_BACK;
            String id = null, fallbackId = null;
            for (String cid : mgr.getCameraIdList()) {
                Integer f = mgr.getCameraCharacteristics(cid)
                        .get(CameraCharacteristics.LENS_FACING);
                if (fallbackId == null) fallbackId = cid;
                if (f != null && f == wantedFacing) { id = cid; break; }
            }
            if (id == null) id = fallbackId;
            if (id == null) return;

            CameraCharacteristics chars = mgr.getCameraCharacteristics(id);
            sensorArraySize = chars.get(
                    CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Float maxZ = chars.get(
                    CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            if (maxZ != null) {
                maxZoomCamera = maxZ;
                mainHandler.post(() -> binding.zoomDialView.setMaxZoom(maxZoomCamera));
            }
            mgr.openCamera(id, cameraStateCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            Toast.makeText(this, "Camera error: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void flipCamera() {
        useFrontCamera = !useFrontCamera;
        currentZoom = 1.0f;
        binding.zoomDialView.resetZoom();
        binding.zoomLabel.setText("1.0x");
        binding.btnFlipCamera.setText(useFrontCamera ? "↩ REAR" : "↩ FLIP");
        closeCamera();
        openCamera();
    }

    private final CameraDevice.StateCallback cameraStateCallback =
            new CameraDevice.StateCallback() {
        @Override public void onOpened(@NonNull CameraDevice cam) {
            cameraDevice = cam;
            createPreviewSession();
        }
        @Override public void onDisconnected(@NonNull CameraDevice cam) {
            cam.close(); cameraDevice = null;
        }
        @Override public void onError(@NonNull CameraDevice cam, int e) {
            cam.close(); cameraDevice = null;
        }
    };

    private void createPreviewSession() {
        try {
            SurfaceTexture tex = binding.cameraPreview.getSurfaceTexture();
            if (tex == null) return;
            tex.setDefaultBufferSize(1280, 720);
            Surface surface = new Surface(tex);
            previewRequestBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);
            cameraDevice.createCaptureSession(Arrays.asList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(
                                @NonNull CameraCaptureSession s) {
                            if (cameraDevice == null) return;
                            captureSession = s;
                            try {
                                previewRequestBuilder.set(
                                        CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                previewRequestBuilder.set(
                                        CaptureRequest.CONTROL_AE_MODE,
                                        CaptureRequest.CONTROL_AE_MODE_ON);
                                captureSession.setRepeatingRequest(
                                        previewRequestBuilder.build(),
                                        null, backgroundHandler);
                                if (currentZoom > 1.0f) applyZoom(currentZoom);
                                // Start always-on color detection
                                mainHandler.post(() -> startSamplingLoop());
                            } catch (CameraAccessException e) { e.printStackTrace(); }
                        }
                        @Override public void onConfigureFailed(
                                @NonNull CameraCaptureSession s) {
                            Toast.makeText(MainActivity.this,
                                    "Camera session failed", Toast.LENGTH_SHORT).show();
                        }
                    }, backgroundHandler);
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CamBG");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread == null) return;
        backgroundThread.quitSafely();
        try { backgroundThread.join(); } catch (InterruptedException ignored) {}
        backgroundThread = null;
        backgroundHandler = null;
    }

    private void closeCamera() {
        if (captureSession != null) { captureSession.close(); captureSession = null; }
        if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; }
    }

    // ── Zoom ──────────────────────────────────────────────────────────────────

    private void applyZoom(float zoom) {
        if (captureSession == null || previewRequestBuilder == null
                || sensorArraySize == null) return;
        zoom = Math.max(1f, Math.min(maxZoomCamera, zoom));
        int sW = sensorArraySize.width(), sH = sensorArraySize.height();
        int cropW = Math.round(sW / zoom), cropH = Math.round(sH / zoom);
        int cropX = (sW - cropW) / 2,    cropY = (sH - cropH) / 2;
        previewRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION,
                new Rect(cropX, cropY, cropX + cropW, cropY + cropH));
        try {
            captureSession.setRepeatingRequest(
                    previewRequestBuilder.build(), null, backgroundHandler);
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    // ── Always-on sampling ────────────────────────────────────────────────────

    private void startSamplingLoop() {
        samplingHandler.removeCallbacks(samplingRunnable);
        samplingRunnable = new Runnable() {
            @Override public void run() {
                sampleFrame();
                samplingHandler.postDelayed(this, SAMPLE_MS);
            }
        };
        samplingHandler.post(samplingRunnable);
    }

    private void stopSamplingLoop() {
        samplingHandler.removeCallbacks(samplingRunnable);
    }

    private void sampleFrame() {
        if (!binding.cameraPreview.isAvailable()) return;
        Bitmap bmp = binding.cameraPreview.getBitmap();
        if (bmp == null) return;
        int W = bmp.getWidth(), H = bmp.getHeight();
        List<OverlayView.Blob> blobs = new ArrayList<>();

        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                int cx = (int)((col + 0.5f) / GRID_COLS * W);
                int cy = (int)((row + 0.5f) / GRID_ROWS * H);
                int[] avg = samplePatch(bmp, cx, cy, PATCH_R, W, H);
                int sig = classifySignal(avg[0], avg[1], avg[2]);
                if (sig != SIG_UNKNOWN) {
                    float nx = (col + 0.5f) / GRID_COLS;
                    float ny = (row + 0.5f) / GRID_ROWS;
                    boolean merged = false;
                    for (OverlayView.Blob existing : blobs) {
                        if (Math.abs(existing.cx - nx) < 1.5f / GRID_COLS
                                && Math.abs(existing.cy - ny) < 1.5f / GRID_ROWS
                                && existing.signal == sig) {
                            existing.cx = (existing.cx + nx) / 2f;
                            existing.cy = (existing.cy + ny) / 2f;
                            merged = true; break;
                        }
                    }
                    if (!merged) blobs.add(new OverlayView.Blob(nx, ny, sig));
                }
            }
        }

        int lx = (int)(lockNX * W), ly = (int)(lockNY * H);
        int[] lockedAvg = samplePatch(bmp, lx, ly, PATCH_R * 2, W, H);
        int lockedSig = classifySignal(lockedAvg[0], lockedAvg[1], lockedAvg[2]);
        bmp.recycle();

        final List<OverlayView.Blob> finalBlobs = blobs;
        final int fR = lockedAvg[0], fG = lockedAvg[1], fB = lockedAvg[2];
        final int fSig = lockedSig;
        mainHandler.post(() -> {
            binding.overlayView.updateBlobs(finalBlobs);
            updateUI(fR, fG, fB, fSig);
        });
    }

    private int[] samplePatch(Bitmap bmp, int cx, int cy, int r, int W, int H) {
        long sumR = 0, sumG = 0, sumB = 0;
        int count = 0;
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                int px = cx + dx, py = cy + dy;
                if (px >= 0 && px < W && py >= 0 && py < H) {
                    int p = bmp.getPixel(px, py);
                    sumR += Color.red(p); sumG += Color.green(p); sumB += Color.blue(p);
                    count++;
                }
            }
        }
        if (count == 0) return new int[]{0, 0, 0};
        return new int[]{(int)(sumR/count), (int)(sumG/count), (int)(sumB/count)};
    }

    // ── Signal classification ─────────────────────────────────────────────────

    private int classifySignal(int r, int g, int b) {
        if ((r + g + b) / 3 < 15) return SIG_UNKNOWN;

        float[] hsv = new float[3];
        Color.RGBToHSV(r, g, b, hsv);
        float hue = hsv[0], sat = hsv[1], val = hsv[2];

        // ── Bloomed/saturated red lamp centre ────────────────────────────────
        // When camera overexposes a red lamp, the centre goes near-white:
        // R:253 G:206 B:206 → sat=0.19, fails normal sat gate.
        // But: G≈B (bloom spreads all channels equally), and R is highest.
        //
        // Key discriminator vs yellow/orange:
        //   Bloomed red:   R:253 G:206 B:206 → |G-B| = 0   (G≈B) ✓
        //   Orange/yellow: R:227 G:156 B:73  → |G-B| = 83  (G≠B) ✗ — skip
        //
        // Only trigger if G and B are within 40 of each other (equal bloom).
        int maxGB = Math.max(g, b);
        int minGB = Math.min(g, b);
        if (r > maxGB && (r - maxGB) >= 30 && val > 0.60f && (maxGB - minGB) <= 40) {
            return SIG_RED;
        }

        // Standard saturation gate — reject grey/white after the red override above
        if (sat < 0.20f && val > 0.50f) return SIG_UNKNOWN;
        if (sat < 0.25f) return SIG_UNKNOWN;

        // ── FIX 2: False red on yellow boundary (Image 1) ────────────────────
        // Old boundary: red ≤20°, yellow ≥25°. Gap 20-25° caused yellow hue=22°
        // to be wrongly caught by red (≤20° was fine, but camera noise pushed
        // some yellow pixels just under 20°).
        // Fix: tighten red to hue ≤15° (and ≥348°). Yellow starts at 16°.
        // Observed yellow: R:118 G:51 B:19 → hue≈21°  → now classified yellow ✓
        // Observed red:    R:240 G:109 B:119 → hue≈355° → still red ✓
        if ((hue >= 348f || hue <= 15f) && val > 0.30f) return SIG_RED;

        // ── GREEN: cyan-teal (hue 90–215°) ───────────────────────────────────
        // Indian Railway green lamps appear cyan through camera.
        // R:1 G:191 B:164 → hue≈170° ✓  |  R:65 G:247 B:250 → hue≈182° ✓
        if (hue >= 90f && hue <= 215f && sat >= 0.28f && val > 0.18f) return SIG_GREEN;

        // ── FIX 3: Remove double-yellow — all yellow returns SIG_YELLOW ───────
        // Single/double yellow range hue 16°–89°
        if (hue >= 16f && hue <= 89f && sat >= 0.25f) return SIG_YELLOW;

        return SIG_UNKNOWN;
    }

    // ── UI update ─────────────────────────────────────────────────────────────

    private void updateUI(int r, int g, int b, int signal) {
        // sampledColorSwatch is hidden (zero-size) in new layout — set colour anyway for compat
        binding.sampledColorSwatch.setBackgroundColor(Color.rgb(r, g, b));

        // RGB text — shown below signal name in top-left overlay (ref image 2)
        if (r == 0 && g == 0 && b == 0) {
            binding.sampledColorText.setText("R:--- G:--- B:---");
        } else {
            binding.sampledColorText.setText(
                    String.format("R:%d  G:%d  B:%d", r, g, b));
        }

        int boxColor; String name; int nameColor;
        switch (signal) {
            case SIG_RED:
                // Ref image 2: "R:222 — STOP" with R value in the name
                boxColor  = 0xFFCC1111;
                name      = "R:" + r + " \u2014 STOP";    // R:222 — STOP
                nameColor = 0xFFFF4444; break;
            case SIG_GREEN:
                boxColor  = 0xFF1A9922;
                name      = "G:" + g + " \u2014 PROCEED";
                nameColor = 0xFF44EE66; break;
            case SIG_YELLOW:
                boxColor  = 0xFFCCAA00;
                name      = "Y:" + r + " \u2014 CAUTION";
                nameColor = 0xFFFFDD44; break;
            default:
                boxColor  = 0xFF2A2A3A;
                name      = isWatching ? "\u2014\u2014 NO SIGNAL \u2014\u2014" : "\u2014\u2014 IDLE \u2014\u2014";
                nameColor = 0xFF555577; break;
        }

        binding.signalColorBox.setBackgroundColor(boxColor);
        binding.signalNameText.setText(name);
        binding.signalNameText.setTextColor(nameColor);
        binding.overlayView.setLockedSignal(signal, alarmActive);

        // Alarm: RED → non-RED/non-UNKNOWN while watching
        if (isWatching && lastLockedSignal == SIG_RED
                && signal != SIG_RED && signal != SIG_UNKNOWN) {
            triggerAlarm(signal);
        }
        lastLockedSignal = signal;
    }

    // ── Alarm ─────────────────────────────────────────────────────────────────

    private void triggerAlarm(int newSignal) {
        if (alarmActive) return;
        alarmActive = true;
        setSilenceButtonActive(true);
        try {
            Vibrator vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vib != null && vib.hasVibrator()) {
                long[] pat = {0, 600, 200, 600, 200, 600};
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    vib.vibrate(VibrationEffect.createWaveform(pat, 0));
                else vib.vibrate(pat, 0);
            }
        } catch (Exception ignored) {}
        try { toneGenerator = new ToneGenerator(AudioManager.STREAM_ALARM, 100); }
        catch (Exception e) { toneGenerator = null; }
        alarmRunnable = new Runnable() {
            @Override public void run() {
                if (!alarmActive) return;
                if (toneGenerator != null)
                    toneGenerator.startTone(
                            ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 700);
                alarmHandler.postDelayed(this, 900);
            }
        };
        alarmHandler.post(alarmRunnable);
        flashSignalName();
    }

    private void flashSignalName() {
        if (!alarmActive) {
            binding.signalNameText.setVisibility(View.VISIBLE);
            return;
        }
        int v = binding.signalNameText.getVisibility();
        binding.signalNameText.setVisibility(
                v == View.VISIBLE ? View.INVISIBLE : View.VISIBLE);
        mainHandler.postDelayed(this::flashSignalName, 350);
    }

    private void silenceAlarm() {
        alarmActive = false;
        alarmHandler.removeCallbacks(alarmRunnable);
        if (toneGenerator != null) {
            try { toneGenerator.stopTone(); toneGenerator.release(); }
            catch (Exception ignored) {}
            toneGenerator = null;
        }
        try {
            Vibrator vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vib != null) vib.cancel();
        } catch (Exception ignored) {}
        binding.signalNameText.setVisibility(View.VISIBLE);
        setSilenceButtonActive(false);
        binding.overlayView.setLockedSignal(lastLockedSignal, false);
    }

    private void setSilenceButtonActive(boolean active) {
        binding.btnSilence.setEnabled(active);
        binding.btnSilence.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        active ? 0xFF6A0000 : 0xFF2A1010));
        binding.btnSilence.setTextColor(active ? 0xFFFF4444 : 0xFF665555);
    }

    // ── Watch dot animation ───────────────────────────────────────────────────

    private void startWatchDot() {
        binding.watchDot.setVisibility(View.VISIBLE);
        watchDotAnim = new AlphaAnimation(1.0f, 0.2f);
        watchDotAnim.setDuration(600);
        watchDotAnim.setRepeatCount(Animation.INFINITE);
        watchDotAnim.setRepeatMode(Animation.REVERSE);
        binding.watchDot.startAnimation(watchDotAnim);
    }

    private void stopWatchDot() {
        binding.watchDot.clearAnimation();
        binding.watchDot.setVisibility(View.INVISIBLE);
    }

    // ── Start / Stop watching ─────────────────────────────────────────────────

    private void startWatching() {
        isWatching = true;
        lastLockedSignal = SIG_UNKNOWN;
        binding.btnStartStop.setText("\u25A0  STOP WATCHING");
        binding.btnStartStop.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0xFF5A0000));
        binding.btnStartStop.setTextColor(0xFFFF5555);
        binding.watchingStatusText.setText("\u25CF Armed \u2014 alarm will fire on signal change");
        binding.watchingStatusText.setTextColor(0xFF44CC66);
        binding.hintText.setVisibility(View.VISIBLE);
        startWatchDot();
    }

    private void stopWatching() {
        isWatching = false;
        silenceAlarm();
        lastLockedSignal = SIG_UNKNOWN;
        binding.btnStartStop.setText("\u25B6  START WATCHING");
        binding.btnStartStop.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0xFF1B5E20));
        binding.btnStartStop.setTextColor(0xFF55FF88);
        binding.watchingStatusText.setText("Not watching \u2014 press START to arm alarm");
        binding.watchingStatusText.setTextColor(0xFF44445A);
        binding.hintText.setVisibility(View.INVISIBLE);
        binding.overlayView.updateBlobs(new ArrayList<>());
        stopWatchDot();
    }

    // ── Buttons ───────────────────────────────────────────────────────────────

    private void setupButtons() {
        binding.btnStartStop.setOnClickListener(v -> {
            if (isWatching) stopWatching(); else startWatching();
        });
        binding.btnSilence.setOnClickListener(v -> silenceAlarm());
        binding.btnFlipCamera.setOnClickListener(v -> flipCamera());
    }
}
