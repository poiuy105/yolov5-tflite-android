package com.crowdhuman.detector.analysis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
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
    private final boolean isFrontCamera;
    private AnalyseCallback callback;
    private Disposable currentDisposable;
    private java.util.Set<Integer> enabledLabels;

    // FPS calculation - P1-6 FIX: volatile for thread visibility
    private volatile long lastFrameTime = 0;
    private volatile float currentFps = 0;
    private static final float FPS_ALPHA = 0.9f;

    // Pre-allocated Bitmaps for reuse (avoid per-frame allocation)
    private Bitmap reusedRotatedBitmap;
    private Bitmap reusedLetterboxBitmap;

    public FullImageAnalyse(Context context, PreviewView previewView, int rotation,
                           Yolov5TFLiteDetector detector, boolean isFrontCamera) {
        this.previewView = previewView;
        this.rotation = rotation;
        this.detector = detector;
        this.imageProcess = new ImageProcess();
        this.isFrontCamera = isFrontCamera;
    }

    public void setCallback(AnalyseCallback callback) {
        this.callback = callback;
    }

    public void setEnabledLabels(java.util.Set<Integer> labels) {
        this.enabledLabels = labels;
    }

    private void ensureRotatedBitmap(int w, int h) {
        if (reusedRotatedBitmap == null || reusedRotatedBitmap.getWidth() != w || reusedRotatedBitmap.getHeight() != h) {
            if (reusedRotatedBitmap != null) reusedRotatedBitmap.recycle();
            reusedRotatedBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
    }

    private void ensureLetterboxBitmap(int size) {
        if (reusedLetterboxBitmap == null || reusedLetterboxBitmap.getWidth() != size) {
            if (reusedLetterboxBitmap != null) reusedLetterboxBitmap.recycle();
            reusedLetterboxBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        }
    }

    public void dispose() {
        if (currentDisposable != null && !currentDisposable.isDisposed()) {
            currentDisposable.dispose();
            currentDisposable = null;
        }
        if (reusedRotatedBitmap != null) {
            reusedRotatedBitmap.recycle();
            reusedRotatedBitmap = null;
        }
        if (reusedLetterboxBitmap != null) {
            reusedLetterboxBitmap.recycle();
            reusedLetterboxBitmap = null;
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

            // Per-stage timing variables
            long t0, t1, t2, t3, t4, t5, t6, t7, t8, t9;

            try {
                // === Stage 1: CameraX toBitmap ===
                t0 = System.currentTimeMillis();
                Bitmap cameraBitmap = image.toBitmap();
                int imgW = cameraBitmap.getWidth();
                int imgH = cameraBitmap.getHeight();
                t1 = System.currentTimeMillis();
                long timeToBitmapMs = t1 - t0;

                // === Stage 2: Rotate ===
                Matrix rotateMatrix = imageProcess.getTransformationMatrix(
                        imgW, imgH, imgH, imgW, 90, false);
                ensureRotatedBitmap(imgH, imgW);
                Canvas rotCanvas = new Canvas(reusedRotatedBitmap);
                rotCanvas.drawBitmap(cameraBitmap, rotateMatrix, null);
                int rotW = reusedRotatedBitmap.getWidth();
                int rotH = reusedRotatedBitmap.getHeight();
                t2 = System.currentTimeMillis();
                long timeRotateMs = t2 - t1;

                // === Stage 3: Letterbox ===
                int modelSize = detector.getInputSize().getWidth();
                float letterScale = Math.min(
                        modelSize / (float) rotW,
                        modelSize / (float) rotH);
                int letterW = (int) (rotW * letterScale);
                int letterH = (int) (rotH * letterScale);
                int padX = (modelSize - letterW) / 2;
                int padY = (modelSize - letterH) / 2;

                ensureLetterboxBitmap(modelSize);
                Canvas letterCanvas = new Canvas(reusedLetterboxBitmap);
                letterCanvas.drawColor(Color.GRAY);
                Matrix letterMatrix = new Matrix();
                letterMatrix.postScale(letterScale, letterScale);
                letterMatrix.postTranslate(padX, padY);
                letterCanvas.drawBitmap(reusedRotatedBitmap, letterMatrix, null);
                t3 = System.currentTimeMillis();
                long timeLetterboxMs = t3 - t2;

                // === Stage 4: Detect (preprocess + inference + decode + NMS + label) ===
                if (enabledLabels != null) {
                    detector.setEnabledLabels(enabledLabels);
                }

                // detectZeroCopy returns timing breakdown via detector
                long[] detectTimings = new long[6]; // preprocess, inference, decode, nms, label, total
                ArrayList<Recognition> recognitions = detector.detectZeroCopyWithTimings(reusedLetterboxBitmap, detectTimings);
                long timePreprocessMs = detectTimings[0];
                long timeInferenceMs = detectTimings[1];
                long timeDecodeMs = detectTimings[2];
                long timeNmsMs = detectTimings[3];
                long timeLabelMs = detectTimings[4];
                t4 = System.currentTimeMillis();

                // === Stage 5: Map coordinates ===
                Matrix modelToRotated = new Matrix();
                modelToRotated.postTranslate(-padX, -padY);
                modelToRotated.postScale(1f / letterScale, 1f / letterScale);
                for (Recognition r : recognitions) {
                    RectF loc = r.getLocation();
                    modelToRotated.mapRect(loc);
                    r.setLocation(loc);
                }

                double previewScale = Math.min(
                        previewHeight / (double) rotH,
                        previewWidth / (double) rotW);
                int renderW = (int) (previewScale * rotW);
                int renderH = (int) (previewScale * rotH);
                int offsetX = (previewWidth - renderW) / 2;
                int offsetY = (previewHeight - renderH) / 2;

                Matrix frameToPreview = new Matrix();
                frameToPreview.postScale((float) previewScale, (float) previewScale);
                t5 = System.currentTimeMillis();
                long timeMapMs = t5 - t4;

                // === Stage 6: Overlay (handled by DetectionOverlayView, minimal) ===
                long timeOverlayMs = 0; // OverlayView just stores data, draw is on next frame

                // Debug info
                String debugInfo = String.format(
                        "cam=%dx%d rot=%dx%d preview=%dx%d letter=%.3f pad=%d,%d render=%dx%d offset=%d,%d",
                        imgW, imgH, rotW, rotH, previewWidth, previewHeight,
                        letterScale, padX, padY, renderW, renderH, offsetX, offsetY);
                Log.d("FullImageAnalyse", debugInfo);

                // Log per-stage timings
                Log.i("PipelineTiming", String.format(
                        "toBitmap=%dms rotate=%dms letterbox=%dms preprocess=%dms inference=%dms decode=%dms nms=%dms label=%dms map=%dms total=%dms",
                        timeToBitmapMs, timeRotateMs, timeLetterboxMs,
                        timePreprocessMs, timeInferenceMs, timeDecodeMs,
                        timeNmsMs, timeLabelMs, timeMapMs,
                        System.currentTimeMillis() - start));

                RectF firstBox = recognitions.isEmpty() ? null : new RectF(recognitions.get(0).getLocation());

                // Calculate FPS
                long now = System.currentTimeMillis();
                if (lastFrameTime > 0) {
                    float instantFps = 1000f / (now - lastFrameTime);
                    currentFps = FPS_ALPHA * currentFps + (1 - FPS_ALPHA) * instantFps;
                }
                lastFrameTime = now;

                long totalTimeMs = now - start;

                emitter.onNext(new AnalyseResult(
                        totalTimeMs, timeInferenceMs, null, recognitions.size(),
                        previewWidth, previewHeight, currentFps, imgW, imgH, debugInfo, firstBox,
                        recognitions, frameToPreview, isFrontCamera, offsetX, offsetY, renderW, renderH,
                        timeToBitmapMs, timeRotateMs, timeLetterboxMs,
                        timePreprocessMs, timeInferenceMs, timeDecodeMs,
                        timeNmsMs, timeLabelMs, timeMapMs, timeOverlayMs));

            } catch (Exception e) {
                Log.e("FullImageAnalyse", "Error: " + e.getMessage(), e);
                emitter.onNext(new AnalyseResult(0, null, 0, previewWidth, previewHeight, currentFps));
            } finally {
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
