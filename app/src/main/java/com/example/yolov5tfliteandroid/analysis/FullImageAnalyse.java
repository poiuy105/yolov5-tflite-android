package com.example.yolov5tfliteandroid.analysis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;
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

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.ObservableEmitter;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class FullImageAnalyse implements ImageAnalysis.Analyzer {

    public static class Result {
        public Result(long costTime, Bitmap bitmap) {
            this.costTime = costTime;
            this.bitmap = bitmap;
        }
        long costTime;
        Bitmap bitmap;
    }

    ImageView boxLabelCanvas;
    PreviewView previewView;
    int rotation;
    private TextView inferenceTimeTextView;
    private TextView frameSizeTextView;
    ImageProcess imageProcess;
    private Yolov5TFLiteDetector yolov5TFLiteDetector;

    // Reusable Paint objects to avoid allocation per frame
    private final Paint boxPaint = new Paint();
    private final Paint textPaint = new Paint();

    public FullImageAnalyse(Context context,
                            PreviewView previewView,
                            ImageView boxLabelCanvas,
                            int rotation,
                            TextView inferenceTimeTextView,
                            TextView frameSizeTextView,
                            Yolov5TFLiteDetector yolov5TFLiteDetector) {
        this.previewView = previewView;
        this.boxLabelCanvas = boxLabelCanvas;
        this.rotation = rotation;
        this.inferenceTimeTextView = inferenceTimeTextView;
        this.frameSizeTextView = frameSizeTextView;
        this.imageProcess = new ImageProcess();
        this.yolov5TFLiteDetector = yolov5TFLiteDetector;

        // Initialize Paint objects once
        boxPaint.setStrokeWidth(5);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setColor(Color.RED);
        textPaint.setTextSize(50);
        textPaint.setColor(Color.RED);
        textPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    public void analyze(@NonNull ImageProxy image) {
        int previewHeight = previewView.getHeight();
        int previewWidth = previewView.getWidth();

        if (previewWidth <= 0 || previewHeight <= 0) {
            image.close();
            return;
        }

        Observable.create((ObservableEmitter<Result> emitter) -> {
            long start = System.currentTimeMillis();

            Bitmap imageBitmap = null;
            Bitmap fullImageBitmap = null;
            Bitmap cropImageBitmap = null;
            Bitmap modelInputBitmap = null;
            Bitmap emptyCropSizeBitmap = null;

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
                        yuvBytes[0],
                        yuvBytes[1],
                        yuvBytes[2],
                        imageWidth,
                        imageHeight,
                        yRowStride,
                        uvRowStride,
                        uvPixelStride,
                        rgbBytes);

                // 原图bitmap
                imageBitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888);
                imageBitmap.setPixels(rgbBytes, 0, imageWidth, 0, 0, imageWidth, imageHeight);

                // 图片适应屏幕fill_start格式的bitmap
                double scale = Math.max(
                        previewHeight / (double) (rotation % 180 == 0 ? imageWidth : imageHeight),
                        previewWidth / (double) (rotation % 180 == 0 ? imageHeight : imageWidth)
                );
                int scaledW = (int) (scale * imageHeight);
                int scaledH = (int) (scale * imageWidth);
                if (scaledW <= 0 || scaledH <= 0) {
                    emitter.onNext(new Result(0, null));
                    return;
                }

                Matrix fullScreenTransform = imageProcess.getTransformationMatrix(
                        imageWidth, imageHeight,
                        scaledW, scaledH,
                        rotation % 180 == 0 ? 90 : 0, false
                );

                // 适应preview的全尺寸bitmap
                fullImageBitmap = Bitmap.createBitmap(imageBitmap, 0, 0, imageWidth, imageHeight, fullScreenTransform, false);

                // 裁剪出跟preview在屏幕上一样大小的bitmap (with bounds check)
                int cropW = Math.min(previewWidth, fullImageBitmap.getWidth());
                int cropH = Math.min(previewHeight, fullImageBitmap.getHeight());
                if (cropW <= 0 || cropH <= 0) {
                    emitter.onNext(new Result(0, null));
                    return;
                }
                cropImageBitmap = Bitmap.createBitmap(fullImageBitmap, 0, 0, cropW, cropH);

                // 模型输入的bitmap
                Matrix previewToModelTransform =
                        imageProcess.getTransformationMatrix(
                                cropImageBitmap.getWidth(), cropImageBitmap.getHeight(),
                                yolov5TFLiteDetector.getInputSize().getWidth(),
                                yolov5TFLiteDetector.getInputSize().getHeight(),
                                0, false);
                modelInputBitmap = Bitmap.createBitmap(cropImageBitmap, 0, 0,
                        cropImageBitmap.getWidth(), cropImageBitmap.getHeight(),
                        previewToModelTransform, false);

                Matrix modelToPreviewTransform = new Matrix();
                try {
                    previewToModelTransform.invert(modelToPreviewTransform);
                } catch (IllegalArgumentException e) {
                    Log.e("FullImageAnalyse", "Matrix invert failed: " + e.getMessage());
                    emitter.onNext(new Result(0, null));
                    return;
                }

                ArrayList<Recognition> recognitions = yolov5TFLiteDetector.detect(modelInputBitmap);

                emptyCropSizeBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888);
                Canvas cropCanvas = new Canvas(emptyCropSizeBitmap);

                for (Recognition res : recognitions) {
                    RectF location = res.getLocation();
                    String label = res.getLabelName();
                    float confidence = res.getConfidence();
                    modelToPreviewTransform.mapRect(location);
                    cropCanvas.drawRect(location, boxPaint);
                    cropCanvas.drawText(label + ":" + String.format("%.2f", confidence), location.left, location.top, textPaint);
                }
                long end = System.currentTimeMillis();
                long costTime = (end - start);
                emitter.onNext(new Result(costTime, emptyCropSizeBitmap));

            } catch (Exception e) {
                Log.e("FullImageAnalyse", "Error in analyze: " + e.getMessage(), e);
                emitter.onNext(new Result(0, null));
            } finally {
                // Recycle all intermediate bitmaps to prevent memory leaks
                if (imageBitmap != null && !imageBitmap.isRecycled()) imageBitmap.recycle();
                if (fullImageBitmap != null && !fullImageBitmap.isRecycled()) fullImageBitmap.recycle();
                if (cropImageBitmap != null && !cropImageBitmap.isRecycled()) cropImageBitmap.recycle();
                if (modelInputBitmap != null && !modelInputBitmap.isRecycled()) modelInputBitmap.recycle();
                // Note: emptyCropSizeBitmap is passed to UI, will be recycled by ImageView when replaced
                image.close();
            }

        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        (Result result) -> {
                            if (result.bitmap != null) {
                                // Recycle previous bitmap before setting new one
                                Bitmap prev = (Bitmap) boxLabelCanvas.getDrawable();
                                if (prev != null && !prev.isRecycled()) {
                                    prev.recycle();
                                }
                                boxLabelCanvas.setImageBitmap(result.bitmap);
                            }
                            frameSizeTextView.setText(previewHeight + "x" + previewWidth);
                            inferenceTimeTextView.setText(Long.toString(result.costTime) + "ms");
                        },
                        (Throwable error) -> {
                            Log.e("FullImageAnalyse", "RxJava error: " + error.getMessage(), error);
                        }
                );
    }
}
