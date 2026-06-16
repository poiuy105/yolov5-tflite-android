package com.example.yolov5tfliteandroid.utils;

import android.graphics.RectF;

public class Recognition {

    private int labelId;
    private String labelName;
    private float labelScore;
    private float confidence;
    private RectF location;

    public Recognition(int labelId, String labelName, float labelScore, float confidence, RectF location) {
        this.labelId = labelId;
        this.labelScore = labelScore;
        this.labelName = labelName;
        this.confidence = confidence;
        this.location = location;
    }

    public int getLabelId() { return labelId; }
    public String getLabelName() { return labelName; }
    public float getLabelScore() { return labelScore; }
    public float getConfidence() { return confidence; }

    public RectF getLocation() {
        return new RectF(location);
    }

    public void setLocation(RectF location) { this.location = location; }
    public void setLabelName(String labelName) { this.labelName = labelName; }
    public void setLabelId(int labelId) { this.labelId = labelId; }
    public void setLabelScore(float labelScore) { this.labelScore = labelScore; }
    public void setConfidence(float confidence) { this.confidence = confidence; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(labelId).append(" ");
        if (labelName != null) sb.append(labelName).append(" ");
        if (confidence > 0) sb.append(String.format("(%.1f%%) ", confidence * 100.0f));
        if (location != null) sb.append(location).append(" ");
        return sb.toString().trim();
    }
}
