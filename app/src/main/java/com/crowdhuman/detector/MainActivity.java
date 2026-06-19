package com.crowdhuman.detector;

import android.Manifest;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
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
    private static final String DEFAULT_MODEL = "yolov5n";
    private static final int ORIENTATION_AUTO = 0;
    private static final int ORIENTATION_PORTRAIT = 1;
    private static final int ORIENTATION_LANDSCAPE = 2;
    private static final String[] ORIENTATION_LABELS = {"Auto", "Portrait", "Landscape"};

    private boolean isSpinnerInitialized = false;
    private int orientationMode = ORIENTATION_AUTO;
    private FullImageAnalyse currentAnalyser;
    private Yolov5TFLiteDetector detector;
    private final CameraProcess cameraProcess = new CameraProcess();
    private java.util.Set<Integer> enabledLabels = new java.util.HashSet<>();

    // Views
    private PreviewView cameraPreviewMatch;
    private ImageView boxLabelCanvas;
    private Spinner modelSpinner;
    private TextView inferenceTimeTextView;
    private TextView frameSizeTextView;
    private TextView detectCountTextView;
    private TextView fpsTextView;
    private TextView thresholdTextView;
    private SeekBar thresholdSeekBar;
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
        frameSizeTextView = findViewById(R.id.frame_size);
        detectCountTextView = findViewById(R.id.detect_count);
        fpsTextView = findViewById(R.id.fps_text);
        thresholdTextView = findViewById(R.id.threshold_value);
        thresholdSeekBar = findViewById(R.id.threshold_seekbar);
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

        cameraSwitchButton.setOnClickListener(v -> {
            cameraProcess.setFrontCamera(!cameraProcess.isFrontCamera());
            startCameraWithCurrentMode();
            // P3 FIX: Use Toast with cancel() to avoid queue buildup on rapid switching
            Toast toast = Toast.makeText(this, cameraProcess.isFrontCamera() ? "Front camera" : "Back camera", Toast.LENGTH_SHORT);
            toast.show();
        });

        screenshotButton.setOnClickListener(v -> takeScreenshot());
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
                // FIX: Use 100.0f for float division, update detector when threshold changes
                if (detector != null && Math.abs(threshold - lastThreshold) > 0.001f) {
                    detector.setDetectThreshold(threshold);
                    lastThreshold = threshold;
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
        // P0-3: Close old detector before creating new one
        if (detector != null) {
            detector.close();
            detector = null;
        }

        loadingIndicator.setVisibility(View.VISIBLE);
        errorPanel.setVisibility(View.GONE);

        detector = new Yolov5TFLiteDetector();
        boolean valid = detector.setModelFile(modelKey);
        if (!valid) {
            loadingIndicator.setVisibility(View.GONE);
            showError("Unknown model: " + modelKey);
            return;
        }

        detector.initialModel(this, new DetectorCallback() {
            @Override public void onModelLoaded(String file) {
                loadingIndicator.setVisibility(View.GONE);
                startCameraWithCurrentMode();
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
                detector, cameraProcess.isFrontCamera());
        currentAnalyser.setCallback(new AnalyseCallback() {
            @Override
            public void onResult(AnalyseResult result) {
                if (result.resultBitmap != null) {
                    boxLabelCanvas.setImageBitmap(result.resultBitmap);
                }
                frameSizeTextView.setText(result.imageHeight + "x" + result.imageWidth);
                inferenceTimeTextView.setText(result.costTimeMs + "ms");
                detectCountTextView.setText(String.valueOf(result.detectCount));
                fpsTextView.setText(String.format("FPS: %.1f", result.fps));
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
        // Merge camera preview + detection overlay
        Bitmap cameraBmp = cameraPreviewMatch.getBitmap();
        Drawable overlay = boxLabelCanvas.getDrawable();
        if (cameraBmp == null || !(overlay instanceof BitmapDrawable)) {
            Toast.makeText(this, "No image to save", Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap overlayBmp = ((BitmapDrawable) overlay).getBitmap();

        // P0-4 FIX: Wrap Bitmap.createBitmap in try-catch for OOM
        Bitmap merged = null;
        try {
            merged = Bitmap.createBitmap(cameraBmp.getWidth(), cameraBmp.getHeight(), Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            Toast.makeText(this, "Image too large, out of memory", Toast.LENGTH_SHORT).show();
            return;
        }
        Canvas canvas = new Canvas(merged);
        canvas.drawBitmap(cameraBmp, 0, 0, null);
        canvas.drawBitmap(overlayBmp, 0, 0, null);

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
    }
}
