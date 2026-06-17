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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class Yolov5TFLiteDetector {

    private static final Size DEFAULT_INPUT_SIZE = new Size(320, 320);
    private Size inputSize; // Dynamic, set from model tensor shape
    private static final float DETECT_THRESHOLD = 0.25f;
    private static final float IOU_THRESHOLD = 0.45f;
    private static final float IOU_CLASS_DUPLICATED_THRESHOLD = 0.7f;
    // P1-10 FIX: Calculate anchor count from input size instead of hardcoding
    // YOLOv5 uses 3 scales with stride [8, 16, 32]
    // For 320x320: (320/8)^2 + (320/16)^2 + (320/32)^2 = 1600 + 400 + 100 = 2100 per anchor
    // With 3 anchors per scale: 2100 * 3 = 6300
    // Formula: sum((input_size / stride)^2) * 3
    private static final MetadataExtractor.QuantizationParams INPUT_INT8_QUANT =
            new MetadataExtractor.QuantizationParams(0.003921568859368563f, 0);
    private static final MetadataExtractor.QuantizationParams OUTPUT_INT8_QUANT =
            new MetadataExtractor.QuantizationParams(0.006305381190031767f, 5);

    // OCP: Configuration table instead of switch-case
    private static final Map<String, ModelConfig> MODEL_CONFIGS = new HashMap<>();
    static {
        MODEL_CONFIGS.put("yolov5s", new ModelConfig("yolov5s", "yolov5s-fp16-320-metadata.tflite", "coco_label.txt", 80, false));
        MODEL_CONFIGS.put("yolov5n", new ModelConfig("yolov5n", "yolov5n-fp16-320.tflite", "coco_label.txt", 80, false));
        MODEL_CONFIGS.put("yolov5m", new ModelConfig("yolov5m", "yolov5m-fp16-320.tflite", "coco_label.txt", 80, false));
        MODEL_CONFIGS.put("yolov5s-int8", new ModelConfig("yolov5s-int8", "yolov5s-int8-320.tflite", "coco_label.txt", 80, true));
        MODEL_CONFIGS.put("crowdhuman", new ModelConfig("crowdhuman", "crowdhuman_vbody_yolov5m.tflite", "person_label.txt", 1, false));
    }

    private Interpreter tflite;
    private GpuDelegate gpuDelegate;
    private NnApiDelegate nnApiDelegate;
    private Interpreter.Options options;
    private List<String> associatedAxisLabels;
    private ModelConfig currentConfig;
    private int[] outputSize;
    private final NmsProcessor nmsProcessor;
    // P2-14 FIX: Reuse TensorProcessor for INT8 models
    private TensorProcessor int8TensorProcessor;

    public Yolov5TFLiteDetector() {
        this.nmsProcessor = new NmsProcessor(DETECT_THRESHOLD, IOU_THRESHOLD);
        this.inputSize = DEFAULT_INPUT_SIZE;
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
        // outputSize will be calculated in initialModel() after reading actual tensor shape
        return true;
    }

    public String getModelFile() { return currentConfig != null ? currentConfig.modelFile : null; }
    public String getModelKey() { return currentConfig != null ? currentConfig.key : null; }
    public Size getInputSize() { return inputSize; }
    public int[] getOutputSize() { return outputSize; }
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
            addGPUDelegate();

            ByteBuffer tfliteModel = FileUtil.loadMappedFile(context, currentConfig.modelFile);
            tflite = new Interpreter(tfliteModel, options);

            // FIX: Read actual input size from model tensor shape
            int inputTensorIdx = tflite.getInputTensor(0).shape().length - 1; // height
            int inputTensorWIdx = tflite.getInputTensor(0).shape().length - 2; // width
            int modelH = (int) tflite.getInputTensor(0).shape()[inputTensorIdx];
            int modelW = (int) tflite.getInputTensor(0).shape()[inputTensorWIdx];
            this.inputSize = new Size(modelW, modelH);

            // Recalculate outputSize with actual input dimensions
            int anchorCount = calculateAnchorCount(modelW);
            this.outputSize = new int[]{1, anchorCount, 5 + currentConfig.numClasses};

            Log.i("tfliteSupport", "Model loaded: " + currentConfig.modelFile
                    + ", inputSize=" + modelW + "x" + modelH
                    + ", outputSize=" + Arrays.toString(outputSize)
                    + ", anchors=" + anchorCount);

            associatedAxisLabels = FileUtil.loadLabels(context, currentConfig.labelFile);
            Log.i("tfliteSupport", "Labels loaded: " + currentConfig.labelFile
                    + ", outputSize=" + Arrays.toString(outputSize)
                    + ", classes=" + currentConfig.numClasses);

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
        // Reset inputSize to default so setModelFile() calculates correctly
        this.inputSize = DEFAULT_INPUT_SIZE;
    }

    /**
     * LSP: Returns empty list if model not loaded (caller can check getModelFile() != null first).
     */
    public ArrayList<Recognition> detect(Bitmap bitmap) {
        if (tflite == null || currentConfig == null || outputSize == null) {
            Log.e("tfliteSupport", "Interpreter is null or outputSize not set, model not loaded!");
            return new ArrayList<>();
        }

        // Preprocess
        TensorImage input = preprocessImage(bitmap);

        // Run inference
        TensorBuffer outputBuffer = TensorBuffer.createFixedSize(outputSize,
                currentConfig.isInt8 ? DataType.UINT8 : DataType.FLOAT32);
        tflite.run(input.getBuffer(), outputBuffer.getBuffer());

        // Post-process INT8 if needed
        // P2-14 FIX: Reuse TensorProcessor instead of creating new one each detect
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
                allRecognitions, currentConfig.numClasses, IOU_CLASS_DUPLICATED_THRESHOLD);

        // Assign labels
        assignLabels(filtered);

        return filtered;
    }

    private TensorImage preprocessImage(Bitmap bitmap) {
        ImageProcessor.Builder builder = new ImageProcessor.Builder()
                .add(new ResizeOp(inputSize.getHeight(), inputSize.getWidth(), ResizeOp.ResizeMethod.BILINEAR))
                .add(new NormalizeOp(0, 255));

        if (currentConfig.isInt8) {
            builder.add(new QuantizeOp(INPUT_INT8_QUANT.getZeroPoint(), INPUT_INT8_QUANT.getScale()))
                   .add(new CastOp(DataType.UINT8));
            TensorImage input = new TensorImage(DataType.UINT8);
            input.load(bitmap);
            return builder.build().process(input);
        } else {
            TensorImage input = new TensorImage(DataType.FLOAT32);
            input.load(bitmap);
            return builder.build().process(input);
        }
    }

    private ArrayList<Recognition> decodeOutput(float[] data) {
        ArrayList<Recognition> results = new ArrayList<>();
        for (int i = 0; i < outputSize[1]; i++) {
            int stride = i * outputSize[2];
            float x = data[0 + stride] * inputSize.getWidth();
            float y = data[1 + stride] * inputSize.getHeight();
            float w = data[2 + stride] * inputSize.getWidth();
            float h = data[3 + stride] * inputSize.getHeight();
            int xmin = (int) Math.max(0, x - w / 2);
            int ymin = (int) Math.max(0, y - h / 2);
            int xmax = (int) Math.min(inputSize.getWidth(), x + w / 2);
            int ymax = (int) Math.min(inputSize.getHeight(), y + h / 2);
            float confidence = data[4 + stride];

            float[] classScores = Arrays.copyOfRange(data, 5 + stride, outputSize[2] + stride);
            int labelId = 0;
            float maxScore = 0;
            for (int j = 0; j < classScores.length; j++) {
                if (classScores[j] > maxScore) {
                    maxScore = classScores[j];
                    labelId = j;
                }
            }

            // YOLOv5 TFLite model output: confidence is already the objectness score.
            // For detection threshold filtering, we use confidence directly.
            // labelScore is used for NMS threshold comparison.
            results.add(new Recognition(labelId, "", confidence, confidence,
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

    private void addGPUDelegate() {
        if (options == null) return;
        CompatibilityList compat = new CompatibilityList();
        if (compat.isDelegateSupportedOnThisDevice()) {
            gpuDelegate = new GpuDelegate(compat.getBestOptionsForThisDevice());
            options.addDelegate(gpuDelegate);
            Log.i("tfliteSupport", "using gpu delegate.");
        } else {
            options.setNumThreads(4);
        }
    }

    public void addNNApiDelegate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && options != null) {
            nnApiDelegate = new NnApiDelegate();
            options.addDelegate(nnApiDelegate);
        }
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

    /**
     * P1-10 FIX: Calculate YOLOv5 anchor count from input size.
     * YOLOv5 uses 3 scales with strides [8, 16, 32] and 3 anchors per scale.
     */
    private static int calculateAnchorCount(int inputSize) {
        int[] strides = {8, 16, 32};
        int total = 0;
        for (int stride : strides) {
            int grid = inputSize / stride;
            total += grid * grid;
        }
        return total * 3; // 3 anchors per scale
    }
}
