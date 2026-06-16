package com.example.yolov5tfliteandroid;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.widget.AdapterView;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.yolov5tfliteandroid.analysis.FullImageAnalyse;
import com.example.yolov5tfliteandroid.detector.Yolov5TFLiteDetector;
import com.example.yolov5tfliteandroid.utils.CameraProcess;

import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_REQUEST_CODE = 1001;

    private boolean IS_FULL_SCREEN = false;
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
    private Yolov5TFLiteDetector yolov5TFLiteDetector;
    private CameraProcess cameraProcess = new CameraProcess();

    protected int getScreenOrientation() {
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_270: return 270;
            case Surface.ROTATION_180: return 180;
            case Surface.ROTATION_90: return 90;
            default: return 0;
        }
    }

    private void initModel(String modelName) {
        try {
            this.yolov5TFLiteDetector = new Yolov5TFLiteDetector();
            this.yolov5TFLiteDetector.setModelFile(modelName);
            this.yolov5TFLiteDetector.initialModel(this);
            Log.i("model", "Success loading model: " + this.yolov5TFLiteDetector.getModelFile());
        } catch (Exception e) {
            Log.e("MainActivity", "load model error: " + e.getMessage(), e);
            Toast.makeText(this, "Failed to load model: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void startCameraWithCurrentMode() {
        if (yolov5TFLiteDetector == null) {
            Log.e("MainActivity", "Detector is null, cannot start camera");
            Toast.makeText(this, "Model not loaded. Please restart the app.", Toast.LENGTH_LONG).show();
            return;
        }

        // Dispose previous analyser
        if (currentAnalyser != null) {
            currentAnalyser.dispose();
        }

        int rotation = getScreenOrientation();
        currentAnalyser = new FullImageAnalyse(
                MainActivity.this,
                cameraPreviewMatch,
                boxLabelCanvas,
                rotation,
                inferenceTimeTextView,
                frameSizeTextView,
                detectCountTextView,
                yolov5TFLiteDetector,
                IS_FULL_SCREEN);

        if (IS_FULL_SCREEN) {
            cameraPreviewWrap.removeAllViews();
            cameraProcess.startCamera(MainActivity.this, currentAnalyser, cameraPreviewMatch,
                    (msg) -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
        } else {
            cameraPreviewMatch.removeAllViews();
            cameraProcess.startCamera(MainActivity.this, currentAnalyser, cameraPreviewWrap,
                    (msg) -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        getWindow().setStatusBarColor(Color.TRANSPARENT);

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

        int rotation = getScreenOrientation();
        Log.i("image", "rotation: " + rotation);

        // Load default model
        initModel("crowdhuman");

        // Model switch
        modelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                if (!isSpinnerInitialized) {
                    isSpinnerInitialized = true;
                    return;
                }
                String model = (String) adapterView.getItemAtPosition(i);
                Toast.makeText(MainActivity.this, "Loading model: " + model, Toast.LENGTH_SHORT).show();
                initModel(model);
                startCameraWithCurrentMode();
            }
            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {}
        });

        // Full-screen toggle
        immersiveSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            IS_FULL_SCREEN = isChecked;
            startCameraWithCurrentMode();
        });

        // Camera switch (front/back)
        cameraSwitchButton.setOnClickListener(v -> {
            cameraProcess.setFrontCamera(!cameraProcess.isFrontCamera());
            startCameraWithCurrentMode();
            Toast.makeText(this,
                    cameraProcess.isFrontCamera() ? "Front camera" : "Back camera",
                    Toast.LENGTH_SHORT).show();
        });

        // Screenshot
        screenshotButton.setOnClickListener(v -> {
            if (currentAnalyser != null) {
                currentAnalyser.setScreenshotListener((bitmap) -> {
                    saveBitmapToGallery(bitmap);
                });
                currentAnalyser.takeScreenshot();
            }
        });

        // Request camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraWithCurrentMode();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST_CODE);
        }
    }

    private void saveBitmapToGallery(Bitmap bitmap) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "detection_" + System.currentTimeMillis() + ".png");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CrowdHuman");
            }
            android.net.Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out != null) {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                        Toast.makeText(this, "Screenshot saved", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Failed to save screenshot: " + e.getMessage(), e);
            Toast.makeText(this, "Failed to save screenshot", Toast.LENGTH_SHORT).show();
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
                Toast.makeText(this, "Camera permission denied. App cannot function without camera.", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (currentAnalyser != null) {
            currentAnalyser.dispose();
            currentAnalyser = null;
        }
        if (yolov5TFLiteDetector != null) {
            yolov5TFLiteDetector.close();
            yolov5TFLiteDetector = null;
        }
    }
}
