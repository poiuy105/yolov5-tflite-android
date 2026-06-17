package com.example.yolov5tfliteandroid.utils;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.camera.core.AspectRatio;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;

public class CameraProcess {

    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private static final int REQUEST_CODE_PERMISSIONS = 1001;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{"android.permission.CAMERA"};
    private int currentLensFacing = CameraSelector.LENS_FACING_BACK;

    public boolean allPermissionsGranted(Context context) {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    public void requestPermissions(android.app.Activity activity) {
        androidx.core.app.ActivityCompat.requestPermissions(activity, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
    }

    public void setFrontCamera(boolean front) {
        currentLensFacing = front ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
    }

    public boolean isFrontCamera() {
        return currentLensFacing == CameraSelector.LENS_FACING_FRONT;
    }

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

                Preview preview = new Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .build();
                CameraSelector selector = new CameraSelector.Builder()
                        .requireLensFacing(currentLensFacing).build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                cameraProvider.unbindAll();
                // P1-11 FIX: Check if context is LifecycleOwner before casting
                if (context instanceof LifecycleOwner) {
                    cameraProvider.bindToLifecycle((LifecycleOwner) context, selector, imageAnalysis, preview);
                } else {
                    throw new IllegalArgumentException("Context must be a LifecycleOwner");
                }

            } catch (ExecutionException | InterruptedException e) {
                Log.e("CameraProcess", "Failed to start camera: " + e.getMessage(), e);
                if (errorCallback != null) errorCallback.onCameraError("Camera failed: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(context));
    }

    public interface CameraErrorCallback {
        void onCameraError(String message);
    }
}
