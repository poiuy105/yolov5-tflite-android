package com.example.yolov5tfliteandroid.detector;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Build;
import android.util.Log;
import android.util.Size;
import android.widget.Toast;

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
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;


public class Yolov5TFLiteDetector {

    private final Size INPUT_SIZE = new Size(320, 320);
    private int NUM_CLASSES = 80;
    private int[] outputSize = new int[]{1, 6300, 85};
    private Boolean IS_INT8 = false;
    private final float DETECT_THRESHOLD = 0.25f;
    private final float IOU_THRESHOLD = 0.45f;
    private final float IOU_CLASS_DUPLICATED_THRESHOLD = 0.7f;
    private final String MODEL_YOLOV5S = "yolov5s-fp16-320-metadata.tflite";
    private final String MODEL_YOLOV5N =  "yolov5n-fp16-320.tflite";
    private final String MODEL_YOLOV5M = "yolov5m-fp16-320.tflite";
    private final String MODEL_YOLOV5S_INT8 = "yolov5s-int8-320.tflite";
    private final String MODEL_CROWDHUMAN = "crowdhuman_vbody_yolov5m.tflite";
    private final String LABEL_FILE = "coco_label.txt";
    private final String PERSON_LABEL_FILE = "person_label.txt";
    MetadataExtractor.QuantizationParams input5SINT8QuantParams = new MetadataExtractor.QuantizationParams(0.003921568859368563f, 0);
    MetadataExtractor.QuantizationParams output5SINT8QuantParams = new MetadataExtractor.QuantizationParams(0.006305381190031767f, 5);
    private String MODEL_FILE;

    private Interpreter tflite;
    private GpuDelegate gpuDelegate;
    private NnApiDelegate nnApiDelegate;
    private List<String> associatedAxisLabels;
    private Interpreter.Options options;

    public String getModelFile() {
        return this.MODEL_FILE;
    }

    private String currentLabelFile = LABEL_FILE;

    public void setModelFile(String modelFile){
        switch (modelFile) {
            case "yolov5s":
                IS_INT8 = false;
                MODEL_FILE = MODEL_YOLOV5S;
                currentLabelFile = LABEL_FILE;
                NUM_CLASSES = 80;
                break;
            case "yolov5n":
                IS_INT8 = false;
                MODEL_FILE = MODEL_YOLOV5N;
                currentLabelFile = LABEL_FILE;
                NUM_CLASSES = 80;
                break;
            case "yolov5m":
                IS_INT8 = false;
                MODEL_FILE = MODEL_YOLOV5M;
                currentLabelFile = LABEL_FILE;
                NUM_CLASSES = 80;
                break;
            case "yolov5s-int8":
                IS_INT8 = true;
                MODEL_FILE = MODEL_YOLOV5S_INT8;
                currentLabelFile = LABEL_FILE;
                NUM_CLASSES = 80;
                break;
            case "crowdhuman":
                IS_INT8 = false;
                MODEL_FILE = MODEL_CROWDHUMAN;
                currentLabelFile = PERSON_LABEL_FILE;
                NUM_CLASSES = 1;
                break;
            default:
                Log.i("tfliteSupport", "Only yolov5s/n/m/sint8/crowdhuman can be load!");
                return;
        }
        outputSize = new int[]{1, 6300, 5 + NUM_CLASSES};
    }

    public String getLabelFile() {
        return this.currentLabelFile;
    }

    public Size getInputSize(){return this.INPUT_SIZE;}
    public int[] getOutputSize(){return this.outputSize;}
    public int getNumClasses(){return this.NUM_CLASSES;}

    /**
     * 初始化模型
     */
    public void initialModel(Context activity) {
        if (MODEL_FILE == null) {
            Log.e("tfliteSupport", "MODEL_FILE is null, call setModelFile first!");
            Toast.makeText(activity, "No model selected!", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            // Close previous interpreter and delegates
            close();

            // Create fresh options each time to prevent delegate accumulation
            options = new Interpreter.Options();
            addGPUDelegate();

            ByteBuffer tfliteModel = FileUtil.loadMappedFile(activity, MODEL_FILE);
            tflite = new Interpreter(tfliteModel, options);
            Log.i("tfliteSupport", "Success reading model: " + MODEL_FILE);

            associatedAxisLabels = FileUtil.loadLabels(activity, currentLabelFile);
            Log.i("tfliteSupport", "Success reading label: " + currentLabelFile);
            Log.i("tfliteSupport", "Output size: " + Arrays.toString(outputSize) + ", classes: " + NUM_CLASSES);

        } catch (IOException e) {
            Log.e("tfliteSupport", "Error reading model or label: ", e);
            Toast.makeText(activity, "Failed to load model: " + MODEL_FILE + "\n" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 释放所有 native 资源
     */
    public void close() {
        if (tflite != null) {
            tflite.close();
            tflite = null;
            Log.i("tfliteSupport", "TFLite Interpreter closed.");
        }
        if (gpuDelegate != null) {
            gpuDelegate.close();
            gpuDelegate = null;
            Log.i("tfliteSupport", "GpuDelegate closed.");
        }
        if (nnApiDelegate != null) {
            nnApiDelegate.close();
            nnApiDelegate = null;
            Log.i("tfliteSupport", "NnApiDelegate closed.");
        }
        options = null;
    }

    public ArrayList<Recognition> detect(Bitmap bitmap) {
        if (tflite == null) {
            Log.e("tfliteSupport", "Interpreter is null, model not loaded!");
            return new ArrayList<>();
        }

        TensorImage yolov5sTfliteInput;
        ImageProcessor imageProcessor;
        if(IS_INT8){
            imageProcessor =
                    new ImageProcessor.Builder()
                            .add(new ResizeOp(INPUT_SIZE.getHeight(), INPUT_SIZE.getWidth(), ResizeOp.ResizeMethod.BILINEAR))
                            .add(new NormalizeOp(0, 255))
                            .add(new QuantizeOp(input5SINT8QuantParams.getZeroPoint(), input5SINT8QuantParams.getScale()))
                            .add(new CastOp(DataType.UINT8))
                            .build();
            yolov5sTfliteInput = new TensorImage(DataType.UINT8);
        }else{
            imageProcessor =
                    new ImageProcessor.Builder()
                            .add(new ResizeOp(INPUT_SIZE.getHeight(), INPUT_SIZE.getWidth(), ResizeOp.ResizeMethod.BILINEAR))
                            .add(new NormalizeOp(0, 255))
                            .build();
            yolov5sTfliteInput = new TensorImage(DataType.FLOAT32);
        }

        yolov5sTfliteInput.load(bitmap);
        yolov5sTfliteInput = imageProcessor.process(yolov5sTfliteInput);

        TensorBuffer probabilityBuffer;
        if(IS_INT8){
            probabilityBuffer = TensorBuffer.createFixedSize(outputSize, DataType.UINT8);
        }else{
            probabilityBuffer = TensorBuffer.createFixedSize(outputSize, DataType.FLOAT32);
        }

        tflite.run(yolov5sTfliteInput.getBuffer(), probabilityBuffer.getBuffer());

        if(IS_INT8){
            TensorProcessor tensorProcessor = new TensorProcessor.Builder()
                    .add(new DequantizeOp(output5SINT8QuantParams.getZeroPoint(), output5SINT8QuantParams.getScale()))
                    .build();
            probabilityBuffer = tensorProcessor.process(probabilityBuffer);
        }

        float[] recognitionArray = probabilityBuffer.getFloatArray();
        ArrayList<Recognition> allRecognitions = new ArrayList<>();
        for (int i = 0; i < outputSize[1]; i++) {
            int gridStride = i * outputSize[2];
            float x = recognitionArray[0 + gridStride] * INPUT_SIZE.getWidth();
            float y = recognitionArray[1 + gridStride] * INPUT_SIZE.getHeight();
            float w = recognitionArray[2 + gridStride] * INPUT_SIZE.getWidth();
            float h = recognitionArray[3 + gridStride] * INPUT_SIZE.getHeight();
            int xmin = (int) Math.max(0, x - w / 2.);
            int ymin = (int) Math.max(0, y - h / 2.);
            int xmax = (int) Math.min(INPUT_SIZE.getWidth(), x + w / 2.);
            int ymax = (int) Math.min(INPUT_SIZE.getHeight(), y + h / 2.);
            float confidence = recognitionArray[4 + gridStride];
            float[] classScores = Arrays.copyOfRange(recognitionArray, 5 + gridStride, this.outputSize[2] + gridStride);

            int labelId = 0;
            float maxLabelScores = 0.f;
            for (int j = 0; j < classScores.length; j++) {
                if (classScores[j] > maxLabelScores) {
                    maxLabelScores = classScores[j];
                    labelId = j;
                }
            }

            Recognition r = new Recognition(
                    labelId,
                    "",
                    maxLabelScores,
                    confidence,
                    new RectF(xmin, ymin, xmax, ymax));
            allRecognitions.add(r);
        }

        ArrayList<Recognition> nmsRecognitions = nms(allRecognitions);
        ArrayList<Recognition> nmsFilterBoxDuplicationRecognitions = nmsAllClass(nmsRecognitions);

        for(Recognition recognition : nmsFilterBoxDuplicationRecognitions){
            int labelId = recognition.getLabelId();
            if (labelId >= 0 && labelId < associatedAxisLabels.size()) {
                recognition.setLabelName(associatedAxisLabels.get(labelId));
            } else {
                recognition.setLabelName("unknown_" + labelId);
            }
        }

        return nmsFilterBoxDuplicationRecognitions;
    }

    protected ArrayList<Recognition> nms(ArrayList<Recognition> allRecognitions) {
        ArrayList<Recognition> nmsRecognitions = new ArrayList<Recognition>();
        for (int i = 0; i < NUM_CLASSES; i++) {
            PriorityQueue<Recognition> pq =
                    new PriorityQueue<Recognition>(
                            6300,
                            new Comparator<Recognition>() {
                                @Override
                                public int compare(final Recognition l, final Recognition r) {
                                    return Float.compare(r.getConfidence(), l.getConfidence());
                                }
                            });

            for (int j = 0; j < allRecognitions.size(); ++j) {
                if (allRecognitions.get(j).getLabelId() == i && allRecognitions.get(j).getConfidence() > DETECT_THRESHOLD) {
                    pq.add(allRecognitions.get(j));
                }
            }

            while (pq.size() > 0) {
                Recognition[] a = new Recognition[pq.size()];
                Recognition[] detections = pq.toArray(a);
                Recognition max = detections[0];
                nmsRecognitions.add(max);
                pq.clear();

                for (int k = 1; k < detections.length; k++) {
                    Recognition detection = detections[k];
                    if (boxIou(max.getLocation(), detection.getLocation()) < IOU_THRESHOLD) {
                        pq.add(detection);
                    }
                }
            }
        }
        return nmsRecognitions;
    }

    protected ArrayList<Recognition> nmsAllClass(ArrayList<Recognition> allRecognitions) {
        ArrayList<Recognition> nmsRecognitions = new ArrayList<Recognition>();

        PriorityQueue<Recognition> pq =
                new PriorityQueue<Recognition>(
                        100,
                        new Comparator<Recognition>() {
                            @Override
                            public int compare(final Recognition l, final Recognition r) {
                                return Float.compare(r.getConfidence(), l.getConfidence());
                            }
                        });

        for (int j = 0; j < allRecognitions.size(); ++j) {
            if (allRecognitions.get(j).getConfidence() > DETECT_THRESHOLD) {
                pq.add(allRecognitions.get(j));
            }
        }

        while (pq.size() > 0) {
            Recognition[] a = new Recognition[pq.size()];
            Recognition[] detections = pq.toArray(a);
            Recognition max = detections[0];
            nmsRecognitions.add(max);
            pq.clear();

            for (int k = 1; k < detections.length; k++) {
                Recognition detection = detections[k];
                if (boxIou(max.getLocation(), detection.getLocation()) < IOU_CLASS_DUPLICATED_THRESHOLD) {
                    pq.add(detection);
                }
            }
        }
        return nmsRecognitions;
    }


    protected float boxIou(RectF a, RectF b) {
        float intersection = boxIntersection(a, b);
        float union = boxUnion(a, b);
        if (union <= 0) return 1;
        return intersection / union;
    }

    protected float boxIntersection(RectF a, RectF b) {
        float maxLeft = a.left > b.left ? a.left : b.left;
        float maxTop = a.top > b.top ? a.top : b.top;
        float minRight = a.right < b.right ? a.right : b.right;
        float minBottom = a.bottom < b.bottom ? a.bottom : b.bottom;
        float w = minRight -  maxLeft;
        float h = minBottom - maxTop;

        if (w < 0 || h < 0) return 0;
        return w * h;
    }

    protected float boxUnion(RectF a, RectF b) {
        float i = boxIntersection(a, b);
        float u = (a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - i;
        return u;
    }

    /**
     * 添加NNapi代理, 保存引用以便后续释放
     */
    public void addNNApiDelegate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            nnApiDelegate = new NnApiDelegate();
            if (options != null) {
                options.addDelegate(nnApiDelegate);
            }
            Log.i("tfliteSupport", "using nnapi delegate.");
        }
    }

    /**
     * 添加GPU代理, 保存 delegate 引用以便后续释放
     */
    public void addGPUDelegate() {
        if (options == null) return;
        CompatibilityList compatibilityList = new CompatibilityList();
        if(compatibilityList.isDelegateSupportedOnThisDevice()){
            GpuDelegate.Options delegateOptions = compatibilityList.getBestOptionsForThisDevice();
            gpuDelegate = new GpuDelegate(delegateOptions);
            options.addDelegate(gpuDelegate);
            Log.i("tfliteSupport", "using gpu delegate.");
        } else {
            addThread(4);
        }
    }

    public void addThread(int thread) {
        if (options != null) {
            options.setNumThreads(thread);
        }
    }

}
