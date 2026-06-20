package com.crowdhuman.detector.utils;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Log;

import androidx.annotation.NonNull;
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
    private volatile boolean isStarting = false;
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

    /**
     * Query the largest YUV_420_888 analysis resolution capped at 640x480.
     * Using the full sensor resolution causes severe CPU bottleneck in YUV→RGB conversion.
     * 640x480 is a good balance: fast preprocessing, negligible accuracy impact
     * since model input is only 320x320.
     */
    private static final int MAX_ANALYSIS_WIDTH = 320;
    private static final int MAX_ANALYSIS_HEIGHT = 240;

    private android.util.Size getMaxAnalysisResolution(@NonNull Context context, int lensFacing) {
        CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraId : cm.getCameraIdList()) {
                CameraCharacteristics chars = cm.getCameraCharacteristics(cameraId);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == lensFacing) {
                    StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (map == null) continue;
                    android.util.Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
                    if (sizes == null || sizes.length == 0) continue;

                    // Pick the largest resolution that does not exceed MAX_ANALYSIS dimensions
                    android.util.Size best = null;
                    for (android.util.Size s : sizes) {
                        if (s.getWidth() <= MAX_ANALYSIS_WIDTH && s.getHeight() <= MAX_ANALYSIS_HEIGHT) {
                            if (best == null || s.getWidth() * s.getHeight() > best.getWidth() * best.getHeight()) {
                                best = s;
                            }
                        }
                    }
                    if (best != null) {
                        Log.i("CameraProcess", "Selected analysis resolution for facing=" + lensFacing
                                + ": " + best.getWidth() + "x" + best.getHeight());
                        return best;
                    }
                    // If no resolution fits within cap, pick the smallest available
                    android.util.Size min = sizes[0];
                    for (android.util.Size s : sizes) {
                        if (s.getWidth() * s.getHeight() < min.getWidth() * min.getHeight()) {
                            min = s;
                        }
                    }
                    Log.w("CameraProcess", "No resolution within cap, using smallest: "
                            + min.getWidth() + "x" + min.getHeight());
                    return min;
                }
            }
        } catch (CameraAccessException e) {
            Log.e("CameraProcess", "Failed to query camera resolutions", e);
        }
        // Fallback
        Log.w("CameraProcess", "Could not query resolution, using fallback 640x480");
        return new android.util.Size(MAX_ANALYSIS_WIDTH, MAX_ANALYSIS_HEIGHT);
    }

    public void startCamera(Context context, ImageAnalysis.Analyzer analyzer, PreviewView previewView) {
        startCamera(context, analyzer, previewView, null);
    }

    public void startCamera(Context context, ImageAnalysis.Analyzer analyzer, PreviewView previewView, CameraErrorCallback errorCallback) {
        if (isStarting) {
            Log.w("CameraProcess", "Camera start already in progress, skipping");
            return;
        }
        isStarting = true;
        cameraProviderFuture = ProcessCameraProvider.getInstance(context);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // Query max resolution via Camera2 characteristics
                android.util.Size maxSize = getMaxAnalysisResolution(context, currentLensFacing);
                Log.i("CameraProcess", "Requesting analysis resolution: " + maxSize.getWidth() + "x" + maxSize.getHeight());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(maxSize)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(context), analyzer);

                // Preview: 4:3 aspect ratio (portrait = 3:4)
                Preview preview = new Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .build();
                CameraSelector selector = new CameraSelector.Builder()
                        .requireLensFacing(currentLensFacing).build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                cameraProvider.unbindAll();
                if (context instanceof LifecycleOwner) {
                    cameraProvider.bindToLifecycle((LifecycleOwner) context, selector, imageAnalysis, preview);
                } else {
                    throw new IllegalArgumentException("Context must be a LifecycleOwner");
                }

            } catch (ExecutionException | InterruptedException e) {
                Log.e("CameraProcess", "Failed to start camera: " + e.getMessage(), e);
                if (errorCallback != null) errorCallback.onCameraError("Camera failed: " + e.getMessage());
            } finally {
                isStarting = false;
            }
        }, ContextCompat.getMainExecutor(context));
    }

    public interface CameraErrorCallback {
        void onCameraError(String message);
    }
}
