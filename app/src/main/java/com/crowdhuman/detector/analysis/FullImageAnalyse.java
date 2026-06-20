package com.crowdhuman.detector.analysis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.util.DisplayMetrics;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.view.PreviewView;

import com.crowdhuman.detector.detector.Yolov5TFLiteDetector;
import com.crowdhuman.detector.utils.ImageProcess;
import com.crowdhuman.detector.utils.Recognition;

import java.util.ArrayList;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.ObservableEmitter;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class FullImageAnalyse implements ImageAnalysis.Analyzer {

    private final PreviewView previewView;
    private final int rotation;
    private final Yolov5TFLiteDetector detector;
    private final ImageProcess imageProcess;
    private final DetectionRenderer renderer;
    private final boolean isFrontCamera;
    private AnalyseCallback callback;
    private Disposable currentDisposable;
    private java.util.Set<Integer> enabledLabels;

    // FPS calculation - P1-6 FIX: volatile for thread visibility
    private volatile long lastFrameTime = 0;
    private volatile float currentFps = 0;
    private static final float FPS_ALPHA = 0.9f;

    // Bitmap pool for reuse (reduce GC pressure)
    private Bitmap pooledImageBitmap;
    // P2-15 FIX: Reuse rgbBytes array to reduce GC pressure
    private int[] pooledRgbBytes;

    public FullImageAnalyse(Context context, PreviewView previewView, int rotation,
                           Yolov5TFLiteDetector detector, boolean isFrontCamera) {
        this.previewView = previewView;
        this.rotation = rotation;
        this.detector = detector;
        this.imageProcess = new ImageProcess();
        this.isFrontCamera = isFrontCamera;
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        this.renderer = new DetectionRenderer(dm.density);
    }

    public void setCallback(AnalyseCallback callback) {
        this.callback = callback;
    }

    public void setEnabledLabels(java.util.Set<Integer> labels) {
        this.enabledLabels = labels;
    }

    public void dispose() {
        if (currentDisposable != null && !currentDisposable.isDisposed()) {
            currentDisposable.dispose();
            currentDisposable = null;
        }
    }

    @Override
    public void analyze(@NonNull ImageProxy image) {
        int previewHeight = previewView.getHeight();
        int previewWidth = previewView.getWidth();
        if (previewWidth <= 0 || previewHeight <= 0) { image.close(); return; }

        dispose();

        currentDisposable = Observable.create((ObservableEmitter<AnalyseResult> emitter) -> {
            long start = System.currentTimeMillis();
            Bitmap modelInputBitmap = null;

            try {
                byte[][] yuvBytes = new byte[3][];
                ImageProxy.PlaneProxy[] planes = image.getPlanes();
                int imgH = image.getHeight(), imgW = image.getWidth();
                imageProcess.fillBytes(planes, yuvBytes);
                int yRowStride = planes[0].getRowStride();
                int uvRowStride = planes[1].getRowStride();
                int uvPixelStride = planes[1].getPixelStride();

                if (pooledRgbBytes == null || pooledRgbBytes.length != imgH * imgW) {
                    pooledRgbBytes = new int[imgH * imgW];
                }
                imageProcess.YUV420ToARGB8888(yuvBytes[0], yuvBytes[1], yuvBytes[2],
                        imgW, imgH, yRowStride, uvRowStride, uvPixelStride, pooledRgbBytes);

                // Reuse or create imageBitmap
                if (pooledImageBitmap == null || pooledImageBitmap.getWidth() != imgW || pooledImageBitmap.getHeight() != imgH) {
                    if (pooledImageBitmap != null) pooledImageBitmap.recycle();
                    pooledImageBitmap = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888);
                }
                pooledImageBitmap.setPixels(pooledRgbBytes, 0, imgW, 0, 0, imgW, imgH);

                // Rotate camera frame to match screen orientation (portrait)
                // Camera sensor is landscape (640x480), screen is portrait (480x640)
                // Use getTransformationMatrix for correct center-based rotation
                Matrix rotateMatrix = imageProcess.getTransformationMatrix(
                        imgW, imgH, imgH, imgW, 90, false);
                Bitmap rotatedBitmap = Bitmap.createBitmap(pooledImageBitmap, 0, 0, imgW, imgH, rotateMatrix, false);
                int rotW = rotatedBitmap.getWidth();  // 480
                int rotH = rotatedBitmap.getHeight(); // 640

                // Step 1: Direct letterbox from rotated camera frame to 320x320
                int modelSize = detector.getInputSize().getWidth(); // 320
                float letterScale = Math.min(
                        modelSize / (float) rotW,
                        modelSize / (float) rotH);
                int letterW = (int) (rotW * letterScale);
                int letterH = (int) (rotH * letterScale);
                int padX = (modelSize - letterW) / 2;
                int padY = (modelSize - letterH) / 2;

                modelInputBitmap = Bitmap.createBitmap(modelSize, modelSize, Bitmap.Config.ARGB_8888);
                Canvas letterCanvas = new Canvas(modelInputBitmap);
                letterCanvas.drawColor(Color.GRAY);

                Matrix letterMatrix = new Matrix();
                letterMatrix.postScale(letterScale, letterScale);
                letterMatrix.postTranslate(padX, padY);
                letterCanvas.drawBitmap(rotatedBitmap, letterMatrix, null);

                // Step 2: Detect
                if (enabledLabels != null) {
                    detector.setEnabledLabels(enabledLabels);
                }
                long inferenceStart = System.currentTimeMillis();
                ArrayList<Recognition> recognitions = detector.detect(modelInputBitmap);
                long inferenceTimeMs = System.currentTimeMillis() - inferenceStart;

                // Step 3: Map detection boxes back to rotated frame coordinates
                // model (320x320) -> rotated frame (rotW x rotH = 480x640)
                Matrix modelToRotated = new Matrix();
                modelToRotated.postTranslate(-padX, -padY);
                modelToRotated.postScale(1f / letterScale, 1f / letterScale);

                for (Recognition r : recognitions) {
                    RectF loc = r.getLocation();
                    modelToRotated.mapRect(loc);
                    r.setLocation(loc);
                }

                // Step 4: Map rotated frame coordinates to preview coordinates for rendering
                // This must match how PreviewView displays the camera feed
                double previewScale = Math.min(
                        previewHeight / (double) rotH,
                        previewWidth / (double) rotW);
                int renderW = (int) (previewScale * rotW);
                int renderH = (int) (previewScale * rotH);
                int offsetX = (previewWidth - renderW) / 2;
                int offsetY = (previewHeight - renderH) / 2;

                Matrix frameToPreview = new Matrix();
                frameToPreview.postScale((float) previewScale, (float) previewScale);
                // No translate here - renderer handles offset via offsetX/offsetY

                // Step 5: Render detection boxes on preview-sized canvas
                Bitmap resultBitmap = renderer.render(recognitions, renderW, renderH,
                        frameToPreview, isFrontCamera, currentFps, offsetX, offsetY);

                // Debug info
                String debugInfo = String.format(
                        "cam=%dx%d rot=%dx%d preview=%dx%d letter=%.3f pad=%d,%d render=%dx%d offset=%d,%d",
                        imgW, imgH, rotW, rotH, previewWidth, previewHeight,
                        letterScale, padX, padY, renderW, renderH, offsetX, offsetY);
                Log.d("FullImageAnalyse", debugInfo);

                RectF firstBox = recognitions.isEmpty() ? null : new RectF(recognitions.get(0).getLocation());

                // Calculate FPS
                long now = System.currentTimeMillis();
                if (lastFrameTime > 0) {
                    float instantFps = 1000f / (now - lastFrameTime);
                    currentFps = FPS_ALPHA * currentFps + (1 - FPS_ALPHA) * instantFps;
                }
                lastFrameTime = now;

                emitter.onNext(new AnalyseResult(now - start, inferenceTimeMs, resultBitmap, recognitions.size(),
                        previewWidth, previewHeight, currentFps, imgW, imgH, debugInfo, firstBox));

            } catch (Exception e) {
                Log.e("FullImageAnalyse", "Error: " + e.getMessage(), e);
                emitter.onNext(new AnalyseResult(0, null, 0, previewWidth, previewHeight, currentFps));
            } finally {
                if (modelInputBitmap != null && !modelInputBitmap.isRecycled()) modelInputBitmap.recycle();
                image.close();
            }
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        result -> { if (callback != null) callback.onResult(result); },
                        error -> { Log.e("FullImageAnalyse", "RxJava error: " + error.getMessage(), error); if (callback != null) callback.onError(error.getMessage()); }
                );
    }
}
