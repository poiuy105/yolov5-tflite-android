package com.example.yolov5tfliteandroid.utils;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.graphics.SurfaceTexture;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.widget.Toast;

import androidx.camera.core.AspectRatio;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;

public class CameraProcess {

    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private int REQUEST_CODE_PERMISSIONS = 1001;
    private final String[] REQUIRED_PERMISSIONS = new String[]{"android.permission.CAMERA"};
    private int currentLensFacing = CameraSelector.LENS_FACING_BACK;

    public boolean allPermissionsGranted(Context context) {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    public void requestPermissions(Activity activity) {
        ActivityCompat.requestPermissions(activity, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
    }

    public void setFrontCamera(boolean front) {
        currentLensFacing = front ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
    }

    public boolean isFrontCamera() {
        return currentLensFacing == CameraSelector.LENS_FACING_FRONT;
    }

    /**
     * 打开摄像头
     */
    public void startCamera(Context context, ImageAnalysis.Analyzer analyzer, PreviewView previewView) {
        startCamera(context, analyzer, previewView, null);
    }

    public void startCamera(Context context, ImageAnalysis.Analyzer analyzer, PreviewView previewView, CameraErrorCallback errorCallback) {
        cameraProviderFuture = ProcessCameraProvider.getInstance(context);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(context), analyzer);
                Preview previewBuilder = new Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .build();
                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(currentLensFacing).build();
                previewBuilder.setSurfaceProvider(previewView.getSurfaceProvider());
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle((LifecycleOwner) context, cameraSelector, imageAnalysis, previewBuilder);

            } catch (ExecutionException | InterruptedException e) {
                Log.e("CameraProcess", "Failed to start camera: " + e.getMessage(), e);
                if (errorCallback != null) {
                    errorCallback.onCameraError("Camera initialization failed: " + e.getMessage());
                }
            }
        }, ContextCompat.getMainExecutor(context));
    }

    public interface CameraErrorCallback {
        void onCameraError(String message);
    }

    public void showCameraSupportSize(Activity activity) {
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics cc = manager.getCameraCharacteristics(id);
                if (cc.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    Size[] previewSizes = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                            .getOutputSizes(SurfaceTexture.class);
                    for (Size s : previewSizes){
                        Log.i("camera", s.getHeight()+"/"+s.getWidth());
                    }
                    break;
                }
            }
        } catch (Exception e) {
            Log.e("image", "can not open camera", e);
        }
    }

}
