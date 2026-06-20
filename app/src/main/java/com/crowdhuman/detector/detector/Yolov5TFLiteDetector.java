package com.crowdhuman.detector.detector;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Build;
import android.util.Log;
import android.util.Size;

import com.crowdhuman.detector.utils.Recognition;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.nnapi.NnApiDelegate;
import org.tensorflow.lite.support.common.FileUtil;
import org.tensorflow.lite.support.common.TensorProcessor;
import org.tensorflow.lite.support.common.ops.CastOp;
import org.tensorflow.lite.support.common.ops.DequantizeOp;
import org.tensorflow.lite.support.common.ops.NormalizeOp;
import org.tensorflow.lite.support.common.ops.QuantizeOp;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;
import org.tensorflow.lite.support.metadata.MetadataExtractor;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


public class Yolov5TFLiteDetector {

    // Original hardcoded sizes for 320x320 YOLOv5 models
    private static final Size INPUT_SIZE = new Size(320, 320);
    private static final int[] OUTPUT_SIZE = new int[]{1, 6300, 85};
    private static final float DETECT_THRESHOLD = 0.25f;
    private static final float IOU_THRESHOLD = 0.45f;
    private static final float IOU_CLASS_DUPLICATED_THRESHOLD = 0.7f;
    private static final MetadataExtractor.QuantizationParams INPUT_INT8_QUANT =
            new MetadataExtractor.QuantizationParams(0.003921568859368563f, 0);
    private static final MetadataExtractor.QuantizationParams OUTPUT_INT8_QUANT =
            new MetadataExtractor.QuantizationParams(0.006305381190031767f, 5);

    // OCP: Configuration table instead of switch-case
    private static final Map<String, ModelConfig> MODEL_CONFIGS = new HashMap<>();
    static {
        MODEL_CONFIGS.put("yolov5n", new ModelConfig("yolov5n", "yolov5n-fp16-320.tflite", "coco_label.txt", 80, false));
    }

    private Interpreter tflite;
    private GpuDelegate gpuDelegate;
    private NnApiDelegate nnApiDelegate;
    private Interpreter.Options options;
    private List<String> associatedAxisLabels;
    private ModelConfig currentConfig;
    private final NmsProcessor nmsProcessor;
    private Set<Integer> enabledLabels;
    // P2-14 FIX: Reuse TensorProcessor for INT8 models
    private TensorProcessor int8TensorProcessor;

    // Zero-copy input buffer (pre-allocated, reused across frames)
    private ByteBuffer inputBuffer;
    private int[] pixelCache;
    private ImageProcessor cachedImageProcessor;

    public Yolov5TFLiteDetector() {
        this.nmsProcessor = new NmsProcessor(DETECT_THRESHOLD, IOU_THRESHOLD);
    }

    /**
     * OCP: Set model by key. Returns true if model key is valid.
     */
    public boolean setModelFile(String modelKey) {
        ModelConfig config = MODEL_CONFIGS.get(modelKey);
        if (config == null) {
            Log.w("tfliteSupport", "Unknown model: " + modelKey + ". Available: " + MODEL_CONFIGS.keySet());
            return false;
        }
        this.currentConfig = config;
        return true;
    }

    public String getModelFile() { return currentConfig != null ? currentConfig.modelFile : null; }
    public String getModelKey() { return currentConfig != null ? currentConfig.key : null; }
    public Size getInputSize() { return INPUT_SIZE; }
    public int[] getOutputSize() { return OUTPUT_SIZE; }
    public int getNumClasses() { return currentConfig != null ? currentConfig.numClasses : 0; }

    /**
     * DIP: Load model. Errors reported via callback, no Toast.
     */
    public void initialModel(Context context, DetectorCallback callback) {
        if (currentConfig == null) {
            if (callback != null) callback.onModelError("No model selected. Call setModelFile() first.");
            return;
        }
        try {
            close();

            options = new Interpreter.Options();
            addBestDelegate();

            ByteBuffer tfliteModel = FileUtil.loadMappedFile(context, currentConfig.modelFile);
            tflite = new Interpreter(tfliteModel, options);
            Log.i("tfliteSupport", "Success reading model: " + currentConfig.modelFile);

            // Load labels
            associatedAxisLabels = FileUtil.loadLabels(context, currentConfig.labelFile);
            Log.i("tfliteSupport", "Success reading label: " + currentConfig.labelFile);

            if (callback != null) callback.onModelLoaded(currentConfig.modelFile);

        } catch (Exception e) {
            Log.e("tfliteSupport", "Error loading model: ", e);
            if (callback != null) callback.onModelError("Failed to load " + currentConfig.modelFile + ": " + e.getMessage());
        }
    }

    public void close() {
        if (tflite != null) { tflite.close(); tflite = null; }
        if (gpuDelegate != null) { gpuDelegate.close(); gpuDelegate = null; }
        if (nnApiDelegate != null) { nnApiDelegate.close(); nnApiDelegate = null; }
        options = null;
        inputBuffer = null;
        pixelCache = null;
        cachedImageProcessor = null;
    }

    /**
     * LSP: Returns empty list if model not loaded (caller can check getModelFile() != null first).
     */
    public ArrayList<Recognition> detect(Bitmap bitmap) {
        if (tflite == null || currentConfig == null) {
            Log.e("tfliteSupport", "Interpreter is null, model not loaded!");
            return new ArrayList<>();
        }

        // Preprocess
        TensorImage input = preprocessImage(bitmap);

        // Run inference - original hardcoded output size and data type
        TensorBuffer outputBuffer;
        if (currentConfig.isInt8) {
            outputBuffer = TensorBuffer.createFixedSize(OUTPUT_SIZE, DataType.UINT8);
        } else {
            outputBuffer = TensorBuffer.createFixedSize(OUTPUT_SIZE, DataType.FLOAT32);
        }
        tflite.run(input.getBuffer(), outputBuffer.getBuffer());

        // Post-process INT8 if needed
        if (currentConfig.isInt8) {
            if (int8TensorProcessor == null) {
                int8TensorProcessor = new TensorProcessor.Builder()
                        .add(new DequantizeOp(OUTPUT_INT8_QUANT.getZeroPoint(), OUTPUT_INT8_QUANT.getScale()))
                        .build();
            }
            outputBuffer = int8TensorProcessor.process(outputBuffer);
        }

        // Decode output
        ArrayList<Recognition> allRecognitions = decodeOutput(outputBuffer.getFloatArray());

        // NMS (OCP: single unified method)
        ArrayList<Recognition> filtered = nmsProcessor.suppress(
                allRecognitions, currentConfig.numClasses, IOU_CLASS_DUPLICATED_THRESHOLD, enabledLabels);

        // Assign labels
        assignLabels(filtered);

        return filtered;
    }

    private TensorImage preprocessImage(Bitmap bitmap) {
        if (cachedImageProcessor == null) {
            ImageProcessor.Builder builder = new ImageProcessor.Builder()
                    .add(new ResizeOp(INPUT_SIZE.getHeight(), INPUT_SIZE.getWidth(), ResizeOp.ResizeMethod.BILINEAR))
                    .add(new NormalizeOp(0, 255));
            if (currentConfig.isInt8) {
                builder.add(new QuantizeOp(INPUT_INT8_QUANT.getZeroPoint(), INPUT_INT8_QUANT.getScale()))
                        .add(new CastOp(DataType.UINT8));
            }
            cachedImageProcessor = builder.build();
        }
        TensorImage input = new TensorImage(currentConfig.isInt8 ? DataType.UINT8 : DataType.FLOAT32);
        input.load(bitmap);
        return cachedImageProcessor.process(input);
    }

    /**
     * Decode TFLite output with early filtering.
     * Skips anchors below threshold before computing class scores,
     * reducing iterations from 6300 to typically 50-200 on most frames.
     */
    private ArrayList<Recognition> decodeOutput(float[] data) {
        ArrayList<Recognition> results = new ArrayList<>();
        float threshold = nmsProcessor.getDetectThreshold();
        int stride = OUTPUT_SIZE[2];

        for (int i = 0; i < OUTPUT_SIZE[1]; i++) {
            int base = i * stride;
            float confidence = data[4 + base];

            // Early exit: skip low-confidence anchors entirely
            if (confidence < threshold) continue;

            // Decode coordinates
            float x = data[base] * INPUT_SIZE.getWidth();
            float y = data[1 + base] * INPUT_SIZE.getHeight();
            float w = data[2 + base] * INPUT_SIZE.getWidth();
            float h = data[3 + base] * INPUT_SIZE.getHeight();
            int xmin = (int) Math.max(0, x - w / 2.);
            int ymin = (int) Math.max(0, y - h / 2.);
            int xmax = (int) Math.min(INPUT_SIZE.getWidth(), x + w / 2.);
            int ymax = (int) Math.min(INPUT_SIZE.getHeight(), y + h / 2.);

            // Find max class score
            int labelId = 0;
            float maxScore = 0.f;
            for (int j = 5; j < stride; j++) {
                float score = data[j + base];
                if (score > maxScore) {
                    maxScore = score;
                    labelId = j - 5;
                }
            }

            // Combined score filter (objness * class_score)
            if (maxScore * confidence < threshold) continue;

            results.add(new Recognition(labelId, "", maxScore, confidence,
                    new RectF(xmin, ymin, xmax, ymax)));
        }
        return results;
    }

    private void assignLabels(ArrayList<Recognition> recognitions) {
        for (Recognition r : recognitions) {
            int id = r.getLabelId();
            if (id >= 0 && id < associatedAxisLabels.size()) {
                r.setLabelName(associatedAxisLabels.get(id));
            } else {
                r.setLabelName("unknown_" + id);
            }
        }
    }

    /**
     * Smart delegate selection: NNAPI (DSP) > GPU > CPU with optimized thread count.
     * On low-end devices, NNAPI+DSP can be 2-3x faster than GPU.
     */
    private void addBestDelegate() {
        if (options == null) return;

        // 1. Try NNAPI with DSP acceleration (Android 8.1+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                NnApiDelegate.Options nnapiOpts = new NnApiDelegate.Options();
                nnapiOpts.setAllowNnapiCpu(false); // Prevent fallback to CPU (no benefit)
                nnApiDelegate = new NnApiDelegate(nnapiOpts);
                options.addDelegate(nnApiDelegate);
                Log.i("tfliteSupport", "using NNAPI delegate.");
                return;
            } catch (Exception e) {
                Log.w("tfliteSupport", "NNAPI not available: " + e.getMessage());
            }
        }

        // 2. Try GPU Delegate
        CompatibilityList compat = new CompatibilityList();
        if (compat.isDelegateSupportedOnThisDevice()) {
            gpuDelegate = new GpuDelegate(compat.getBestOptionsForThisDevice());
            options.addDelegate(gpuDelegate);
            Log.i("tfliteSupport", "using GPU delegate.");
            return;
        }

        // 3. CPU fallback: low-end devices benefit from fewer threads
        options.setNumThreads(Runtime.getRuntime().availableProcessors() > 4 ? 4 : 2);
        Log.i("tfliteSupport", "using CPU with " + options.getNumThreads() + " threads.");
    }

    public void addNNApiDelegate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && options != null) {
            nnApiDelegate = new NnApiDelegate();
            options.addDelegate(nnApiDelegate);
        }
    }

    /**
     * Zero-copy detect: directly write bitmap pixels to TFLite ByteBuffer,
     * skipping ImageProcessor intermediate allocations.
     * Falls back to standard detect() for INT8 models.
     */
    public ArrayList<Recognition> detectZeroCopy(Bitmap bitmap) {
        if (tflite == null || currentConfig == null || inputBuffer == null) {
            Log.e("tfliteSupport", "Interpreter or buffer not ready!");
            return new ArrayList<>();
        }
        if (currentConfig.isInt8) {
            return detect(bitmap);
        }

        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (pixelCache == null || pixelCache.length != w * h) {
            pixelCache = new int[w * h];
        }
        bitmap.getPixels(pixelCache, 0, w, 0, 0, w, h);

        inputBuffer.rewind();
        for (int i = 0; i < pixelCache.length; i++) {
            int p = pixelCache[i];
            inputBuffer.putFloat(((p >> 16) & 0xFF) / 255.0f);
            inputBuffer.putFloat(((p >> 8) & 0xFF) / 255.0f);
            inputBuffer.putFloat((p & 0xFF) / 255.0f);
        }
        inputBuffer.rewind();

        TensorBuffer outputBuffer = TensorBuffer.createFixedSize(OUTPUT_SIZE, DataType.FLOAT32);
        tflite.run(inputBuffer, outputBuffer.getBuffer());

        ArrayList<Recognition> allRecognitions = decodeOutput(outputBuffer.getFloatArray());
        ArrayList<Recognition> filtered = nmsProcessor.suppress(
                allRecognitions, currentConfig.numClasses, IOU_CLASS_DUPLICATED_THRESHOLD, enabledLabels);
        assignLabels(filtered);
        return filtered;
    }

    /**
     * Pre-allocate input buffer after model is loaded.
     * Must be called after initialModel() succeeds.
     */
    public void initInputBuffer() {
        if (tflite == null) return;
        int pixelCount = INPUT_SIZE.getWidth() * INPUT_SIZE.getHeight();
        inputBuffer = ByteBuffer.allocateDirect(pixelCount * 3 * 4); // FLOAT32: 3 channels * 4 bytes
        inputBuffer.order(ByteOrder.nativeOrder());
        pixelCache = new int[pixelCount];

        // Cache ImageProcessor (build once, reuse forever)
        ImageProcessor.Builder builder = new ImageProcessor.Builder()
                .add(new ResizeOp(INPUT_SIZE.getHeight(), INPUT_SIZE.getWidth(), ResizeOp.ResizeMethod.BILINEAR))
                .add(new NormalizeOp(0, 255));
        if (currentConfig.isInt8) {
            builder.add(new QuantizeOp(INPUT_INT8_QUANT.getZeroPoint(), INPUT_INT8_QUANT.getScale()))
                    .add(new CastOp(DataType.UINT8));
        }
        cachedImageProcessor = builder.build();
        Log.i("tfliteSupport", "Input buffer initialized: " + pixelCount + " pixels");
    }

    /**
     * Set detection confidence threshold dynamically.
     */
    public void setDetectThreshold(float threshold) {
        nmsProcessor.setDetectThreshold(threshold);
    }

    public float getDetectThreshold() {
        return nmsProcessor.getDetectThreshold();
    }

    public void setEnabledLabels(Set<Integer> labels) {
        this.enabledLabels = labels;
    }

    public Set<Integer> getEnabledLabels() {
        return enabledLabels;
    }

    public String[] getLabels() {
        if (associatedAxisLabels == null) return new String[0];
        return associatedAxisLabels.toArray(new String[0]);
    }

}
