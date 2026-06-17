package com.example.yolov5tfliteandroid.detector;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Build;
import android.util.Log;
import android.util.Size;

import com.example.yolov5tfliteandroid.utils.Recognition;

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

    private static final Size INPUT_SIZE = new Size(320, 320);
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
        this.outputSize = new int[]{1, 6300, 5 + config.numClasses};
        return true;
    }

    public String getModelFile() { return currentConfig != null ? currentConfig.modelFile : null; }
    public String getModelKey() { return currentConfig != null ? currentConfig.key : null; }
    public Size getInputSize() { return INPUT_SIZE; }
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
            Log.i("tfliteSupport", "Model loaded: " + currentConfig.modelFile);

            associatedAxisLabels = FileUtil.loadLabels(context, currentConfig.labelFile);
            Log.i("tfliteSupport", "Labels loaded: " + currentConfig.labelFile
                    + ", outputSize=" + Arrays.toString(outputSize)
                    + ", classes=" + currentConfig.numClasses);

            if (callback != null) callback.onModelLoaded(currentConfig.modelFile);

        } catch (IOException e) {
            Log.e("tfliteSupport", "Error loading model: ", e);
            if (callback != null) callback.onModelError("Failed to load " + currentConfig.modelFile + ": " + e.getMessage());
        }
    }

    public void close() {
        if (tflite != null) { tflite.close(); tflite = null; }
        if (gpuDelegate != null) { gpuDelegate.close(); gpuDelegate = null; }
        if (nnApiDelegate != null) { nnApiDelegate.close(); nnApiDelegate = null; }
        options = null;
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

        // Run inference
        TensorBuffer outputBuffer = TensorBuffer.createFixedSize(outputSize,
                currentConfig.isInt8 ? DataType.UINT8 : DataType.FLOAT32);
        tflite.run(input.getBuffer(), outputBuffer.getBuffer());

        // Post-process INT8 if needed
        if (currentConfig.isInt8) {
            TensorProcessor tp = new TensorProcessor.Builder()
                    .add(new DequantizeOp(OUTPUT_INT8_QUANT.getZeroPoint(), OUTPUT_INT8_QUANT.getScale()))
                    .build();
            outputBuffer = tp.process(outputBuffer);
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
                .add(new ResizeOp(INPUT_SIZE.getHeight(), INPUT_SIZE.getWidth(), ResizeOp.ResizeMethod.BILINEAR))
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
            float x = data[0 + stride] * INPUT_SIZE.getWidth();
            float y = data[1 + stride] * INPUT_SIZE.getHeight();
            float w = data[2 + stride] * INPUT_SIZE.getWidth();
            float h = data[3 + stride] * INPUT_SIZE.getHeight();
            int xmin = (int) Math.max(0, x - w / 2);
            int ymin = (int) Math.max(0, y - h / 2);
            int xmax = (int) Math.min(INPUT_SIZE.getWidth(), x + w / 2);
            int ymax = (int) Math.min(INPUT_SIZE.getHeight(), y + h / 2);
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
}
