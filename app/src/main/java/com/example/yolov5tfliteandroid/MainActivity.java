package com.example.yolov5tfliteandroid;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.widget.AdapterView;
import android.widget.ImageView;
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

public class MainActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_REQUEST_CODE = 1001;
    private static final String DEFAULT_MODEL = "crowdhuman";

    private boolean isFullScreen = false;
    private boolean isSpinnerInitialized = false;
    private FullImageAnalyse currentAnalyser;

    private PreviewView cameraPreviewMatch;
    private PreviewView cameraPreviewWrap;
    private ImageView boxLabelCanvas;
    private Spinner modelSpinner;
    private com.google.android.material.switchmaterial.SwitchMaterial immersiveSwitch;
    private TextView inferenceTimeTextView;
    private TextView frameSizeTextView;
    private TextView detectCountTextView;
    private ImageView cameraSwitchButton;
    private ImageView screenshotButton;
    private Yolov5TFLiteDetector detector;
    private final CameraProcess cameraProcess = new CameraProcess();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        getWindow().setStatusBarColor(Color.TRANSPARENT);

        // Bind views
        cameraPreviewMatch = findViewById(R.id.camera_preview_match);
        cameraPreviewMatch.setScaleType(PreviewView.ScaleType.FILL_START);
        cameraPreviewWrap = findViewById(R.id.camera_preview_wrap);
        boxLabelCanvas = findViewById(R.id.box_label_canvas);
        modelSpinner = findViewById(R.id.model);
        immersiveSwitch = findViewById(R.id.immersive);
        inferenceTimeTextView = findViewById(R.id.inference_time);
        frameSizeTextView = findViewById(R.id.frame_size);
        detectCountTextView = findViewById(R.id.detect_count);
        cameraSwitchButton = findViewById(R.id.camera_switch);
        screenshotButton = findViewById(R.id.screenshot);

        // Load default model
        initModel(DEFAULT_MODEL);

        // Model switch
        modelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int i, long id) {
                if (!isSpinnerInitialized) { isSpinnerInitialized = true; return; }
                String model = (String) parent.getItemAtPosition(i);
                Toast.makeText(MainActivity.this, "Loading: " + model, Toast.LENGTH_SHORT).show();
                initModel(model);
                startCameraWithCurrentMode();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Full-screen toggle
        immersiveSwitch.setOnCheckedChangeListener((btn, checked) -> {
            isFullScreen = checked;
            startCameraWithCurrentMode();
        });

        // Camera switch
        cameraSwitchButton.setOnClickListener(v -> {
            cameraProcess.setFrontCamera(!cameraProcess.isFrontCamera());
            startCameraWithCurrentMode();
            Toast.makeText(this, cameraProcess.isFrontCamera() ? "Front camera" : "Back camera", Toast.LENGTH_SHORT).show();
        });

        // Screenshot
        screenshotButton.setOnClickListener(v -> {
            Drawable d = boxLabelCanvas.getDrawable();
            if (d instanceof BitmapDrawable) {
                Bitmap bmp = ((BitmapDrawable) d).getBitmap();
                if (bmp != null && !bmp.isRecycled()) {
                    ScreenshotUtils.saveToGallery(this, bmp.copy(Bitmap.Config.ARGB_8888, false),
                            new ScreenshotUtils.SaveCallback() {
                                @Override public void onSuccess() { Toast.makeText(MainActivity.this, "Screenshot saved", Toast.LENGTH_SHORT).show(); }
                                @Override public void onError(String msg) { Toast.makeText(MainActivity.this, "Save failed: " + msg, Toast.LENGTH_SHORT).show(); }
                            });
                }
            }
        });

        // Permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraWithCurrentMode();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
        }
    }

    private void initModel(String modelKey) {
        detector = new Yolov5TFLiteDetector();
        boolean valid = detector.setModelFile(modelKey);
        if (!valid) {
            Toast.makeText(this, "Unknown model: " + modelKey, Toast.LENGTH_LONG).show();
            return;
        }
        detector.initialModel(this, new DetectorCallback() {
            @Override public void onModelLoaded(String file) {
                Log.i("MainActivity", "Model loaded: " + file);
            }
            @Override public void onModelError(String msg) {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void startCameraWithCurrentMode() {
        if (detector == null || detector.getModelFile() == null) {
            Toast.makeText(this, "Model not loaded. Please restart.", Toast.LENGTH_LONG).show();
            return;
        }
        if (currentAnalyser != null) currentAnalyser.dispose();

        int rotation = DisplayUtils.getScreenOrientation(this);
        currentAnalyser = new FullImageAnalyse(this, cameraPreviewMatch, rotation, detector, isFullScreen);
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

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Camera permission granted", Toast.LENGTH_SHORT).show();
                startCameraWithCurrentMode();
            } else {
                Toast.makeText(this, "Camera permission denied.", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (currentAnalyser != null) { currentAnalyser.dispose(); currentAnalyser = null; }
        if (detector != null) { detector.close(); detector = null; }
    }
}
