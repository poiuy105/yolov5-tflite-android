package com.example.yolov5tfliteandroid.analysis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.view.PreviewView;

import com.example.yolov5tfliteandroid.detector.Yolov5TFLiteDetector;
import com.example.yolov5tfliteandroid.utils.ImageProcess;
import com.example.yolov5tfliteandroid.utils.Recognition;

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
    private final boolean useFullScreenCrop;
    private final DetectionRenderer renderer;
    private AnalyseCallback callback;
    private Disposable currentDisposable;

    public FullImageAnalyse(Context context, PreviewView previewView, int rotation,
                           Yolov5TFLiteDetector detector, boolean useFullScreenCrop) {
        this.previewView = previewView;
        this.rotation = rotation;
        this.detector = detector;
        this.imageProcess = new ImageProcess();
        this.useFullScreenCrop = useFullScreenCrop;
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        this.renderer = new DetectionRenderer(dm.density);
    }

    public void setCallback(AnalyseCallback callback) {
        this.callback = callback;
    }

    public Bitmap takeScreenshot() {
        ImageView canvas = null; // caller should hold reference externally
        // Screenshot is now handled externally via AnalyseResult.resultBitmap
        return null;
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
            Bitmap imageBitmap = null, fullImageBitmap = null, cropImageBitmap = null, modelInputBitmap = null;
            try {
                byte[][] yuvBytes = new byte[3][];
                ImageProxy.PlaneProxy[] planes = image.getPlanes();
                int imgH = image.getHeight(), imgW = image.getWidth();
                imageProcess.fillBytes(planes, yuvBytes);
                int yRowStride = planes[0].getRowStride();
                int uvRowStride = planes[1].getRowStride();
                int uvPixelStride = planes[1].getPixelStride();
                int[] rgbBytes = new int[imgH * imgW];
                imageProcess.YUV420ToARGB8888(yuvBytes[0], yuvBytes[1], yuvBytes[2],
                        imgW, imgH, yRowStride, uvRowStride, uvPixelStride, rgbBytes);

                imageBitmap = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888);
                imageBitmap.setPixels(rgbBytes, 0, imgW, 0, 0, imgW, imgH);

                double scale = Math.max(
                        previewHeight / (double) (rotation % 180 == 0 ? imgW : imgH),
                        previewWidth / (double) (rotation % 180 == 0 ? imgH : imgW));
                int scaledW = (int) (scale * imgH), scaledH = (int) (scale * imgW);
                if (scaledW <= 0 || scaledH <= 0) { emitter.onNext(new AnalyseResult(0, null, 0, previewWidth, previewHeight)); return; }

                Matrix transform = imageProcess.getTransformationMatrix(imgW, imgH, scaledW, scaledH, rotation % 180 == 0 ? 90 : 0, false);
                fullImageBitmap = Bitmap.createBitmap(imageBitmap, 0, 0, imgW, imgH, transform, false);

                int cropW = Math.min(previewWidth, fullImageBitmap.getWidth());
                int cropH = Math.min(previewHeight, fullImageBitmap.getHeight());
                if (cropW <= 0 || cropH <= 0) { emitter.onNext(new AnalyseResult(0, null, 0, previewWidth, previewHeight)); return; }

                if (useFullScreenCrop) {
                    int offX = Math.max(0, (fullImageBitmap.getWidth() - previewWidth) / 2);
                    int offY = Math.max(0, (fullImageBitmap.getHeight() - previewHeight) / 2);
                    cropW = Math.min(cropW, fullImageBitmap.getWidth() - offX);
                    cropH = Math.min(cropH, fullImageBitmap.getHeight() - offY);
                    if (cropW <= 0 || cropH <= 0) { emitter.onNext(new AnalyseResult(0, null, 0, previewWidth, previewHeight)); return; }
                    cropImageBitmap = Bitmap.createBitmap(fullImageBitmap, offX, offY, cropW, cropH);
                } else {
                    cropImageBitmap = Bitmap.createBitmap(fullImageBitmap, 0, 0, cropW, cropH);
                }

                Matrix previewToModel = imageProcess.getTransformationMatrix(
                        cropImageBitmap.getWidth(), cropImageBitmap.getHeight(),
                        detector.getInputSize().getWidth(), detector.getInputSize().getHeight(), 0, false);
                modelInputBitmap = Bitmap.createBitmap(cropImageBitmap, 0, 0,
                        cropImageBitmap.getWidth(), cropImageBitmap.getHeight(), previewToModel, false);

                Matrix modelToPreview = new Matrix();
                try { previewToModel.invert(modelToPreview); }
                catch (IllegalArgumentException e) { emitter.onNext(new AnalyseResult(0, null, 0, previewWidth, previewHeight)); return; }

                ArrayList<Recognition> recognitions = detector.detect(modelInputBitmap);
                Bitmap resultBitmap = renderer.render(recognitions, previewWidth, previewHeight, modelToPreview);
                emitter.onNext(new AnalyseResult(System.currentTimeMillis() - start, resultBitmap, recognitions.size(), previewWidth, previewHeight));

            } catch (Exception e) {
                Log.e("FullImageAnalyse", "Error: " + e.getMessage(), e);
                emitter.onNext(new AnalyseResult(0, null, 0, previewWidth, previewHeight));
            } finally {
                if (imageBitmap != null && !imageBitmap.isRecycled()) imageBitmap.recycle();
                if (fullImageBitmap != null && !fullImageBitmap.isRecycled()) fullImageBitmap.recycle();
                if (cropImageBitmap != null && !cropImageBitmap.isRecycled()) cropImageBitmap.recycle();
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
