package com.crowdhuman.detector;

import android.Manifest;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.crowdhuman.detector.analysis.AnalyseCallback;
import com.crowdhuman.detector.analysis.AnalyseResult;
import com.crowdhuman.detector.analysis.DetectionOverlayView;
import com.crowdhuman.detector.analysis.FullImageAnalyse;
import com.crowdhuman.detector.detector.DetectorCallback;
import com.crowdhuman.detector.detector.Yolov5TFLiteDetector;
import com.crowdhuman.detector.utils.CameraProcess;
import com.crowdhuman.detector.utils.DisplayUtils;
import com.crowdhuman.detector.utils.ScreenshotUtils;
import com.google.android.material.progressindicator.CircularProgressIndicator;

public class MainActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_REQUEST_CODE = 1001;
    private static final int STORAGE_PERMISSION_REQUEST_CODE = 1002;
    private static final String DEFAULT_MODEL = "yolov5n-320";
    private static final int ORIENTATION_AUTO = 0;
    private static final int ORIENTATION_PORTRAIT = 1;
    private static final int ORIENTATION_LANDSCAPE = 2;
    private static final String[] ORIENTATION_LABELS = {"Auto", "Portrait", "Landscape"};

    private boolean isSpinnerInitialized = false;
    private boolean isFrameSpinnerInitialized = false;
    private int orientationMode = ORIENTATION_AUTO;
    private FullImageAnalyse currentAnalyser;
    private Yolov5TFLiteDetector detector;
    private Yolov5TFLiteDetector detectorSmall;  // 160 模型（运动区域推理）
    private final CameraProcess cameraProcess = new CameraProcess();
    private java.util.Set<Integer> enabledLabels = new java.util.HashSet<>();

    // Views
    private PreviewView cameraPreviewMatch;
    private DetectionOverlayView boxLabelCanvas;
    private Spinner modelSpinner;
    private TextView inferenceTimeTextView;
    private Spinner frameSizeSpinner;
    private TextView detectCountTextView;
    private TextView fpsTextView;
    private TextView timingTextView;
    private com.google.android.material.button.MaterialButton timingToggle;
    private boolean showTimingDetail = false;
    private TextView thresholdTextView;
    private SeekBar thresholdSeekBar;
    private SeekBar motionSensSeekBar;
    private TextView motionSensValueText;
    private SeekBar mergeDistSeekBar;
    private TextView mergeDistValueText;
    private ImageView cameraSwitchButton;
    private ImageView screenshotButton;
    private ImageView galleryButton;
    private ImageView rotateButton;
    private ImageView filterButton;
    private CircularProgressIndicator loadingIndicator;
    private View errorPanel;
    private TextView errorText;
    private com.google.android.material.button.MaterialButton retryButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // P3 FIX: Use WindowInsetsController on API 30+, fallback for older versions
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        setStatusBarIconColor(false);

        setContentView(R.layout.activity_main);

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(R.id.root_layout),
            (view, insets) -> {
                androidx.core.graphics.Insets statusBars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.statusBars());
                androidx.core.graphics.Insets navBars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.navigationBars());
                view.setPadding(statusBars.left, statusBars.top, statusBars.right, navBars.bottom);
                return insets;
            });

        bindViews();
        setupListeners();
        initDefaultLabels();

        // Load default model
        initModel(DEFAULT_MODEL);

        // Request permissions
        requestCameraPermission();
    }

    private void setStatusBarIconColor(boolean light) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            View decorView = getWindow().getDecorView();
            int flags = decorView.getSystemUiVisibility();
            if (light) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            decorView.setSystemUiVisibility(flags);
        }
    }

    private void bindViews() {
        cameraPreviewMatch = findViewById(R.id.camera_preview_match);
        cameraPreviewMatch.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        boxLabelCanvas = findViewById(R.id.box_label_canvas);
        modelSpinner = findViewById(R.id.model);
        inferenceTimeTextView = findViewById(R.id.inference_time);
        frameSizeSpinner = findViewById(R.id.frame_size_spinner);
        frameSizeSpinner.setSelection(2); // Default: 640x480 (index 2)
        detectCountTextView = findViewById(R.id.detect_count);
        fpsTextView = findViewById(R.id.fps_text);
        timingTextView = findViewById(R.id.timing_text);
        timingToggle = findViewById(R.id.timing_toggle);
        thresholdTextView = findViewById(R.id.threshold_value);
        thresholdSeekBar = findViewById(R.id.threshold_seekbar);
        motionSensSeekBar = findViewById(R.id.motion_sens_seekbar);
        motionSensValueText = findViewById(R.id.motion_sens_value);
        mergeDistSeekBar = findViewById(R.id.merge_dist_seekbar);
        mergeDistValueText = findViewById(R.id.merge_dist_value);
        cameraSwitchButton = findViewById(R.id.camera_switch);
        screenshotButton = findViewById(R.id.screenshot);
        galleryButton = findViewById(R.id.gallery);
        rotateButton = findViewById(R.id.rotate_button);
        filterButton = findViewById(R.id.filter_button);
        loadingIndicator = findViewById(R.id.loading_indicator);
        errorPanel = findViewById(R.id.error_panel);
        errorText = findViewById(R.id.error_text);
        retryButton = findViewById(R.id.retry_button);
    }

    private void setupListeners() {
        modelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int i, long id) {
                if (!isSpinnerInitialized) { isSpinnerInitialized = true; return; }
                String model = (String) parent.getItemAtPosition(i);
                initModel(model);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        frameSizeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int i, long id) {
                if (!isFrameSpinnerInitialized) { isFrameSpinnerInitialized = true; return; }
                String selected = (String) parent.getItemAtPosition(i);
                // Parse "WxH" format
                String[] parts = selected.split("x");
                if (parts.length == 2) {
                    int w = Integer.parseInt(parts[0]);
                    int h = Integer.parseInt(parts[1]);
                    cameraProcess.setAnalysisResolution(w, h);
                    startCameraWithCurrentMode();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        cameraSwitchButton.setOnClickListener(v -> {
            cameraProcess.setFrontCamera(!cameraProcess.isFrontCamera());
            startCameraWithCurrentMode();
            // P3 FIX: Use Toast with cancel() to avoid queue buildup on rapid switching
            Toast toast = Toast.makeText(this, cameraProcess.isFrontCamera() ? "Front camera" : "Back camera", Toast.LENGTH_SHORT);
            toast.show();
        });

        screenshotButton.setOnClickListener(v -> takeScreenshot());
        timingToggle.setOnClickListener(v -> {
            showTimingDetail = !showTimingDetail;
            timingToggle.setText(showTimingDetail ? "Hide" : "Detail");
        });
        galleryButton.setOnClickListener(v -> Toast.makeText(this, "Gallery detection coming soon", Toast.LENGTH_SHORT).show());

        rotateButton.setOnClickListener(v -> {
            orientationMode = (orientationMode + 1) % 3;
            applyOrientationMode();
        });

        filterButton.setOnClickListener(v -> showClassFilterDialog());

        thresholdSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private float lastThreshold = -1f;
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float threshold = progress / 100.0f;
                thresholdTextView.setText(String.format("Threshold: %.2f", threshold));
                // Sync threshold to ALL NMS processors (320 + 160 + cross-region)
                if (Math.abs(threshold - lastThreshold) > 0.001f) {
                    if (detector != null) detector.setDetectThreshold(threshold);
                    if (detectorSmall != null) detectorSmall.setDetectThreshold(threshold);
                    if (currentAnalyser != null) currentAnalyser.setCrossRegionThreshold(threshold);
                    lastThreshold = threshold;
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Motion sensitivity: progress [0..10] → blockActivateThreshold [12..2]
        // Lower threshold = more blocks activated = more sensitive
        motionSensSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int threshold = 12 - progress;  // progress 0→12, 10→2
                motionSensValueText.setText(String.valueOf(threshold));
                if (currentAnalyser != null) {
                    currentAnalyser.getBlockMotionGrid().setBlockActivateThreshold(threshold);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Merge distance: progress [0..8] → distance [16..144] pixels
        mergeDistSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int distance = 16 + progress * 16;  // 16, 32, 48, ..., 144
                mergeDistValueText.setText(distance + "px");
                if (currentAnalyser != null) {
                    currentAnalyser.getBlockMotionGrid().setMergeDistance(distance);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        retryButton.setOnClickListener(v -> {
            errorPanel.setVisibility(View.GONE);
            initModel(DEFAULT_MODEL);
        });
    }

    private void initModel(String modelKey) {
        // Close old detectors before creating new ones
        if (detectorSmall != null) {
            detectorSmall.close();
            detectorSmall = null;
        }
        if (detector != null) {
            detector.close();
            detector = null;
            // GPU delegate native release is async; brief delay prevents SIGSEGV
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }

        loadingIndicator.setVisibility(View.VISIBLE);
        errorPanel.setVisibility(View.GONE);

        // 创建 320 模型（主模型）
        detector = new Yolov5TFLiteDetector();
        boolean valid = detector.setModelFile(modelKey);
        if (!valid) {
            loadingIndicator.setVisibility(View.GONE);
            showError("Unknown model: " + modelKey);
            return;
        }

        // 预创建 160 模型（运动区域推理），加载失败不影响主流程
        detectorSmall = new Yolov5TFLiteDetector();
        boolean smallValid = detectorSmall.setModelFile("yolov5n-160");
        if (!smallValid) {
            Log.w("MainActivity", "160 model not available, motion region inference disabled");
            detectorSmall = null;
        }

        // 加载 320 模型
        detector.initialModel(this, new DetectorCallback() {
            @Override public void onModelLoaded(String file) {
                detector.initInputBuffer();

                // 加载 160 模型（如果可用）
                if (detectorSmall != null) {
                    detectorSmall.initialModel(MainActivity.this, new DetectorCallback() {
                        @Override public void onModelLoaded(String f) {
                            detectorSmall.initInputBuffer();
                            Log.i("MainActivity", "160 model loaded for motion region inference");
                            loadingIndicator.setVisibility(View.GONE);
                            startCameraWithCurrentMode();
                        }
                        @Override public void onModelError(String msg) {
                            Log.w("MainActivity", "160 model load failed: " + msg + ", using 320 only");
                            detectorSmall = null;
                            loadingIndicator.setVisibility(View.GONE);
                            startCameraWithCurrentMode();
                        }
                    });
                } else {
                    loadingIndicator.setVisibility(View.GONE);
                    startCameraWithCurrentMode();
                }
            }
            @Override public void onModelError(String msg) {
                loadingIndicator.setVisibility(View.GONE);
                showError(msg);
            }
        });
    }

    private void showError(String message) {
        errorText.setText(message);
        errorPanel.setVisibility(View.VISIBLE);
    }

    private void startCameraWithCurrentMode() {
        if (detector == null || detector.getModelFile() == null) return;
        if (currentAnalyser != null) currentAnalyser.dispose();

        int rotation = DisplayUtils.getScreenOrientation(this);

        currentAnalyser = new FullImageAnalyse(this, cameraPreviewMatch, rotation,
                detector, detectorSmall, cameraProcess.isFrontCamera());
        currentAnalyser.setCallback(new AnalyseCallback() {
            @Override
            public void onResult(AnalyseResult result) {
                if (result.recognitions != null) {
                    boxLabelCanvas.updateResults(
                            result.recognitions,
                            result.isFrontCamera,
                            result.fps,
                            result.offsetX,
                            result.offsetY,
                            result.renderWidth,
                            result.renderHeight);
                    // 运动区域可视化
                    boxLabelCanvas.setMotionRegionRects(result.motionRegionRects);
                }
                inferenceTimeTextView.setText(result.costTimeMs + "ms (infer " + result.inferenceTimeMs + "ms)");
                detectCountTextView.setText(String.valueOf(result.detectCount));
                fpsTextView.setText(String.format("FPS: %.1f", result.fps));

                // Per-stage timing breakdown
                String timingStr;
                if (showTimingDetail) {
                    timingStr = String.format(
                            "[1]Camera\u2192Bitmap: %dx%d %dms\n" +
                            "[2]Rotate+Letterbox\u2192%dx%d %dms\n" +
                            "[3]Preprocess\u2192%dx%d %dms\n" +
                            "[4]Inference %dms\n" +
                            "[5]Decode %dms\n" +
                            "[6]NMS %dms\n" +
                            "[7]Map\u2192Preview %dx%d %dms\n" +
                            "[M]Motion: %.4f %s\n" +
                            "[R]Regions: %d %s cropResize=%dms\n" +
                            "Total: %dms",
                            result.imageWidth, result.imageHeight, result.timeToBitmapMs,
                            result.letterboxSize, result.letterboxSize, result.timeLetterboxMs,
                            result.letterboxSize, result.letterboxSize, result.timePreprocessMs,
                            result.timeInferenceMs,
                            result.timeDecodeMs,
                            result.timeNmsMs,
                            result.frameWidth, result.frameHeight, result.timeMapMs,
                            result.motionScore, result.isSkippedFrame ? "SKIP" : "RUN",
                            result.regionCount, result.usedSmallModel ? "160" : "320",
                            result.timeCropResizeMs,
                            result.costTimeMs);
                } else {
                    timingStr = String.format("Total: %dms | FPS: %.1f | %s",
                            result.costTimeMs, result.fps,
                            result.isSkippedFrame ? "SKIP" : "RUN");
                }
                timingTextView.setText(timingStr);

                Log.d("MainActivity", "Debug: " + result.debugInfo);
                if (result.firstBox != null) {
                    Log.d("MainActivity", "First box: " + result.firstBox.toShortString());
                }
            }
            @Override
            public void onError(String message) {
                Log.e("MainActivity", "Analyse error: " + message);
            }
        });

        CameraProcess.CameraErrorCallback errCb = msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        cameraProcess.startCamera(this, currentAnalyser, cameraPreviewMatch, errCb);

        if (currentAnalyser != null) {
            currentAnalyser.setEnabledLabels(new java.util.HashSet<>(enabledLabels));
        }
    }

    private void takeScreenshot() {
        Bitmap cameraBmp = cameraPreviewMatch.getBitmap();
        if (cameraBmp == null) {
            Toast.makeText(this, "No image to save", Toast.LENGTH_SHORT).show();
            return;
        }

        Bitmap merged = null;
        try {
            merged = Bitmap.createBitmap(cameraBmp.getWidth(), cameraBmp.getHeight(), Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            Toast.makeText(this, "Image too large, out of memory", Toast.LENGTH_SHORT).show();
            return;
        }
        Canvas canvas = new Canvas(merged);
        canvas.drawBitmap(cameraBmp, 0, 0, null);
        boxLabelCanvas.draw(canvas);

        ScreenshotUtils.saveToGallery(this, merged, new ScreenshotUtils.SaveCallback() {
            @Override public void onSuccess() { Toast.makeText(MainActivity.this, "Screenshot saved", Toast.LENGTH_SHORT).show(); }
            @Override public void onError(String msg) { Toast.makeText(MainActivity.this, "Save failed: " + msg, Toast.LENGTH_SHORT).show(); }
        });
    }

    private void initDefaultLabels() {
        enabledLabels.clear();
        for (int i = 0; i < 80; i++) {
            enabledLabels.add(i);
        }
    }

    private void showClassFilterDialog() {
        String[] labels = detector.getLabels();
        if (labels.length == 0) {
            Toast.makeText(this, "Labels not loaded yet", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean[] checked = new boolean[labels.length];
        for (int i = 0; i < labels.length; i++) {
            checked[i] = enabledLabels.contains(i);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Filter Classes")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> {
                    if (isChecked) enabledLabels.add(which);
                    else enabledLabels.remove(which);
                })
                .setPositiveButton("OK", (d, which) -> {
                    if (currentAnalyser != null) {
                        currentAnalyser.setEnabledLabels(new java.util.HashSet<>(enabledLabels));
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();

        android.widget.LinearLayout footer = new android.widget.LinearLayout(this);
        footer.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        footer.setGravity(android.view.Gravity.CENTER);
        footer.setPadding(16, 16, 16, 16);

        android.widget.Button btnAll = new android.widget.Button(this);
        btnAll.setText("All");
        btnAll.setOnClickListener(v -> {
            for (int i = 0; i < labels.length; i++) {
                enabledLabels.add(i);
                dialog.getListView().setItemChecked(i, true);
            }
        });

        android.widget.Button btnNone = new android.widget.Button(this);
        btnNone.setText("None");
        btnNone.setOnClickListener(v -> {
            enabledLabels.clear();
            for (int i = 0; i < labels.length; i++) {
                dialog.getListView().setItemChecked(i, false);
            }
        });

        android.widget.Button btnInvert = new android.widget.Button(this);
        btnInvert.setText("Invert");
        btnInvert.setOnClickListener(v -> {
            for (int i = 0; i < labels.length; i++) {
                boolean newState = !enabledLabels.contains(i);
                if (newState) enabledLabels.add(i);
                else enabledLabels.remove(i);
                dialog.getListView().setItemChecked(i, newState);
            }
        });

        footer.addView(btnAll);
        footer.addView(btnNone);
        footer.addView(btnInvert);
        dialog.getListView().addFooterView(footer);
        dialog.show();
    }

    private void applyOrientationMode() {
        switch (orientationMode) {
            case ORIENTATION_AUTO:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
                break;
            case ORIENTATION_PORTRAIT:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                break;
            case ORIENTATION_LANDSCAPE:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                break;
        }
        Toast.makeText(this, "Orientation: " + ORIENTATION_LABELS[orientationMode], Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Delay camera restart to avoid race condition during rotation
        cameraPreviewMatch.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) {
                startCameraWithCurrentMode();
            }
        }, 300);
    }

    private void requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            // Camera OK, also check storage for screenshot
            requestStoragePermission();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
        }
    }

    private void requestStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_REQUEST_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestStoragePermission();
                startCameraWithCurrentMode();
            } else {
                Toast.makeText(this, "Camera permission denied.", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            // Storage permission result - no action needed, MediaStore works without it on Q+
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (currentAnalyser != null) { currentAnalyser.dispose(); currentAnalyser = null; }
        if (detector != null) { detector.close(); detector = null; }
        if (detectorSmall != null) { detectorSmall.close(); detectorSmall = null; }
    }
}
