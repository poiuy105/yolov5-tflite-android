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
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionFilter;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;

public class CameraProcess {

    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private volatile boolean isStarting = false;
    private static final int REQUEST_CODE_PERMISSIONS = 1001;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{"android.permission.CAMERA"};
    private int currentLensFacing = CameraSelector.LENS_FACING_BACK;

    // Target analysis resolution - configurable via setAnalysisResolution()
    private int targetAnalysisWidth = 640;
    private int targetAnalysisHeight = 480;

    /**
     * 设置分析流分辨率（宽x高，横向）。
     * 设置后需重启相机生效。
     */
    public void setAnalysisResolution(int width, int height) {
        this.targetAnalysisWidth = width;
        this.targetAnalysisHeight = height;
    }

    public int getTargetAnalysisWidth() { return targetAnalysisWidth; }
    public int getTargetAnalysisHeight() { return targetAnalysisHeight; }

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
     * Build a ResolutionSelector that forces the analysis resolution to be
     * at most TARGET_ANALYSIS_WIDTH x TARGET_ANALYSIS_HEIGHT.
     * Uses ResolutionFilter to hard-cap the available resolutions.
     */
    private ResolutionSelector buildAnalysisResolutionSelector() {
        return new ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionFilter(new ResolutionFilter() {
                    @Override
                    public List<android.util.Size> filter(List<android.util.Size> supportedSizes, int rotationDegrees) {
                        List<android.util.Size> filtered = new ArrayList<>();
                        // Only keep resolutions <= target analysis size
                        for (android.util.Size size : supportedSizes) {
                            if (size.getWidth() <= targetAnalysisWidth
                                    && size.getHeight() <= targetAnalysisHeight) {
                                filtered.add(size);
                            }
                        }
                        if (filtered.isEmpty()) {
                            // If nothing fits, pick the smallest available
                            android.util.Size min = supportedSizes.get(0);
                            for (android.util.Size s : supportedSizes) {
                                if (s.getWidth() * s.getHeight() < min.getWidth() * min.getHeight()) {
                                    min = s;
                                }
                            }
                            Log.w("CameraProcess", "No resolution <= " + targetAnalysisWidth + "x" + targetAnalysisHeight
                                    + ", using smallest: " + min.getWidth() + "x" + min.getHeight());
                            filtered.add(min);
                        } else {
                            Log.i("CameraProcess", "ResolutionFilter: " + filtered.size() + " candidates <= "
                                    + targetAnalysisWidth + "x" + targetAnalysisHeight);
                        }
                        return filtered;
                    }
                })
                .build();
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

                // Use ResolutionSelector to force small analysis resolution
                ResolutionSelector analysisSelector = buildAnalysisResolutionSelector();

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setResolutionSelector(analysisSelector)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(context), analyzer);

                Log.i("CameraProcess", "ImageAnalysis configured with ResolutionSelector, RGBA_8888, KEEP_ONLY_LATEST");

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

            } catch (Exception e) {
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
