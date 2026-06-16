package com.example.yolov5tfliteandroid.analysis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.view.PreviewView;

import com.example.yolov5tfliteandroid.detector.Yolov5TFLiteDetector;
import com.example.yolov5tfliteandroid.utils.ImageProcess;
import com.example.yolov5tfliteandroid.utils.Recognition;

import java.util.ArrayList;
import java.util.Locale;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.ObservableEmitter;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Unified analyser that supports both full-image and full-screen modes.
 * Mode is controlled by the useFullScreenCrop parameter.
 */
public class FullImageAnalyse implements ImageAnalysis.Analyzer {

    public static class Result {
        public Result(long costTime, Bitmap bitmap, int detectCount) {
            this.costTime = costTime;
            this.bitmap = bitmap;
            this.detectCount = detectCount;
        }
        long costTime;
        Bitmap bitmap;
        int detectCount;
    }

    public interface ScreenshotListener {
        void onScreenshotReady(Bitmap bitmap);
    }

    private final ImageView boxLabelCanvas;
    private final PreviewView previewView;
    private final int rotation;
    private final TextView inferenceTimeTextView;
    private final TextView frameSizeTextView;
    private final TextView detectCountTextView;
    private final Yolov5TFLiteDetector yolov5TFLiteDetector;
    private final ImageProcess imageProcess;
    private final boolean useFullScreenCrop;
    private final float textScale;

    // Reusable Paint objects
    private final Paint boxPaint = new Paint();
    private final Paint textBgPaint = new Paint();
    private final Paint textPaint = new Paint();

    // RxJava disposable management
    private Disposable currentDisposable;
    private ScreenshotListener screenshotListener;

    public FullImageAnalyse(Context context,
                            PreviewView previewView,
                            ImageView boxLabelCanvas,
                            int rotation,
                            TextView inferenceTimeTextView,
                            TextView frameSizeTextView,
                            TextView detectCountTextView,
                            Yolov5TFLiteDetector yolov5TFLiteDetector,
                            boolean useFullScreenCrop) {
        this.previewView = previewView;
        this.boxLabelCanvas = boxLabelCanvas;
        this.rotation = rotation;
        this.inferenceTimeTextView = inferenceTimeTextView;
        this.frameSizeTextView = frameSizeTextView;
        this.detectCountTextView = detectCountTextView;
        this.yolov5TFLiteDetector = yolov5TFLiteDetector;
        this.imageProcess = new ImageProcess();
        this.useFullScreenCrop = useFullScreenCrop;

        // Calculate text scale based on screen density
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        textScale = dm.density;

        // Initialize Paint objects once
        boxPaint.setStrokeWidth(3 * textScale);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setColor(Color.RED);

        float textSize = 14 * textScale;
        textPaint.setTextSize(textSize);
        textPaint.setColor(Color.WHITE);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);

        textBgPaint.setStyle(Paint.Style.FILL);
        textBgPaint.setColor(Color.argb(180, 0, 0, 0));
    }

    public void setScreenshotListener(ScreenshotListener listener) {
        this.screenshotListener = listener;
    }

    public void takeScreenshot() {
        if (screenshotListener != null && boxLabelCanvas != null) {
            boxLabelCanvas.post(() -> {
                Drawable drawable = boxLabelCanvas.getDrawable();
                if (drawable instanceof BitmapDrawable) {
                    Bitmap bmp = ((BitmapDrawable) drawable).getBitmap();
                    if (bmp != null && !bmp.isRecycled()) {
                        screenshotListener.onScreenshotReady(bmp.copy(Bitmap.Config.ARGB_8888, false));
                    }
                }
            });
        }
    }

    /**
     * Cancel previous subscription to prevent memory leaks
     */
    public void dispose() {
        if (currentDisposable != null && !currentDisposable.isDisposed()) {
            currentDisposable.dispose();
            currentDisposable = null;
            Log.i("FullImageAnalyse", "Previous subscription disposed.");
        }
    }

    @Override
    public void analyze(@NonNull ImageProxy image) {
        int previewHeight = previewView.getHeight();
        int previewWidth = previewView.getWidth();

        if (previewWidth <= 0 || previewHeight <= 0) {
            image.close();
            return;
        }

        // Dispose previous subscription
        dispose();

        currentDisposable = Observable.create((ObservableEmitter<Result> emitter) -> {
            long start = System.currentTimeMillis();

            Bitmap imageBitmap = null;
            Bitmap fullImageBitmap = null;
            Bitmap cropImageBitmap = null;
            Bitmap modelInputBitmap = null;
            Bitmap resultBitmap = null;

            try {
                byte[][] yuvBytes = new byte[3][];
                ImageProxy.PlaneProxy[] planes = image.getPlanes();
                int imageHeight = image.getHeight();
                int imageWidth = image.getWidth();

                imageProcess.fillBytes(planes, yuvBytes);
                int yRowStride = planes[0].getRowStride();
                final int uvRowStride = planes[1].getRowStride();
                final int uvPixelStride = planes[1].getPixelStride();

                int[] rgbBytes = new int[imageHeight * imageWidth];
                imageProcess.YUV420ToARGB8888(
                        yuvBytes[0], yuvBytes[1], yuvBytes[2],
                        imageWidth, imageHeight,
                        yRowStride, uvRowStride, uvPixelStride, rgbBytes);

                imageBitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888);
                imageBitmap.setPixels(rgbBytes, 0, imageWidth, 0, 0, imageWidth, imageHeight);

                double scale = Math.max(
                        previewHeight / (double) (rotation % 180 == 0 ? imageWidth : imageHeight),
                        previewWidth / (double) (rotation % 180 == 0 ? imageHeight : imageWidth)
                );
                int scaledW = (int) (scale * imageHeight);
                int scaledH = (int) (scale * imageWidth);
                if (scaledW <= 0 || scaledH <= 0) {
                    emitter.onNext(new Result(0, null, 0));
                    return;
                }

                Matrix fullScreenTransform = imageProcess.getTransformationMatrix(
                        imageWidth, imageHeight, scaledW, scaledH,
                        rotation % 180 == 0 ? 90 : 0, false);

                fullImageBitmap = Bitmap.createBitmap(imageBitmap, 0, 0, imageWidth, imageHeight, fullScreenTransform, false);

                int cropW = Math.min(previewWidth, fullImageBitmap.getWidth());
                int cropH = Math.min(previewHeight, fullImageBitmap.getHeight());
                if (cropW <= 0 || cropH <= 0) {
                    emitter.onNext(new Result(0, null, 0));
                    return;
                }

                // Full-screen mode: crop center; Full-image mode: use full image scaled to preview
                Bitmap inputForModel;
                if (useFullScreenCrop) {
                    int offsetX = Math.max(0, (fullImageBitmap.getWidth() - previewWidth) / 2);
                    int offsetY = Math.max(0, (fullImageBitmap.getHeight() - previewHeight) / 2);
                    cropW = Math.min(cropW, fullImageBitmap.getWidth() - offsetX);
                    cropH = Math.min(cropH, fullImageBitmap.getHeight() - offsetY);
                    if (cropW <= 0 || cropH <= 0) {
                        emitter.onNext(new Result(0, null, 0));
                        return;
                    }
                    cropImageBitmap = Bitmap.createBitmap(fullImageBitmap, offsetX, offsetY, cropW, cropH);
                    inputForModel = cropImageBitmap;
                } else {
                    cropImageBitmap = Bitmap.createBitmap(fullImageBitmap, 0, 0, cropW, cropH);
                    inputForModel = cropImageBitmap;
                }

                Matrix previewToModelTransform = imageProcess.getTransformationMatrix(
                        inputForModel.getWidth(), inputForModel.getHeight(),
                        yolov5TFLiteDetector.getInputSize().getWidth(),
                        yolov5TFLiteDetector.getInputSize().getHeight(),
                        0, false);
                modelInputBitmap = Bitmap.createBitmap(inputForModel, 0, 0,
                        inputForModel.getWidth(), inputForModel.getHeight(),
                        previewToModelTransform, false);

                Matrix modelToPreviewTransform = new Matrix();
                try {
                    previewToModelTransform.invert(modelToPreviewTransform);
                } catch (IllegalArgumentException e) {
                    Log.e("FullImageAnalyse", "Matrix invert failed: " + e.getMessage());
                    emitter.onNext(new Result(0, null, 0));
                    return;
                }

                ArrayList<Recognition> recognitions = yolov5TFLiteDetector.detect(modelInputBitmap);

                resultBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888);
                Canvas cropCanvas = new Canvas(resultBitmap);

                for (Recognition res : recognitions) {
                    RectF location = res.getLocation();
                    String label = res.getLabelName();
                    float confidence = res.getConfidence();
                    modelToPreviewTransform.mapRect(location);

                    // Clamp to screen bounds
                    location.left = Math.max(0, location.left);
                    location.top = Math.max(0, location.top);
                    location.right = Math.min(previewWidth, location.right);
                    location.bottom = Math.min(previewHeight, location.bottom);

                    cropCanvas.drawRect(location, boxPaint);

                    // Draw label with background for readability
                    String text = label + ":" + String.format(Locale.US, "%.2f", confidence);
                    float textWidth = textPaint.measureText(text);
                    float textHeight = textPaint.getTextSize();
                    float textX = location.left;
                    float textY = location.top > textHeight ? location.top : location.top + textHeight;

                    // Background rect
                    cropCanvas.drawRect(textX - 2 * textScale, textY - textHeight - 2 * textScale,
                            textX + textWidth + 4 * textScale, textY + 2 * textScale, textBgPaint);
                    // Text
                    cropCanvas.drawText(text, textX, textY, textPaint);
                }

                long end = System.currentTimeMillis();
                emitter.onNext(new Result(end - start, resultBitmap, recognitions.size()));

            } catch (Exception e) {
                Log.e("FullImageAnalyse", "Error in analyze: " + e.getMessage(), e);
                emitter.onNext(new Result(0, null, 0));
            } finally {
                if (imageBitmap != null && !imageBitmap.isRecycled()) imageBitmap.recycle();
                if (fullImageBitmap != null && !fullImageBitmap.isRecycled()) fullImageBitmap.recycle();
                if (cropImageBitmap != null && !cropImageBitmap.isRecycled()) cropImageBitmap.recycle();
                if (modelInputBitmap != null && !modelInputBitmap.isRecycled()) modelInputBitmap.recycle();
                // resultBitmap is passed to UI, will be recycled when replaced
                image.close();
            }

        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        (Result result) -> {
                            if (result.bitmap != null) {
                                Drawable prevDrawable = boxLabelCanvas.getDrawable();
                                if (prevDrawable instanceof BitmapDrawable) {
                                    Bitmap prev = ((BitmapDrawable) prevDrawable).getBitmap();
                                    if (prev != null && !prev.isRecycled()) {
                                        prev.recycle();
                                    }
                                }
                                boxLabelCanvas.setImageBitmap(result.bitmap);
                            }
                            frameSizeTextView.setText(previewHeight + "x" + previewWidth);
                            inferenceTimeTextView.setText(result.costTime + "ms");
                            if (detectCountTextView != null) {
                                detectCountTextView.setText(String.valueOf(result.detectCount));
                            }
                        },
                        (Throwable error) -> {
                            Log.e("FullImageAnalyse", "RxJava error: " + error.getMessage(), error);
                        }
                );
    }
}
