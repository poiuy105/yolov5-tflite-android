package com.example.yolov5tfliteandroid;

import android.Manifest;
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

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.yolov5tfliteandroid.analysis.AnalyseCallback;
import com.example.yolov5tfliteandroid.analysis.AnalyseResult;
import com.example.yolov5tfliteandroid.analysis.FullImageAnalyse;
import com.example.yolov5tfliteandroid.detector.DetectorCallback;
import com.example.yolov5tfliteandroid.detector.Yolov5TFLiteDetector;
import com.example.yolov5tfliteandroid.utils.CameraProcess;
import com.example.yolov5tfliteandroid.utils.DisplayUtils;
import com.example.yolov5tfliteandroid.utils.ScreenshotUtils;
import com.google.android.material.progressindicator.CircularProgressIndicator;

public class MainActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_REQUEST_CODE = 1001;
    private static final int STORAGE_PERMISSION_REQUEST_CODE = 1002;
    private static final String DEFAULT_MODEL = "crowdhuman";

    private boolean isFullScreen = false;
    private boolean isSpinnerInitialized = false;
    private FullImageAnalyse currentAnalyser;
    private Yolov5TFLiteDetector detector;
    private final CameraProcess cameraProcess = new CameraProcess();

    // Views
    private PreviewView cameraPreviewMatch;
    private PreviewView cameraPreviewWrap;
    private ImageView boxLabelCanvas;
    private Spinner modelSpinner;
    private com.google.android.material.switchmaterial.SwitchMaterial immersiveSwitch;
    private TextView inferenceTimeTextView;
    private TextView frameSizeTextView;
    private TextView detectCountTextView;
    private TextView fpsTextView;
    private TextView thresholdTextView;
    private SeekBar thresholdSeekBar;
    private ImageView cameraSwitchButton;
    private ImageView screenshotButton;
    private ImageView galleryButton;
    private CircularProgressIndicator loadingIndicator;
    private View errorPanel;
    private TextView errorText;
    private com.google.android.material.button.MaterialButton retryButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        getWindow().setStatusBarColor(Color.TRANSPARENT);

        bindViews();
        setupListeners();

        // Load default model
        initModel(DEFAULT_MODEL);

        // Request permissions
        requestCameraPermission();
    }

    private void bindViews() {
        cameraPreviewMatch = findViewById(R.id.camera_preview_match);
        cameraPreviewMatch.setScaleType(PreviewView.ScaleType.FILL_START);
        cameraPreviewWrap = findViewById(R.id.camera_preview_wrap);
        boxLabelCanvas = findViewById(R.id.box_label_canvas);
        modelSpinner = findViewById(R.id.model);
        immersiveSwitch = findViewById(R.id.immersive);
        inferenceTimeTextView = findViewById(R.id.inference_time);
        frameSizeTextView = findViewById(R.id.frame_size);
        detectCountTextView = findViewById(R.id.detect_count);
        fpsTextView = findViewById(R.id.fps_text);
        thresholdTextView = findViewById(R.id.threshold_value);
        thresholdSeekBar = findViewById(R.id.threshold_seekbar);
        cameraSwitchButton = findViewById(R.id.camera_switch);
        screenshotButton = findViewById(R.id.screenshot);
        galleryButton = findViewById(R.id.gallery);
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

        immersiveSwitch.setOnCheckedChangeListener((btn, checked) -> {
            isFullScreen = checked;
            startCameraWithCurrentMode();
        });

        cameraSwitchButton.setOnClickListener(v -> {
            cameraProcess.setFrontCamera(!cameraProcess.isFrontCamera());
            startCameraWithCurrentMode();
            Toast.makeText(this, cameraProcess.isFrontCamera() ? "Front camera" : "Back camera", Toast.LENGTH_SHORT).show();
        });

        screenshotButton.setOnClickListener(v -> takeScreenshot());
        galleryButton.setOnClickListener(v -> Toast.makeText(this, "Gallery detection coming soon", Toast.LENGTH_SHORT).show());

        thresholdSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float threshold = progress / 100f;
                thresholdTextView.setText(String.format("Threshold: %.2f", threshold));
                if (detector != null) {
                    detector.setDetectThreshold(threshold);
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
                detector, isFullScreen, cameraProcess.isFrontCamera());
        currentAnalyser.setCallback(new AnalyseCallback() {
            @Override
            public void onResult(AnalyseResult result) {
                if (result.resultBitmap != null) {
                    Drawable prev = boxLabelCanvas.getDrawable();
                    if (prev instanceof BitmapDrawable) {
                        Bitmap prevBmp = ((BitmapDrawable) prev).getBitmap();
                        if (prevBmp != null && !prevBmp.isRecycled()) prevBmp.recycle();
                    }
                    boxLabelCanvas.setImageBitmap(result.resultBitmap);
                }
                frameSizeTextView.setText(result.frameHeight + "x" + result.frameWidth);
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
        if (isFullScreen) {
            cameraPreviewWrap.removeAllViews();
            cameraProcess.startCamera(this, currentAnalyser, cameraPreviewMatch, errCb);
        } else {
            cameraPreviewMatch.removeAllViews();
            cameraProcess.startCamera(this, currentAnalyser, cameraPreviewWrap, errCb);
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

        Bitmap merged = Bitmap.createBitmap(cameraBmp.getWidth(), cameraBmp.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(merged);
        canvas.drawBitmap(cameraBmp, 0, 0, null);
        canvas.drawBitmap(overlayBmp, 0, 0, null);

        ScreenshotUtils.saveToGallery(this, merged, new ScreenshotUtils.SaveCallback() {
            @Override public void onSuccess() { Toast.makeText(MainActivity.this, "Screenshot saved", Toast.LENGTH_SHORT).show(); }
            @Override public void onError(String msg) { Toast.makeText(MainActivity.this, "Save failed: " + msg, Toast.LENGTH_SHORT).show(); }
        });
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
