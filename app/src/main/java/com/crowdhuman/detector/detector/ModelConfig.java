package com.crowdhuman.detector.detector;

public class ModelConfig {
    public final String key;
    public final String modelFile;
    public final String labelFile;
    public final int numClasses;
    public final boolean isInt8;

    public ModelConfig(String key, String modelFile, String labelFile, int numClasses, boolean isInt8) {
        this.key = key;
        this.modelFile = modelFile;
        this.labelFile = labelFile;
        this.numClasses = numClasses;
        this.isInt8 = isInt8;
    }
}
