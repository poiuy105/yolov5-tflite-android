package com.crowdhuman.detector.detector;

import android.graphics.RectF;

import com.crowdhuman.detector.utils.Recognition;

import java.util.ArrayList;
import java.util.Set;

public class NmsProcessor {

    private float detectThreshold;
    private final float iouThreshold;

    public NmsProcessor(float detectThreshold, float iouThreshold) {
        this.detectThreshold = detectThreshold;
        this.iouThreshold = iouThreshold;
    }

    public void setDetectThreshold(float threshold) {
        this.detectThreshold = threshold;
    }

    public float getDetectThreshold() {
        return detectThreshold;
    }

    public ArrayList<Recognition> suppress(ArrayList<Recognition> allRecognitions, int numClasses, float crossClassIouThreshold, Set<Integer> enabledLabels) {
        ArrayList<Recognition> perClassResult = perClassNms(allRecognitions, numClasses, enabledLabels);
        if (crossClassIouThreshold > 0) {
            perClassResult = singleNms(perClassResult, crossClassIouThreshold);
        }
        return perClassResult;
    }

    public ArrayList<Recognition> suppress(ArrayList<Recognition> allRecognitions, int numClasses, float crossClassIouThreshold) {
        return suppress(allRecognitions, numClasses, crossClassIouThreshold, null);
    }

    private ArrayList<Recognition> perClassNms(ArrayList<Recognition> allRecognitions, int numClasses, Set<Integer> enabledLabels) {
        ArrayList<Recognition> result = new ArrayList<>();
        for (int i = 0; i < numClasses; i++) {
            if (enabledLabels != null && !enabledLabels.contains(i)) {
                continue;
            }
            result.addAll(singleNms(filterByClass(allRecognitions, i), iouThreshold));
        }
        return result;
    }

    private ArrayList<Recognition> filterByClass(ArrayList<Recognition> recognitions, int classId) {
        ArrayList<Recognition> filtered = new ArrayList<>();
        for (Recognition r : recognitions) {
            // Original logic: filter by confidence (objness), not labelScore
            if (r.getLabelId() == classId && r.getConfidence() > detectThreshold) {
                filtered.add(r);
            }
        }
        return filtered;
    }

    private ArrayList<Recognition> singleNms(ArrayList<Recognition> candidates, float threshold) {
        ArrayList<Recognition> result = new ArrayList<>();
        if (candidates.isEmpty()) return result;

        candidates.sort((a, b) -> Float.compare(b.getConfidence(), a.getConfidence()));

        boolean[] suppressed = new boolean[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            if (suppressed[i]) continue;
            result.add(candidates.get(i));
            for (int j = i + 1; j < candidates.size(); j++) {
                if (!suppressed[j] && boxIou(candidates.get(i).getLocation(), candidates.get(j).getLocation()) >= threshold) {
                    suppressed[j] = true;
                }
            }
        }
        return result;
    }

    float boxIou(RectF a, RectF b) {
        float intersection = boxIntersection(a, b);
        float union = boxUnion(a, b);
        if (union <= 0) return 1;
        return intersection / union;
    }

    private float boxIntersection(RectF a, RectF b) {
        float w = Math.min(a.right, b.right) - Math.max(a.left, b.left);
        float h = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
        if (w < 0 || h < 0) return 0;
        return w * h;
    }

    private float boxUnion(RectF a, RectF b) {
        float i = boxIntersection(a, b);
        return (a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - i;
    }
}
