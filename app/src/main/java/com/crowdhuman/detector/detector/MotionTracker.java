package com.crowdhuman.detector.detector;

import android.graphics.RectF;

import com.crowdhuman.detector.utils.Recognition;

import java.util.ArrayList;

/**
 * IOU 帧间跟踪器，用于在跳帧时插值检测框位置。
 * 通过 IOU 匹配将新检测关联到已有轨迹，对未匹配的轨迹进行线性插值预测。
 */
public class MotionTracker {

    private static final int DEFAULT_MAX_TRACK_FRAMES = 6;
    private static final float DEFAULT_IOU_THRESHOLD = 0.3f;
    private static final float CONFIDENCE_DECAY = 0.9f;

    private int maxTrackFrames = DEFAULT_MAX_TRACK_FRAMES;
    private float iouThreshold = DEFAULT_IOU_THRESHOLD;

    private final ArrayList<TrackedObject> trackedObjects = new ArrayList<>();

    /**
     * 跟踪对象，保存检测目标的历史状态和运动信息。
     */
    public static class TrackedObject {
        public int labelId;
        public String labelName;
        public RectF location;
        public RectF prevLocation;
        public float confidence;
        public float velocityX;
        public float velocityY;
        public int framesSinceDetection;
        public int maxTrackFrames;

        public TrackedObject(int labelId, String labelName, float confidence, RectF location, int maxTrackFrames) {
            this.labelId = labelId;
            this.labelName = labelName;
            this.confidence = confidence;
            this.location = new RectF(location);
            this.prevLocation = new RectF(location);
            this.velocityX = 0.0f;
            this.velocityY = 0.0f;
            this.framesSinceDetection = 0;
            this.maxTrackFrames = maxTrackFrames;
        }

        /**
         * 用新的检测结果更新跟踪对象。
         */
        public void update(RectF newLocation, float newConfidence) {
            prevLocation.set(location);
            // 计算速度（中心点位移）
            float prevCx = prevLocation.centerX();
            float prevCy = prevLocation.centerY();
            float newCx = newLocation.centerX();
            float newCy = newLocation.centerY();
            velocityX = newCx - prevCx;
            velocityY = newCy - prevCy;
            location.set(newLocation);
            confidence = newConfidence;
            framesSinceDetection = 0;
        }

        /**
         * 预测当前位置（线性插值），并衰减置信度。
         */
        public void predict() {
            prevLocation.set(location);
            // 根据速度线性预测新位置
            location.offset(velocityX, velocityY);
            confidence *= CONFIDENCE_DECAY;
            framesSinceDetection++;
        }
    }

    /**
     * 用新的检测结果更新跟踪器。
     * 通过 IOU 匹配将新检测关联到已有轨迹，创建新轨迹，删除过期轨迹。
     *
     * @param detections 当前帧的检测结果列表
     */
    public void update(ArrayList<Recognition> detections) {
        if (detections == null || detections.isEmpty()) {
            // 没有新检测，所有轨迹进行预测
            for (TrackedObject obj : trackedObjects) {
                obj.predict();
            }
            removeExpired();
            return;
        }

        int numDetections = detections.size();
        int numTracks = trackedObjects.size();

        // 计算 IOU 矩阵
        float[][] iouMatrix = new float[numTracks][numDetections];
        for (int t = 0; t < numTracks; t++) {
            for (int d = 0; d < numDetections; d++) {
                iouMatrix[t][d] = computeIOU(
                        trackedObjects.get(t).location,
                        detections.get(d).getLocation()
                );
            }
        }

        // 贪心匹配：每次选择 IOU 最大的配对
        boolean[] detectionMatched = new boolean[numDetections];
        boolean[] trackMatched = new boolean[numTracks];

        while (true) {
            float bestIou = iouThreshold;
            int bestT = -1;
            int bestD = -1;

            for (int t = 0; t < numTracks; t++) {
                if (trackMatched[t]) continue;
                for (int d = 0; d < numDetections; d++) {
                    if (detectionMatched[d]) continue;
                    if (iouMatrix[t][d] > bestIou) {
                        bestIou = iouMatrix[t][d];
                        bestT = t;
                        bestD = d;
                    }
                }
            }

            if (bestT == -1) break;

            // 更新匹配的轨迹
            Recognition det = detections.get(bestD);
            trackedObjects.get(bestT).update(det.getLocation(), det.getConfidence());
            trackMatched[bestT] = true;
            detectionMatched[bestD] = true;
        }

        // 未匹配的轨迹进行预测
        for (int t = 0; t < numTracks; t++) {
            if (!trackMatched[t]) {
                trackedObjects.get(t).predict();
            }
        }

        // 未匹配的检测创建新轨迹
        for (int d = 0; d < numDetections; d++) {
            if (!detectionMatched[d]) {
                Recognition det = detections.get(d);
                TrackedObject newObj = new TrackedObject(
                        det.getLabelId(),
                        det.getLabelName(),
                        det.getConfidence(),
                        det.getLocation(),
                        maxTrackFrames
                );
                trackedObjects.add(newObj);
            }
        }

        // 删除过期轨迹
        removeExpired();
    }

    /**
     * 预测所有跟踪对象的当前位置。
     *
     * @return 预测的检测结果列表，置信度已衰减
     */
    public ArrayList<Recognition> predict() {
        ArrayList<Recognition> predictions = new ArrayList<>();
        for (TrackedObject obj : trackedObjects) {
            Recognition pred = new Recognition(
                    obj.labelId,
                    obj.labelName,
                    obj.confidence,
                    obj.confidence,
                    new RectF(obj.location)
            );
            predictions.add(pred);
        }
        return predictions;
    }

    /**
     * 获取当前所有跟踪对象。
     *
     * @return 跟踪对象列表
     */
    public ArrayList<TrackedObject> getTrackedObjects() {
        return trackedObjects;
    }

    /**
     * 计算两个矩形的 IOU（交并比）。
     *
     * @param a 矩形 A
     * @param b 矩形 B
     * @return IOU 值，范围 0.0 ~ 1.0
     */
    public static float computeIOU(RectF a, RectF b) {
        if (a == null || b == null || RectF.intersects(a, b) == false) {
            return 0.0f;
        }

        float intersectLeft = Math.max(a.left, b.left);
        float intersectTop = Math.max(a.top, b.top);
        float intersectRight = Math.min(a.right, b.right);
        float intersectBottom = Math.min(a.bottom, b.bottom);

        if (intersectRight <= intersectLeft || intersectBottom <= intersectTop) {
            return 0.0f;
        }

        float intersectArea = (intersectRight - intersectLeft) * (intersectBottom - intersectTop);
        float areaA = a.width() * a.height();
        float areaB = b.width() * b.height();

        if (areaA <= 0.0f || areaB <= 0.0f) {
            return 0.0f;
        }

        float unionArea = areaA + areaB - intersectArea;
        return intersectArea / unionArea;
    }

    /**
     * 设置最大跟踪帧数。
     *
     * @param frames 超过此帧数未匹配到检测的轨迹将被删除
     */
    public void setMaxTrackFrames(int frames) {
        this.maxTrackFrames = frames;
    }

    /**
     * 设置 IOU 匹配阈值。
     *
     * @param threshold IOU 阈值，范围 0.0 ~ 1.0
     */
    public void setIouThreshold(float threshold) {
        if (threshold >= 0.0f && threshold <= 1.0f) {
            this.iouThreshold = threshold;
        }
    }

    /**
     * 重置跟踪器，清除所有轨迹。
     */
    public void reset() {
        trackedObjects.clear();
    }

    /**
     * 删除过期轨迹（超过最大跟踪帧数仍未匹配到检测）。
     */
    private void removeExpired() {
        for (int i = trackedObjects.size() - 1; i >= 0; i--) {
            if (trackedObjects.get(i).framesSinceDetection > trackedObjects.get(i).maxTrackFrames) {
                trackedObjects.remove(i);
            }
        }
    }
}
