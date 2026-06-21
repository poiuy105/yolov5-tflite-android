package com.crowdhuman.detector.detector;

import android.graphics.Bitmap;

/**
 * 帧差法运动检测器，适用于固定相机场景。
 * 通过比较当前帧与上一帧的降采样灰度图来计算运动分数。
 */
public class MotionDetector {

    private static final int DOWNSAMPLE = 4;
    private static final int DEFAULT_PIXEL_DIFF_THRESHOLD = 25;
    private static final float DEFAULT_MOTION_RATIO_THRESHOLD = 0.003f;

    private int pixelDiffThreshold = DEFAULT_PIXEL_DIFF_THRESHOLD;
    private float motionRatioThreshold = DEFAULT_MOTION_RATIO_THRESHOLD;

    private int[] prevGrayPixels;
    private int[] currentGrayPixels;
    private int prevWidth;
    private int prevHeight;

    private float lastMotionScore = 0.0f;

    // 二值运动掩码：160x120 (downW x downH)，1=运动，0=静止
    private byte[] motionMask;
    private int maskWidth;
    private int maskHeight;

    /**
     * 计算当前帧的运动分数。
     * 将当前帧降采样并转为灰度，与上一帧比较，返回 0.0-1.0 的运动分数。
     *
     * @param bitmap 当前帧图像
     * @return 运动分数，范围 0.0 ~ 1.0
     */
    public float computeMotionScore(Bitmap bitmap) {
        if (bitmap == null || bitmap.getWidth() == 0 || bitmap.getHeight() == 0) {
            return 0.0f;
        }

        // getPixels() does not support HardwareBitmap; convert to ARGB_8888 if needed
        Bitmap workBitmap = bitmap;
        if (bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
            try {
                workBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            } catch (Exception e) {
                return lastMotionScore;
            }
        }

        int width = workBitmap.getWidth();
        int height = workBitmap.getHeight();
        int downW = width / DOWNSAMPLE;
        int downH = height / DOWNSAMPLE;
        int totalPixels = downW * downH;

        if (totalPixels <= 0) {
            return 0.0f;
        }

        // 按需分配或复用数组
        if (currentGrayPixels == null || currentGrayPixels.length < totalPixels) {
            currentGrayPixels = new int[totalPixels];
        }

        // 降采样并转灰度
        extractDownsampledGray(workBitmap, currentGrayPixels, downW, downH);

        float score = 0.0f;

        if (prevGrayPixels != null && prevGrayPixels.length >= totalPixels
                && prevWidth == downW && prevHeight == downH) {
            // 按需分配或复用掩码数组
            if (motionMask == null || motionMask.length < totalPixels) {
                motionMask = new byte[totalPixels];
            }
            maskWidth = downW;
            maskHeight = downH;

            // 与上一帧比较，同时记录逐像素掩码
            int diffCount = 0;
            for (int i = 0; i < totalPixels; i++) {
                int diff = Math.abs(currentGrayPixels[i] - prevGrayPixels[i]);
                if (diff > pixelDiffThreshold) {
                    motionMask[i] = 1;
                    diffCount++;
                } else {
                    motionMask[i] = 0;
                }
            }
            score = (float) diffCount / totalPixels;
        } else {
            // 首帧无前帧，清空掩码
            if (motionMask == null || motionMask.length < totalPixels) {
                motionMask = new byte[totalPixels];
            }
            maskWidth = downW;
            maskHeight = downH;
            java.util.Arrays.fill(motionMask, 0, totalPixels, (byte) 0);
        }

        // 交换：当前帧变为上一帧
        int[] temp = prevGrayPixels;
        prevGrayPixels = currentGrayPixels;
        currentGrayPixels = temp;
        prevWidth = downW;
        prevHeight = downH;

        lastMotionScore = score;

        // 回收复制的 bitmap（如果是 HardwareBitmap 转换的）
        if (workBitmap != bitmap) {
            workBitmap.recycle();
        }

        return score;
    }

    /**
     * 检查是否检测到运动。
     *
     * @return 如果运动分数超过阈值返回 true
     */
    public boolean isMotionDetected() {
        return lastMotionScore >= motionRatioThreshold;
    }

    /**
     * 设置运动灵敏度（运动比例阈值）。
     * 值越小越灵敏。
     *
     * @param ratio 运动比例阈值，范围建议 0.0 ~ 1.0
     */
    public void setMotionSensitivity(float ratio) {
        if (ratio >= 0.0f && ratio <= 1.0f) {
            this.motionRatioThreshold = ratio;
        }
    }

    /**
     * 设置像素差异阈值。
     *
     * @param threshold 像素灰度差异阈值
     */
    public void setPixelDiffThreshold(int threshold) {
        if (threshold >= 0 && threshold <= 255) {
            this.pixelDiffThreshold = threshold;
        }
    }

    /**
     * 获取最近一次的运动分数。
     *
     * @return 运动分数 0.0 ~ 1.0
     */
    public float getLastMotionScore() {
        return lastMotionScore;
    }

    /**
     * 获取二值运动掩码（降采样后的灰度分辨率）。
     * 掩码尺寸为 maskWidth x maskHeight（通常 160x120），
     * 1 表示该像素有运动，0 表示静止。
     *
     * @return 掩码数组引用（勿修改），无运动时返回 null
     */
    public byte[] getMotionMask() {
        return motionMask;
    }

    /**
     * 获取运动掩码的宽度（通常 160）。
     */
    public int getMaskWidth() {
        return maskWidth;
    }

    /**
     * 获取运动掩码的高度（通常 120）。
     */
    public int getMaskHeight() {
        return maskHeight;
    }

    /**
     * 重置检测器状态，清除上一帧数据。
     */
    public void reset() {
        prevGrayPixels = null;
        currentGrayPixels = null;
        prevWidth = 0;
        prevHeight = 0;
        lastMotionScore = 0.0f;
        motionMask = null;
        maskWidth = 0;
        maskHeight = 0;
    }

    /**
     * 从 Bitmap 中提取降采样灰度像素。
     */
    private void extractDownsampledGray(Bitmap bitmap, int[] outGray, int downW, int downH) {
        int width = bitmap.getWidth();
        int[] pixels = new int[width];
        int index = 0;

        for (int y = 0; y < downH; y++) {
            int srcY = y * DOWNSAMPLE;
            bitmap.getPixels(pixels, 0, width, 0, srcY, width, 1);
            for (int x = 0; x < downW; x++) {
                int srcX = x * DOWNSAMPLE;
                int pixel = pixels[srcX];
                // 快速灰度转换：加权平均 (0.299R + 0.587G + 0.114B)
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                int gray = (r * 77 + g * 150 + b * 29) >> 8;
                outGray[index++] = gray;
            }
        }
    }
}
