package com.crowdhuman.detector.detector;

import android.graphics.Rect;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Queue;

/**
 * 分块运动网格处理器。
 * 将运动掩码划分为块网格，通过连通域分析提取运动区域，
 * 输出适合裁剪送入小模型的矩形区域列表。
 *
 * 坐标约定：输入掩码为降采样后分辨率（如 160x120），
 * 输出矩形为原始帧坐标（如 640x480）。
 */
public class BlockMotionGrid {

    private static final String TAG = "BlockMotionGrid";

    // === 可调参数 ===
    /** 原始帧坐标下的块大小（像素） */
    private int blockPixelSize = 16;
    /** 块激活所需的最小运动像素数（块内总掩码像素中） */
    private int blockActivateThreshold = 4;
    /** 区域 padding（原始帧坐标像素） */
    private int regionPadding = 24;
    /** 最小裁剪尺寸（原始帧坐标像素，方形） */
    private int minCropSize = 120;
    /** 最大裁剪尺寸（原始帧坐标像素，方形，超过则回退全帧） */
    private int maxCropSize = 320;
    /** 最大区域数 */
    private int maxRegions = 3;
    /** 区域合并距离（原始帧坐标像素） */
    private int mergeDistance = 32;
    /** 覆盖率回退阈值（0-1） */
    private float coverageFallback = 0.6f;

    // === 内部状态 ===
    private final int downsample;

    /**
     * @param downsample MotionDetector 的降采样因子（通常 4）
     */
    public BlockMotionGrid(int downsample) {
        this.downsample = downsample;
    }

    /**
     * 提取结果：运动区域列表 + 是否应回退全帧推理。
     */
    public static class ExtractionResult {
        /** 运动区域列表（原始帧坐标），可能为空 */
        public final ArrayList<Rect> regions;
        /** true 表示运动覆盖面积过大，应使用全帧 320 模型 */
        public final boolean shouldFallback;
        /** 运动覆盖比例（0-1） */
        public final float coverageRatio;

        public ExtractionResult(ArrayList<Rect> regions, boolean shouldFallback, float coverageRatio) {
            this.regions = regions;
            this.shouldFallback = shouldFallback;
            this.coverageRatio = coverageRatio;
        }
    }

    /**
     * 从运动掩码中提取运动区域。
     *
     * @param mask      二值运动掩码（1=运动，0=静止），行优先
     * @param maskW     掩码宽度（如 160）
     * @param maskH     掩码高度（如 120）
     * @param frameW    原始帧宽度（如 640）
     * @param frameH    原始帧高度（如 480）
     * @return 提取结果
     */
    public ExtractionResult extract(byte[] mask, int maskW, int maskH,
                                    int frameW, int frameH) {
        if (mask == null || maskW <= 0 || maskH <= 0) {
            return new ExtractionResult(new ArrayList<>(), false, 0f);
        }

        // 1. 计算块网格
        // 掩码中每个块的大小（掩码像素）
        int maskBlockW = blockPixelSize / downsample; // 16/4 = 4
        int maskBlockH = blockPixelSize / downsample; // 16/4 = 4
        int gridCols = maskW / maskBlockW; // 160/4 = 40
        int gridRows = maskH / maskBlockH; // 120/4 = 30
        int totalBlocks = gridCols * gridRows;

        // 2. 评估每个块的活跃状态
        boolean[] active = new boolean[totalBlocks];
        int activeBlockCount = 0;

        for (int row = 0; row < gridRows; row++) {
            for (int col = 0; col < gridCols; col++) {
                int motionCount = 0;
                int baseY = row * maskBlockH;
                int baseX = col * maskBlockW;
                for (int dy = 0; dy < maskBlockH; dy++) {
                    int maskY = baseY + dy;
                    if (maskY >= maskH) break;
                    for (int dx = 0; dx < maskBlockW; dx++) {
                        int maskX = baseX + dx;
                        if (maskX >= maskW) break;
                        if (mask[maskY * maskW + maskX] != 0) {
                            motionCount++;
                        }
                    }
                }
                if (motionCount >= blockActivateThreshold) {
                    active[row * gridCols + col] = true;
                    activeBlockCount++;
                }
            }
        }

        // 3. 计算覆盖率
        float coverageRatio = (float) activeBlockCount / totalBlocks;

        // 4. 覆盖率过高 → 回退全帧
        if (coverageRatio > coverageFallback) {
            Log.d(TAG, "Coverage " + String.format("%.1f%%", coverageRatio * 100)
                    + " exceeds threshold, fallback to full frame");
            return new ExtractionResult(new ArrayList<>(), true, coverageRatio);
        }

        // 5. 无活跃块 → 空结果
        if (activeBlockCount == 0) {
            return new ExtractionResult(new ArrayList<>(), false, coverageRatio);
        }

        // 6. BFS 连通域分析
        boolean[] visited = new boolean[totalBlocks];
        ArrayList<Rect> components = new ArrayList<>();

        for (int row = 0; row < gridRows; row++) {
            for (int col = 0; col < gridCols; col++) {
                int idx = row * gridCols + col;
                if (active[idx] && !visited[idx]) {
                    // BFS 查找连通块
                    int minCol = col, maxCol = col;
                    int minRow = row, maxRow = row;

                    Queue<Integer> queue = new LinkedList<>();
                    queue.add(idx);
                    visited[idx] = true;

                    while (!queue.isEmpty()) {
                        int cur = queue.poll();
                        int curRow = cur / gridCols;
                        int curCol = cur % gridCols;

                        minCol = Math.min(minCol, curCol);
                        maxCol = Math.max(maxCol, curCol);
                        minRow = Math.min(minRow, curRow);
                        maxRow = Math.max(maxRow, curRow);

                        // 检查 4-邻域
                        int[][] neighbors = {
                                {curRow - 1, curCol}, {curRow + 1, curCol},
                                {curRow, curCol - 1}, {curRow, curCol + 1}
                        };
                        for (int[] n : neighbors) {
                            int nr = n[0], nc = n[1];
                            if (nr >= 0 && nr < gridRows && nc >= 0 && nc < gridCols) {
                                int nIdx = nr * gridCols + nc;
                                if (active[nIdx] && !visited[nIdx]) {
                                    visited[nIdx] = true;
                                    queue.add(nIdx);
                                }
                            }
                        }
                    }

                    // 块坐标 → 原始帧坐标（运动区域的边界框）
                    int left = minCol * blockPixelSize;
                    int top = minRow * blockPixelSize;
                    int right = (maxCol + 1) * blockPixelSize;
                    int bottom = (maxRow + 1) * blockPixelSize;
                    components.add(new Rect(left, top, right, bottom));
                }
            }
        }

        // 7. 合并近距离区域
        components = mergeNearbyRegions(components);

        // 8. 如果区域过多，合并最近的直到满足 maxRegions
        while (components.size() > maxRegions) {
            mergeClosestPair(components);
        }

        // 9. 对每个区域：方形化 + padding + min/max 约束 + 帧边界裁剪
        ArrayList<Rect> finalRegions = new ArrayList<>();
        long totalCropArea = 0;

        for (Rect r : components) {
            Rect square = makeSquareCrop(r, frameW, frameH);
            if (square.width() > maxCropSize || square.height() > maxCropSize) {
                // 单个区域过大 → 回退全帧
                Log.d(TAG, "Region " + square + " exceeds max crop, fallback");
                return new ExtractionResult(new ArrayList<>(), true, coverageRatio);
            }
            finalRegions.add(square);
            totalCropArea += (long) square.width() * square.height();
        }

        // 10. 最终覆盖率检查（包括 padding 扩展后的面积）
        float finalCoverage = (float) totalCropArea / ((long) frameW * frameH);
        if (finalCoverage > coverageFallback) {
            Log.d(TAG, "Final coverage " + String.format("%.1f%%", finalCoverage * 100)
                    + " exceeds threshold, fallback");
            return new ExtractionResult(new ArrayList<>(), true, finalCoverage);
        }

        return new ExtractionResult(finalRegions, false, finalCoverage);
    }

    /**
     * 合并距离小于 mergeDistance 的区域。
     */
    private ArrayList<Rect> mergeNearbyRegions(ArrayList<Rect> regions) {
        if (regions.size() <= 1) return regions;

        boolean merged = true;
        while (merged) {
            merged = false;
            ArrayList<Rect> result = new ArrayList<>();
            boolean[] consumed = new boolean[regions.size()];

            for (int i = 0; i < regions.size(); i++) {
                if (consumed[i]) continue;
                Rect current = new Rect(regions.get(i));

                for (int j = i + 1; j < regions.size(); j++) {
                    if (consumed[j]) continue;
                    Rect other = regions.get(j);

                    // 计算两个矩形之间的最小间距
                    int gapX = Math.max(0, Math.max(current.left - other.right, other.left - current.right));
                    int gapY = Math.max(0, Math.max(current.top - other.bottom, other.top - current.bottom));
                    int gap = Math.max(gapX, gapY);

                    if (gap <= mergeDistance) {
                        current.union(other);
                        consumed[j] = true;
                        merged = true;
                    }
                }
                result.add(current);
            }
            regions = result;
        }
        return regions;
    }

    /**
     * 合并距离最近的两个区域（贪心策略）。
     */
    private void mergeClosestPair(ArrayList<Rect> regions) {
        if (regions.size() <= 1) return;

        float bestDist = Float.MAX_VALUE;
        int bestI = 0, bestJ = 1;

        for (int i = 0; i < regions.size(); i++) {
            for (int j = i + 1; j < regions.size(); j++) {
                float dist = regionDistance(regions.get(i), regions.get(j));
                if (dist < bestDist) {
                    bestDist = dist;
                    bestI = i;
                    bestJ = j;
                }
            }
        }

        Rect merged = new Rect(regions.get(bestI));
        merged.union(regions.get(bestJ));
        regions.remove(bestJ);
        regions.remove(bestI);
        regions.add(merged);
    }

    /**
     * 计算两个矩形中心点之间的欧氏距离。
     */
    private float regionDistance(Rect a, Rect b) {
        float dx = a.exactCenterX() - b.exactCenterX();
        float dy = a.exactCenterY() - b.exactCenterY();
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * 将运动区域扩展为方形裁剪（含 padding 和 min/max 约束）。
     * 以较长边为基准做方形化，中心不变，然后加 padding。
     */
    private Rect makeSquareCrop(Rect region, int frameW, int frameH) {
        int w = region.width();
        int h = region.height();
        int cx = region.centerX();
        int cy = region.centerY();

        // 方形化：以较长边为基准
        int side = Math.max(w, h);

        // 加 padding
        side += regionPadding * 2;

        // 最小尺寸约束
        side = Math.max(side, minCropSize);

        // 以中心展开（保持中心不变）
        int half = side / 2;
        int left = cx - half;
        int top = cy - half;
        int right = left + side;
        int bottom = top + side;

        // 帧边界裁剪
        if (left < 0) {
            right -= left;
            left = 0;
        }
        if (top < 0) {
            bottom -= top;
            top = 0;
        }
        if (right > frameW) {
            left -= (right - frameW);
            right = frameW;
        }
        if (bottom > frameH) {
            top -= (bottom - frameH);
            bottom = frameH;
        }

        // 确保不越界
        left = Math.max(0, left);
        top = Math.max(0, top);
        right = Math.min(frameW, right);
        bottom = Math.min(frameH, bottom);

        return new Rect(left, top, right, bottom);
    }

    // === 参数设置 ===

    public void setBlockPixelSize(int size) {
        this.blockPixelSize = size;
    }

    public void setBlockActivateThreshold(int threshold) {
        this.blockActivateThreshold = threshold;
    }

    public void setRegionPadding(int padding) {
        this.regionPadding = padding;
    }

    public void setMinCropSize(int size) {
        this.minCropSize = size;
    }

    public void setMaxCropSize(int size) {
        this.maxCropSize = size;
    }

    public void setMaxRegions(int max) {
        this.maxRegions = max;
    }

    public void setMergeDistance(int distance) {
        this.mergeDistance = distance;
    }

    public void setCoverageFallback(float ratio) {
        this.coverageFallback = ratio;
    }
}
