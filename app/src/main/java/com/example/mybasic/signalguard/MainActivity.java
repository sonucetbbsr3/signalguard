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
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.SeekBar;
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

    // ── Signal Constants ────────────────────────────────────────────────────────
    private static final int SIG_UNKNOWN = 0;
    private static final int SIG_RED     = 1;
    private static final int SIG_GREEN   = 2;    private static final int SIG_YELLOW  = 3;

    // ── Camera & Binding ────────────────────────────────────────────────────
    private ActivityMainBinding binding;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── State ──────────────────────────────────────────────────────────────
    private boolean isWatching = false;
    private boolean alarmActive = false;
    private int lastLockedSignal = SIG_UNKNOWN;
    private boolean settingsVisible = false;

    // ── Screen Settings ─────────────────────────────────────────────────────
    private boolean keepScreenOn = true;
    private boolean allowDimming = false;

    // ── Crosshair / Lock Point ──────────────────────────────────────────────
    private float lockNX = 0.5f;
    private float lockNY = 0.5f;
    private static final float NUDGE_STEP = 0.005f;

    // ── Zoom ────────────────────────────────────────────────────────────────
    private float currentZoom = 1.0f;
    private float maxZoomCamera = 8.0f;
    private Rect sensorArraySize = null;
    private boolean useFrontCamera = false;

    // ── Alarm & Sampling ────────────────────────────────────────────────────
    private ToneGenerator toneGenerator;
    private final Handler alarmHandler = new Handler(Looper.getMainLooper());
    private Runnable alarmRunnable;

    private final Handler samplingHandler = new Handler(Looper.getMainLooper());
    private Runnable samplingRunnable;
    private static final long SAMPLE_MS = 500L;

    private static final int PERM_CODE = 101;

    // ── UI Views ────────────────────────────────────────────────────────────
    private View glassOrbView;
    private SeekBar verticalZoomSeek;
    private TextView btnSettingsCorner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        applyScreenFlags();

        // Find Views
        glassOrbView = findViewById(R.id.glassOrb);
        verticalZoomSeek = findViewById(R.id.verticalZoomSeek);
        btnSettingsCorner = findViewById(R.id.btnSettingsCorner);

        // ── Zoom SeekBar Logic ──
        if (verticalZoomSeek != null) {
            verticalZoomSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    // Map progress (0-100) to zoom (1.0x - maxZoomCamera)
                    float zoom = 1.0f + (progress / 100.0f) * (maxZoomCamera - 1.0f);
                    currentZoom = zoom;
                    applyZoomAtLockPoint(zoom);
                    binding.zoomLabel.setText(String.format("%.1fx", zoom));
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        // ── Overlay: Tap to Lock ──
        binding.overlayView.setOnTargetSelectedListener(new OverlayView.OnTargetSelectedListener() {
            @Override
            public void onTargetSelected(float nx, float ny) {
                lockNX = nx;
                lockNY = ny;
                binding.watchingStatusText.setText("Lock set — watching this point");
            }
        });

        // ── D-Pad: Nudge Crosshair ──
        binding.dpadView.setOnDirectionListener(new DPadView.OnDirectionListener() {
            @Override
            public void onDirection(int dir) {
                switch (dir) {
                    case 0: lockNY = Math.max(0f, lockNY - NUDGE_STEP); break;
                    case 1: lockNY = Math.min(1f, lockNY + NUDGE_STEP); break;
                    case 2: lockNX = Math.max(0f, lockNX - NUDGE_STEP); break;
                    case 3: lockNX = Math.min(1f, lockNX + NUDGE_STEP); break;
                    case 4: lockNX = 0.5f; lockNY = 0.5f; break;
                }
                binding.overlayView.setLockPoint(lockNX, lockNY);
                if (isWatching) {                    binding.watchingStatusText.setText(
                            String.format("Lock: %.0f%% %.0f%%", lockNX * 100, lockNY * 100));
                }
            }
        });

        // ── Settings Button ──
        if (btnSettingsCorner != null) {
            btnSettingsCorner.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleSettings();
                }
            });
        }

        // ── Screen Keep-On Toggle ──
        binding.switchKeepScreenOn.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                keepScreenOn = isChecked;
                binding.switchAllowDimming.setEnabled(isChecked);
                if (!isChecked) {
                    allowDimming = false;
                    binding.switchAllowDimming.setChecked(false);
                }
                applyScreenFlags();
                updateScreenOnChip();
            }
        });

        // ── Allow Dimming Toggle ──
        binding.switchAllowDimming.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                allowDimming = isChecked;
                applyScreenFlags();
            }
        });

        // Init States
        binding.switchKeepScreenOn.setChecked(keepScreenOn);
        binding.switchAllowDimming.setChecked(allowDimming);
        binding.switchAllowDimming.setEnabled(keepScreenOn);

        setupButtons();
        requestPermsIfNeeded();
    }

    @Override    protected void onResume() {
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

    // ═══════════════════════════════════════════════════════════════════════
    // SCREEN FLAGS
    // ═══════════════════════════════════════════════════════════════════════

    private void applyScreenFlags() {
        if (binding == null) return;
        if (keepScreenOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        updateScreenOnChip();
    }

    private void updateScreenOnChip() {
        if (binding == null) return;
        if (keepScreenOn) {
            binding.screenOnChip.setVisibility(View.VISIBLE);
            binding.screenOnChip.setText(allowDimming ? "SCREEN ON (DIM OK)" : "SCREEN ON");
        } else {
            binding.screenOnChip.setVisibility(View.GONE);
        }
    }

    private void toggleSettings() {
        settingsVisible = !settingsVisible;
        binding.settingsPanel.setVisibility(settingsVisible ? View.VISIBLE : View.GONE);        int color = settingsVisible ? 0xFF5599FF : 0xFF8888AA;
        if (btnSettingsCorner != null) btnSettingsCorner.setTextColor(color);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PERMISSIONS
    // ═══════════════════════════════════════════════════════════════════════

    private void requestPermsIfNeeded() {
        List<String> need = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.CAMERA);
        }
        if (!need.isEmpty()) {
            ActivityCompat.requestPermissions(this, need.toArray(new String[0]), PERM_CODE);
        } else {
            setupCameraPreview();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == PERM_CODE) {
            for (int r : results) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Camera permission needed!", Toast.LENGTH_LONG).show();
                    return;
                }
            }
            setupCameraPreview();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CAMERA SETUP
    // ═══════════════════════════════════════════════════════════════════════

    private void setupCameraPreview() {
        binding.cameraPreview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture s, int w, int h) { openCamera(); }
            @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture s, int w, int h) {}
            @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture s) { return true; }
            @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture s) {}
        });
        if (binding.cameraPreview.isAvailable()) openCamera();
    }

    private void openCamera() {        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        CameraManager mgr = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            int wantedFacing = useFrontCamera ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
            String id = null, fallbackId = null;
            for (String cid : mgr.getCameraIdList()) {
                Integer f = mgr.getCameraCharacteristics(cid).get(CameraCharacteristics.LENS_FACING);
                if (fallbackId == null) fallbackId = cid;
                if (f != null && f == wantedFacing) { id = cid; break; }
            }
            if (id == null) id = fallbackId;
            if (id == null) return;

            CameraCharacteristics chars = mgr.getCameraCharacteristics(id);
            sensorArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Float maxZ = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            if (maxZ != null) {
                maxZoomCamera = maxZ;
                mainHandler.post(() -> {
                    if (verticalZoomSeek != null) verticalZoomSeek.setMax(100);
                });
            }
            mgr.openCamera(id, cameraStateCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            Toast.makeText(this, "Camera error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void flipCamera() {
        useFrontCamera = !useFrontCamera;
        currentZoom = 1.0f;
        if (verticalZoomSeek != null) verticalZoomSeek.setProgress(0);
        binding.zoomLabel.setText("1.0x");
        closeCamera();
        openCamera();
    }

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
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
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);
            cameraDevice.createCaptureSession(Arrays.asList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(@NonNull CameraCaptureSession s) {
                            if (cameraDevice == null) return;
                            captureSession = s;
                            try {
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                                captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
                                if (currentZoom > 1.0f) applyZoomAtLockPoint(currentZoom);
                                mainHandler.post(() -> startSamplingLoop());
                            } catch (CameraAccessException e) { e.printStackTrace(); }
                        }
                        @Override public void onConfigureFailed(@NonNull CameraCaptureSession s) {
                            Toast.makeText(MainActivity.this, "Camera session failed", Toast.LENGTH_SHORT).show();
                        }
                    }, backgroundHandler);
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // BACKGROUND THREAD
    // ═══════════════════════════════════════════════════════════════════════

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
    // ═══════════════════════════════════════════════════════════════════════
    // ZOOM AT LOCK POINT
    // ═══════════════════════════════════════════════════════════════════════

    private void applyZoomAtLockPoint(float zoom) {
        if (captureSession == null || previewRequestBuilder == null || sensorArraySize == null) return;
        zoom = Math.max(1f, Math.min(maxZoomCamera, zoom));
        int sW = sensorArraySize.width();
        int sH = sensorArraySize.height();
        int cropW = Math.round(sW / zoom);
        int cropH = Math.round(sH / zoom);

        // Center crop on the lock point (crosshair)
        int cropX = Math.round((sW - cropW) * lockNX);
        int cropY = Math.round((sH - cropH) * lockNY);

        // Ensure crop stays within bounds
        cropX = Math.max(0, Math.min(cropX, sW - cropW));
        cropY = Math.max(0, Math.min(cropY, sH - cropH));

        previewRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, new Rect(cropX, cropY, cropX + cropW, cropY + cropH));
        try {
            captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SAMPLING LOOP
    // ═══════════════════════════════════════════════════════════════════════

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
        if (binding == null || binding.cameraPreview == null) return;
        Bitmap bmp = binding.cameraPreview.getBitmap();
        if (bmp == null) return;        
        int width = bmp.getWidth();
        int height = bmp.getHeight();
        int x = Math.max(0, Math.min(width - 1, (int)(lockNX * width)));
        int y = Math.max(0, Math.min(height - 1, (int)(lockNY * height)));

        int pixel = bmp.getPixel(x, y);
        int r = Color.red(pixel);
        int g = Color.green(pixel);
        int b = Color.blue(pixel);
        int signal = classifySignal(r, g, b);

        mainHandler.post(() -> {
            binding.sampledColorText.setText(String.format("R:%d G:%d B:%d", r, g, b));
            binding.rgbValueText.setText(String.format("R:%d G:%d B:%d", r, g, b));
            binding.signalColorBox.setBackgroundColor(Color.rgb(r, g, b));
            updateGlassOrb(r, g, b);
            updateSignalName(signal);
            if (isWatching && signal != SIG_UNKNOWN && signal != lastLockedSignal) {
                lastLockedSignal = signal;
                triggerAlarm(signal);
            }
        });
        bmp.recycle();
    }

    private void updateGlassOrb(int r, int g, int b) {
        if (glassOrbView == null) return;
        int baseColor = Color.rgb(r, g, b);
        glassOrbView.setBackgroundColor(baseColor);
        glassOrbView.setAlpha(0.9f);
        glassOrbView.setElevation(8f);
    }

    private void updateSignalName(int signal) {
        String signalName;
        int color;
        switch (signal) {
            case SIG_RED:    signalName = "RED";    color = Color.RED;    break;
            case SIG_GREEN:  signalName = "GREEN";  color = Color.GREEN;  break;
            case SIG_YELLOW: signalName = "YELLOW"; color = Color.YELLOW; break;
            default:         signalName = "IDLE";   color = Color.GRAY;   break;
        }
        binding.signalAspectText.setText(signalName);
        binding.signalAspectText.setTextColor(color);
        binding.signalNameText.setText(signalName);
    }

    private int classifySignal(int r, int g, int b) {
        if (r > 180 && g < 100 && b < 100) return SIG_RED;        if (g > 150 && r < 120 && b < 120) return SIG_GREEN;
        if (r > 180 && g > 150 && b < 100) return SIG_YELLOW;
        return SIG_UNKNOWN;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // BUTTONS & WATCHING
    // ═══════════════════════════════════════════════════════════════════════

    private void setupButtons() {
        binding.btnStartStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isWatching) stopWatching();
                else startWatching();
            }
        });
    }

    private void startWatching() {
        isWatching = true;
        alarmActive = false;
        lastLockedSignal = SIG_UNKNOWN;
        binding.btnStartStop.setText("■ STOP");
        binding.btnStartStop.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#B71C1C")));
        binding.watchingStatusText.setText("Watching — tap screen to lock position");
    }

    private void stopWatching() {
        isWatching = false;
        alarmActive = false;
        binding.btnStartStop.setText("▶ START");
        binding.btnStartStop.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#1B5E20")));
        binding.watchingStatusText.setText("Not watching — press START to arm alarm");
        stopAlarm();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ALARM
    // ═══════════════════════════════════════════════════════════════════════

    private void triggerAlarm(int signal) {
        if (alarmActive) return;
        alarmActive = true;
        String signalText;
        switch (signal) {
            case SIG_RED:    signalText = "RED"; break;
            case SIG_GREEN:  signalText = "GREEN"; break;
            case SIG_YELLOW: signalText = "YELLOW"; break;
            default:         signalText = "UNKNOWN"; break;        }
        binding.watchingStatusText.setText("⚠ ALARM: " + signalText + " SIGNAL DETECTED!");
        
        if (toneGenerator == null) {
            toneGenerator = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
        }
        
        alarmRunnable = new Runnable() {
            @Override public void run() {
                if (toneGenerator != null) toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
                Vibrator v = (Vibrator) getSystemService(Vibrator.class);
                if (v != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        v.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        v.vibrate(500);
                    }
                }
            }
        };
        alarmHandler.post(alarmRunnable);
        alarmHandler.postDelayed(alarmRunnable, 1000);
        alarmHandler.postDelayed(alarmRunnable, 2000);
    }

    private void stopAlarm() {
        alarmActive = false;
        alarmHandler.removeCallbacks(alarmRunnable);
        if (toneGenerator != null) {
            toneGenerator.stopTone();
            toneGenerator.release();
            toneGenerator = null;
        }
        if (isWatching) binding.watchingStatusText.setText("Watching — locked on position");
    }
}