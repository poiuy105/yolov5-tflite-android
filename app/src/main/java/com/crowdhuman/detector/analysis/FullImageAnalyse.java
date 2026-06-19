package com.crowdhuman.detector.analysis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
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
    private int rotation;
    private final Yolov5TFLiteDetector detector;
    private final ImageProcess imageProcess;
    private final DetectionRenderer renderer;
    private final boolean isFrontCamera;
    private AnalyseCallback callback;
    private Disposable currentDisposable;

    // FPS calculation - P1-6 FIX: volatile for thread visibility
    private volatile long lastFrameTime = 0;
    private volatile float currentFps = 0;
    private static final float FPS_ALPHA = 0.9f;

    // Bitmap pool for reuse (reduce GC pressure)
    private Bitmap pooledImageBitmap;
    private Bitmap pooledFullImageBitmap;
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

    /**
     * Update image rotation offset for detection without restarting camera.
     * @param rotation 0=direct, 90=rotate right, 270=rotate left
     */
    public void setRotation(int rotation) {
        this.rotation = rotation;
    }

    public void dispose() {
        if (currentDisposable != null && !currentDisposable.isDisposed()) {
            currentDisposable.dispose();
            currentDisposable = null;
        }
        // P0-2 FIX: Don't recycle pooled bitmaps in dispose() - they may be in use
        // by an ongoing analysis. They will be recycled when FullImageAnalyse is
        // garbage collected or when dimensions change.
    }

    @Override
    public void analyze(@NonNull ImageProxy image) {
        int previewHeight = previewView.getHeight();
        int previewWidth = previewView.getWidth();
        if (previewWidth <= 0 || previewHeight <= 0) { image.close(); return; }

        dispose();

        currentDisposable = Observable.create((ObservableEmitter<AnalyseResult> emitter) -> {
            long start = System.currentTimeMillis();
            Bitmap cropImageBitmap = null;
            Bitmap modelInputBitmap = null;

            try {
                byte[][] yuvBytes = new byte[3][];
                ImageProxy.PlaneProxy[] planes = image.getPlanes();
                int imgH = image.getHeight(), imgW = image.getWidth();
                imageProcess.fillBytes(planes, yuvBytes);
                int yRowStride = planes[0].getRowStride();
                int uvRowStride = planes[1].getRowStride();
                int uvPixelStride = planes[1].getPixelStride();
                // P2-15 FIX: Reuse or create rgbBytes array
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

                // FIT_CENTER: scale to fit within preview, preserving aspect ratio
                double scale = Math.min(
                        previewHeight / (double) (rotation % 180 == 0 ? imgW : imgH),
                        previewWidth / (double) (rotation % 180 == 0 ? imgH : imgW));
                int scaledW = (int) (scale * imgH), scaledH = (int) (scale * imgW);
                if (scaledW <= 0 || scaledH <= 0) {
                    emitter.onNext(new AnalyseResult(0, null, 0, previewWidth, previewHeight, currentFps));
                    return;
                }

                Matrix transform = imageProcess.getTransformationMatrix(imgW, imgH, scaledW, scaledH, rotation % 180 == 0 ? 90 : 0, false);

                // Create fullImageBitmap at scaled size
                if (pooledFullImageBitmap == null || pooledFullImageBitmap.getWidth() != scaledW || pooledFullImageBitmap.getHeight() != scaledH) {
                    if (pooledFullImageBitmap != null) pooledFullImageBitmap.recycle();
                    pooledFullImageBitmap = Bitmap.createBitmap(scaledW, scaledH, Bitmap.Config.ARGB_8888);
                }
                Canvas canvas = new Canvas(pooledFullImageBitmap);
                canvas.drawBitmap(pooledImageBitmap, transform, null);

                // Crop the actual image region (not black bars)
                cropImageBitmap = Bitmap.createBitmap(pooledFullImageBitmap, 0, 0, scaledW, scaledH);

                Matrix previewToModel = imageProcess.getTransformationMatrix(
                        cropImageBitmap.getWidth(), cropImageBitmap.getHeight(),
                        detector.getInputSize().getWidth(), detector.getInputSize().getHeight(), 0, false);
                modelInputBitmap = Bitmap.createBitmap(cropImageBitmap, 0, 0,
                        cropImageBitmap.getWidth(), cropImageBitmap.getHeight(), previewToModel, false);

                Matrix modelToPreview = new Matrix();
                try { previewToModel.invert(modelToPreview); }
                catch (IllegalArgumentException e) {
                    emitter.onNext(new AnalyseResult(0, null, 0, previewWidth, previewHeight, currentFps));
                    return;
                }

                ArrayList<Recognition> recognitions = detector.detect(modelInputBitmap);

                // Calculate FPS
                long now = System.currentTimeMillis();
                if (lastFrameTime > 0) {
                    float instantFps = 1000f / (now - lastFrameTime);
                    currentFps = FPS_ALPHA * currentFps + (1 - FPS_ALPHA) * instantFps;
                }
                lastFrameTime = now;

                // Calculate black bar offsets for rendering
                int offsetX = (previewWidth - scaledW) / 2;
                int offsetY = (previewHeight - scaledH) / 2;

                Bitmap resultBitmap = renderer.render(recognitions, scaledW, scaledH,
                        modelToPreview, isFrontCamera, currentFps, offsetX, offsetY);
                emitter.onNext(new AnalyseResult(now - start, resultBitmap, recognitions.size(),
                        previewWidth, previewHeight, currentFps));

            } catch (Exception e) {
                Log.e("FullImageAnalyse", "Error: " + e.getMessage(), e);
                emitter.onNext(new AnalyseResult(0, null, 0, previewWidth, previewHeight, currentFps));
            } finally {
                if (cropImageBitmap != null && !cropImageBitmap.isRecycled()) cropImageBitmap.recycle();
                if (modelInputBitmap != null && !modelInputBitmap.isRecycled()) modelInputBitmap.recycle();
                // P0-5 FIX: Note - resultBitmap is passed to MainActivity via callback,
                // which sets it to ImageView. ImageView manages its lifecycle.
                // We must NOT recycle it here.
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
