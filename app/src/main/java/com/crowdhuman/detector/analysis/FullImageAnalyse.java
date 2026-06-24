package com.crowdhuman.detector.analysis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.view.PreviewView;

import com.crowdhuman.detector.detector.BlockMotionGrid;
import com.crowdhuman.detector.detector.NmsProcessor;
import com.crowdhuman.detector.detector.Yolov5TFLiteDetector;
import com.crowdhuman.detector.detector.MotionDetector;
import com.crowdhuman.detector.detector.MotionTracker;
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
    private final Yolov5TFLiteDetector detector;       // 320 模型（全帧 / 回退）
    private final Yolov5TFLiteDetector detectorSmall;   // 160 模型（运动区域推理）
    private final boolean isFrontCamera;
    private AnalyseCallback callback;
    private Disposable currentDisposable;
    private java.util.Set<Integer> enabledLabels;

    // FPS calculation
    private volatile long lastFrameTime = 0;
    private volatile float currentFps = 0;
    private static final float FPS_ALPHA = 0.9f;

    // Pre-allocated Bitmaps (avoid per-frame allocation)
    private Bitmap reusedLetterboxBitmap;
    private Bitmap reusedCropBitmap;

    // 运动检测、分块网格、跟踪
    private final MotionDetector motionDetector = new MotionDetector();
    private final MotionTracker motionTracker = new MotionTracker();
    private final BlockMotionGrid blockMotionGrid = new BlockMotionGrid(4);

    // 周期性全帧刷新
    private static final int FULL_REFRESH_INTERVAL = 15;
    private int frameCounter = 0;

    // 跨区域 NMS
    private static final float CROSS_REGION_IOU = 0.45f;
    private final NmsProcessor crossRegionNms = new NmsProcessor(0f, CROSS_REGION_IOU);

    public FullImageAnalyse(Context context, PreviewView previewView, int rotation,
                           Yolov5TFLiteDetector detector,
                           Yolov5TFLiteDetector detectorSmall,
                           boolean isFrontCamera) {
        this.previewView = previewView;
        this.rotation = rotation;
        this.detector = detector;
        this.detectorSmall = detectorSmall;
        this.isFrontCamera = isFrontCamera;
    }

    public void setCallback(AnalyseCallback callback) {
        this.callback = callback;
    }

    public void setEnabledLabels(java.util.Set<Integer> labels) {
        this.enabledLabels = labels;
    }

    /**
     * 同步检测阈值到跨区域 NMS 处理器。
     * 由 UI SeekBar 调用，确保 crossRegionNms 的置信度过滤与主检测器一致。
     */
    public void setCrossRegionThreshold(float threshold) {
        crossRegionNms.setDetectThreshold(threshold);
    }

    public BlockMotionGrid getBlockMotionGrid() {
        return blockMotionGrid;
    }

    private void ensureLetterboxBitmap(int size) {
        if (reusedLetterboxBitmap == null || reusedLetterboxBitmap.getWidth() != size) {
            if (reusedLetterboxBitmap != null) reusedLetterboxBitmap.recycle();
            reusedLetterboxBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        }
    }

    private void ensureCropBitmap(int size) {
        if (reusedCropBitmap == null || reusedCropBitmap.getWidth() != size) {
            if (reusedCropBitmap != null) reusedCropBitmap.recycle();
            reusedCropBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        }
    }

    public void dispose() {
        if (currentDisposable != null && !currentDisposable.isDisposed()) {
            currentDisposable.dispose();
            currentDisposable = null;
        }
        if (reusedLetterboxBitmap != null) {
            reusedLetterboxBitmap.recycle();
            reusedLetterboxBitmap = null;
        }
        if (reusedCropBitmap != null) {
            reusedCropBitmap.recycle();
            reusedCropBitmap = null;
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
            long t0, t1, t2, t3, t4, t5;

            try {
                // === Stage 1: CameraX toBitmap ===
                t0 = System.currentTimeMillis();
                Bitmap cameraBitmap = image.toBitmap();
                int imgW = cameraBitmap.getWidth();
                int imgH = cameraBitmap.getHeight();

                // Safety: if CameraX returns an unreasonably large frame, downscale immediately.
                final int MAX_CAMERA_DIM = 960;
                if (imgW > MAX_CAMERA_DIM || imgH > MAX_CAMERA_DIM) {
                    Log.w("FullImageAnalyse", "Camera frame too large: " + imgW + "x" + imgH
                            + ", downscaling to fit " + MAX_CAMERA_DIM + "px");
                    float downScale = Math.min((float) MAX_CAMERA_DIM / imgW, (float) MAX_CAMERA_DIM / imgH);
                    int newW = Math.max(1, (int) (imgW * downScale));
                    int newH = Math.max(1, (int) (imgH * downScale));
                    Bitmap scaled = Bitmap.createScaledBitmap(cameraBitmap, newW, newH, true);
                    cameraBitmap.recycle();
                    cameraBitmap = scaled;
                    imgW = newW;
                    imgH = newH;
                }

                t1 = System.currentTimeMillis();
                long timeToBitmapMs = t1 - t0;

                // image.toBitmap() with RGBA_8888 may already rotate to device natural orientation.
                // If imgH > imgW (portrait), skip rotation in all downstream transforms.
                boolean bitmapIsPortrait = imgH > imgW;

                // === Stage 1.5: 运动检测（帧差法 + 掩码输出） ===
                long tMotionStart = System.currentTimeMillis();
                float motionScore = motionDetector.computeMotionScore(cameraBitmap);
                boolean hasMotion = motionDetector.isMotionDetected();
                long timeMotionMs = System.currentTimeMillis() - tMotionStart;

                // === Stage 1.6: 分块运动网格 → 区域提取 ===
                long tRegionStart = System.currentTimeMillis();
                byte[] mask = motionDetector.getMotionMask();
                int maskW = motionDetector.getMaskWidth();
                int maskH = motionDetector.getMaskHeight();
                BlockMotionGrid.ExtractionResult extraction = null;
                if (hasMotion && mask != null && maskW > 0 && maskH > 0) {
                    extraction = blockMotionGrid.extract(mask, maskW, maskH, imgW, imgH);
                }
                long timeRegionExtractMs = System.currentTimeMillis() - tRegionStart;

                // 周期性全帧刷新：每 N 帧强制使用 320 全帧推理
                frameCounter++;
                boolean periodicRefresh = (frameCounter % FULL_REFRESH_INTERVAL == 0);

                // === 决策分支 ===
                // 确定推理路径：区域160模型 / 全帧320模型 / 跳过
                boolean useSmallModel = false;
                boolean useFullFrame = false;
                boolean wasSkipped = false;
                int regionCount = 0;
                ArrayList<Rect> motionRegions = null;

                if (periodicRefresh) {
                    // 周期刷新 → 全帧 320
                    useFullFrame = true;
                } else if (extraction != null && extraction.shouldFallback) {
                    // 运动覆盖过大 → 全帧 320
                    useFullFrame = true;
                } else if (!hasMotion && motionTracker.getTrackCount() == 0) {
                    // 无运动且无跟踪 → 跳过
                    wasSkipped = true;
                } else if (!hasMotion && motionTracker.getTrackCount() > 0) {
                    // 无运动但有跟踪 → 跳过（Tracker 预测）
                    wasSkipped = true;
                } else if (extraction != null && !extraction.regions.isEmpty()) {
                    // 有运动区域 → 160 模型
                    useSmallModel = true;
                    motionRegions = extraction.regions;
                    regionCount = motionRegions.size();
                } else {
                    // 有运动但提取失败（罕见）→ 全帧 320
                    useFullFrame = true;
                }

                // === 推理路径 ===
                ArrayList<Recognition> recognitions;
                long timePreprocessMs = 0, timeInferenceMs = 0, timeDecodeMs = 0;
                long timeNmsMs = 0, timeLabelMs = 0, timeCropResizeMs = 0;
                int modelSize = detector.getInputSize().getWidth();

                if (enabledLabels != null) {
                    detector.setEnabledLabels(enabledLabels);
                    if (detectorSmall != null) detectorSmall.setEnabledLabels(enabledLabels);
                }

                if (wasSkipped) {
                    // ====== 跳过推理 ======
                    if (motionTracker.getTrackCount() > 0) {
                        recognitions = motionTracker.predict();
                    } else {
                        recognitions = new ArrayList<>();
                    }

                } else if (useSmallModel && detectorSmall != null && motionRegions != null) {
                    // ====== 运动区域 + 160 模型推理 ======
                    int smallSize = detectorSmall.getInputSize().getWidth();
                    ensureCropBitmap(smallSize);
                    Canvas cropCanvas = new Canvas(reusedCropBitmap);
                    Paint smoothPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

                    long timeCropStart = System.currentTimeMillis();
                    ArrayList<Recognition> allRegionDetections = new ArrayList<>();
                    long totalPreprocess = 0, totalInference = 0, totalDecode = 0;
                    long totalNms = 0, totalLabel = 0;

                    for (Rect cropRect : motionRegions) {
                        // 1. 裁剪 + (可选旋转) + 缩放 + letterbox → smallSize x smallSize
                        // 与全帧路径一致：如果 bitmap 是 landscape 才旋转，portrait 则跳过
                        int cropW = cropRect.width();
                        int cropH = cropRect.height();
                        float cropScale = bitmapIsPortrait
                                ? Math.min(smallSize / (float) cropH, smallSize / (float) cropW)
                                : Math.min(smallSize / (float) cropH, smallSize / (float) cropW);
                        int scaledW = bitmapIsPortrait
                                ? (int) (cropW * cropScale)
                                : (int) (cropH * cropScale);
                        int scaledH = bitmapIsPortrait
                                ? (int) (cropH * cropScale)
                                : (int) (cropW * cropScale);
                        int padX = (smallSize - scaledW) / 2;
                        int padY = (smallSize - scaledH) / 2;

                        Matrix cropMatrix = new Matrix();
                        cropMatrix.postTranslate(-cropRect.left - cropW / 2f, -cropRect.top - cropH / 2f);
                        if (!bitmapIsPortrait) {
                            cropMatrix.postRotate(90);
                        }
                        cropMatrix.postScale(cropScale, cropScale);
                        cropMatrix.postTranslate(padX + scaledW / 2f, padY + scaledH / 2f);

                        cropCanvas.drawColor(Color.GRAY);
                        cropCanvas.drawBitmap(cameraBitmap, cropMatrix, smoothPaint);

                        // 2. 推理（160 模型）
                        long[] detectTimings = new long[6];
                        ArrayList<Recognition> detections =
                                detectorSmall.detectZeroCopyWithTimings(reusedCropBitmap, detectTimings);
                        totalPreprocess += detectTimings[0];
                        totalInference += detectTimings[1];
                        totalDecode += detectTimings[2];
                        totalNms += detectTimings[3];
                        totalLabel += detectTimings[4];

                        // 3. 坐标映射：160 模型空间 → 原始帧坐标
                        Matrix cropToFrame = new Matrix();
                        boolean invertOk = cropMatrix.invert(cropToFrame);
                        if (invertOk) {
                            for (Recognition det : detections) {
                                RectF loc = det.getLocation();
                                cropToFrame.mapRect(loc);
                                det.setLocation(loc);
                            }
                        } else {
                            Log.w("FullImageAnalyse", "Crop matrix inversion failed!");
                        }

                        allRegionDetections.addAll(detections);
                    }
                    timeCropResizeMs = System.currentTimeMillis() - timeCropStart;

                    // 4. 跨区域 NMS（同类去重 + 跨类去重）
                    long tCrossNms = System.currentTimeMillis();
                    if (allRegionDetections.size() > 1) {
                        recognitions = crossRegionNms.suppress(
                                allRegionDetections,
                                detectorSmall.getNumClasses(),
                                0.70f);  // 跨类去重 IOU
                    } else {
                        recognitions = allRegionDetections;
                    }
                    long timeCrossNmsMs = System.currentTimeMillis() - tCrossNms;

                    // 汇总计时
                    timePreprocessMs = totalPreprocess;
                    timeInferenceMs = totalInference;
                    timeDecodeMs = totalDecode;
                    timeNmsMs = totalNms + timeCrossNmsMs;
                    timeLabelMs = totalLabel;

                    // 更新 Tracker
                    motionTracker.update(recognitions);

                } else {
                    // ====== 全帧 320 模型推理（原有路径） ======
                    int fullModelSize = detector.getInputSize().getWidth();

                    // Rotate + letterbox
                    float scale = Math.min(
                            fullModelSize / (float) imgH,
                            fullModelSize / (float) imgW);
                    int scaledW = bitmapIsPortrait
                            ? (int) (imgW * scale)
                            : (int) (imgH * scale);
                    int scaledH = bitmapIsPortrait
                            ? (int) (imgH * scale)
                            : (int) (imgW * scale);
                    int padX = (fullModelSize - scaledW) / 2;
                    int padY = (fullModelSize - scaledH) / 2;

                    ensureLetterboxBitmap(fullModelSize);
                    Canvas letterCanvas = new Canvas(reusedLetterboxBitmap);
                    letterCanvas.drawColor(Color.GRAY);

                    Matrix combinedMatrix = new Matrix();
                    combinedMatrix.postTranslate(-imgW / 2f, -imgH / 2f);
                    if (!bitmapIsPortrait) {
                        combinedMatrix.postRotate(90);
                    }
                    combinedMatrix.postScale(scale, scale);
                    combinedMatrix.postTranslate(padX + scaledW / 2f, padY + scaledH / 2f);

                    letterCanvas.drawBitmap(cameraBitmap, combinedMatrix, null);

                    // 推理
                    long[] detectTimings = new long[6];
                    recognitions = detector.detectZeroCopyWithTimings(reusedLetterboxBitmap, detectTimings);
                    timePreprocessMs = detectTimings[0];
                    timeInferenceMs = detectTimings[1];
                    timeDecodeMs = detectTimings[2];
                    timeNmsMs = detectTimings[3];
                    timeLabelMs = detectTimings[4];

                    // 坐标映射：letterbox → 相机帧
                    Matrix modelToCamera = new Matrix();
                    combinedMatrix.invert(modelToCamera);
                    for (Recognition r : recognitions) {
                        RectF loc = r.getLocation();
                        modelToCamera.mapRect(loc);
                    }

                    // 更新 Tracker
                    motionTracker.update(recognitions);

                    // 跳到坐标映射（已完成 letterbox → camera）
                    // 下面统一处理 camera → preview
                    t2 = System.currentTimeMillis();
                    long timeRotateLetterboxMs = t2 - t1;
                    long timeRotateMs = 0;
                    long timeLetterboxMs = timeRotateLetterboxMs;

                    // === Map to preview ===
                    PreviewTransform pvt = buildCameraToPreview(imgW, imgH, previewWidth, previewHeight);
                    for (Recognition r : recognitions) {
                        RectF loc = r.getLocation();
                        if (isFrontCamera) {
                            float centerX = imgW / 2f;
                            float left = 2 * centerX - loc.right;
                            float right = 2 * centerX - loc.left;
                            loc.left = left;
                            loc.right = right;
                        }
                        pvt.matrix.mapRect(loc);
                        r.setLocation(loc);
                    }
                    t4 = System.currentTimeMillis();
                    long timeMapMs = t4 - t2;

                    // 构建并发送结果
                    long now = System.currentTimeMillis();
                    currentFps = updateFps(now);
                    long totalTimeMs = now - start;

                    String debugInfo = String.format(
                            "cam=%dx%d model=%d motion=%.4f skip=%b tracks=%d regions=%d small=%b refresh=%b",
                            imgW, imgH, fullModelSize, motionScore, wasSkipped,
                            motionTracker.getTrackCount(), regionCount, useSmallModel, periodicRefresh);
                    Log.d("FullImageAnalyse", debugInfo);

                    Log.i("PipelineTiming", String.format(
                            "toBitmap=%dms motion=%dms regionExtract=%dms rotate+letterbox=%dms " +
                            "preprocess=%dms inference=%dms decode=%dms nms=%dms label=%dms map=%dms " +
                            "cropResize=%dms total=%dms",
                            timeToBitmapMs, timeMotionMs, timeRegionExtractMs, timeRotateLetterboxMs,
                            timePreprocessMs, timeInferenceMs, timeDecodeMs,
                            timeNmsMs, timeLabelMs, timeMapMs, timeCropResizeMs,
                            totalTimeMs));

                    RectF firstBox = recognitions.isEmpty() ? null : new RectF(recognitions.get(0).getLocation());

                    AnalyseResult fullFrameResult = new AnalyseResult(
                            totalTimeMs, timeInferenceMs, null, recognitions.size(),
                            previewWidth, previewHeight, currentFps, imgW, imgH, debugInfo, firstBox,
                            recognitions, pvt.matrix, isFrontCamera, pvt.offsetX, pvt.offsetY, pvt.renderW, pvt.renderH,
                            fullModelSize, motionScore, wasSkipped,
                            timeToBitmapMs, timeRotateMs, timeLetterboxMs,
                            timePreprocessMs, timeInferenceMs, timeDecodeMs,
                            timeNmsMs, timeLabelMs, timeMapMs, 0L,
                            timeRegionExtractMs, timeCropResizeMs, regionCount, useSmallModel);
                    attachMotionRegions(fullFrameResult, motionRegions, pvt, imgW, isFrontCamera);
                    emitter.onNext(fullFrameResult);
                    return;  // 提前返回，避免重复处理
                }

                // === 非全帧路径：坐标映射 camera → preview ===
                t2 = System.currentTimeMillis();
                long timeRotateLetterboxMs = 0;
                long timeRotateMs = 0;
                long timeLetterboxMs = 0;

                PreviewTransform pvt = buildCameraToPreview(imgW, imgH, previewWidth, previewHeight);
                for (Recognition r : recognitions) {
                    RectF loc = r.getLocation();
                    // 前摄镜像
                    if (isFrontCamera) {
                        float centerX = imgW / 2f;
                        float left = 2 * centerX - loc.right;
                        float right = 2 * centerX - loc.left;
                        loc.left = left;
                        loc.right = right;
                    }
                    pvt.matrix.mapRect(loc);
                    r.setLocation(loc);
                }
                t4 = System.currentTimeMillis();
                long timeMapMs = t4 - t2;

                // === 构建结果 ===
                long now = System.currentTimeMillis();
                currentFps = updateFps(now);
                long totalTimeMs = now - start;

                String debugInfo = String.format(
                        "cam=%dx%d model=%d motion=%.4f skip=%b tracks=%d regions=%d small=%b refresh=%b",
                        imgW, imgH, useSmallModel ? detectorSmall.getInputSize().getWidth() : modelSize,
                        motionScore, wasSkipped, motionTracker.getTrackCount(),
                        regionCount, useSmallModel, periodicRefresh);
                Log.d("FullImageAnalyse", debugInfo);

                Log.i("PipelineTiming", String.format(
                        "toBitmap=%dms motion=%dms regionExtract=%dms " +
                        "preprocess=%dms inference=%dms decode=%dms nms=%dms label=%dms map=%dms " +
                        "cropResize=%dms total=%dms",
                        timeToBitmapMs, timeMotionMs, timeRegionExtractMs,
                        timePreprocessMs, timeInferenceMs, timeDecodeMs,
                        timeNmsMs, timeLabelMs, timeMapMs, timeCropResizeMs,
                        totalTimeMs));

                RectF firstBox = recognitions.isEmpty() ? null : new RectF(recognitions.get(0).getLocation());

                // 为 overlay 提供 letterbox 参数（非全帧路径使用 crop 尺寸）
                int effectiveLetterboxSize = useSmallModel
                        ? detectorSmall.getInputSize().getWidth()
                        : modelSize;

                AnalyseResult regionResult = new AnalyseResult(
                        totalTimeMs, timeInferenceMs, null, recognitions.size(),
                        previewWidth, previewHeight, currentFps, imgW, imgH, debugInfo, firstBox,
                        recognitions, pvt.matrix, isFrontCamera, pvt.offsetX, pvt.offsetY, pvt.renderW, pvt.renderH,
                        effectiveLetterboxSize, motionScore, wasSkipped,
                        timeToBitmapMs, timeRotateMs, timeLetterboxMs,
                        timePreprocessMs, timeInferenceMs, timeDecodeMs,
                        timeNmsMs, timeLabelMs, timeMapMs, 0L,
                        timeRegionExtractMs, timeCropResizeMs, regionCount, useSmallModel);
                attachMotionRegions(regionResult, motionRegions, pvt, imgW, isFrontCamera);
                emitter.onNext(regionResult);

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

    /**
     * 将运动区域从相机坐标变换到预览坐标，并附加到 AnalyseResult。
     */
    private void attachMotionRegions(AnalyseResult result, ArrayList<Rect> cameraRegions,
                                     PreviewTransform pvt, int imgW, boolean isFront) {
        if (cameraRegions == null || cameraRegions.isEmpty()) return;
        ArrayList<RectF> previewRegions = new ArrayList<>();
        for (Rect cr : cameraRegions) {
            RectF rf = new RectF(cr.left, cr.top, cr.right, cr.bottom);
            // 前摄镜像
            if (isFront) {
                float centerX = imgW / 2f;
                float left = 2 * centerX - rf.right;
                float right = 2 * centerX - rf.left;
                rf.left = left;
                rf.right = right;
            }
            pvt.matrix.mapRect(rf);
            previewRegions.add(rf);
        }
        result.setMotionRegionRects(previewRegions);
    }

    /**
     * Camera → Preview 变换结果：矩阵 + 布局参数。
     */
    static class PreviewTransform {
        final Matrix matrix;
        final int offsetX, offsetY, renderW, renderH;
        PreviewTransform(Matrix m, int ox, int oy, int rw, int rh) {
            this.matrix = m; this.offsetX = ox; this.offsetY = oy;
            this.renderW = rw; this.renderH = rh;
        }
    }

    /**
     * 构建 camera (landscape) → preview (portrait) 坐标变换矩阵。
     * 与原有管线保持一致：旋转 90° + 缩放 + 居中偏移。
     */
    private PreviewTransform buildCameraToPreview(int imgW, int imgH, int previewW, int previewH) {
        boolean bitmapIsPortrait = imgH > imgW;
        float previewScale = bitmapIsPortrait
                ? Math.min(previewW / (float) imgW, previewH / (float) imgH)
                : Math.min(previewW / (float) imgH, previewH / (float) imgW);
        int renderW = bitmapIsPortrait
                ? (int) (imgW * previewScale)
                : (int) (imgH * previewScale);
        int renderH = bitmapIsPortrait
                ? (int) (imgH * previewScale)
                : (int) (imgW * previewScale);
        int offsetX = (previewW - renderW) / 2;
        int offsetY = (previewH - renderH) / 2;

        Matrix cameraToPreview = new Matrix();
        cameraToPreview.postTranslate(-imgW / 2f, -imgH / 2f);
        if (!bitmapIsPortrait) {
            cameraToPreview.postRotate(90);
        }
        cameraToPreview.postScale(previewScale, previewScale);
        cameraToPreview.postTranslate(offsetX + renderW / 2f, offsetY + renderH / 2f);

        return new PreviewTransform(cameraToPreview, offsetX, offsetY, renderW, renderH);
    }

    /**
     * 更新 FPS（指数加权移动平均）。
     */
    private float updateFps(long now) {
        if (lastFrameTime > 0) {
            float instantFps = 1000f / (now - lastFrameTime);
            currentFps = FPS_ALPHA * currentFps + (1 - FPS_ALPHA) * instantFps;
        }
        lastFrameTime = now;
        return currentFps;
    }
}
