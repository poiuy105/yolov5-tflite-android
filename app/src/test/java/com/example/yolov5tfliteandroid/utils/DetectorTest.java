package com.example.yolov5tfliteandroid.utils;

import android.graphics.RectF;

import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.*;

/**
 * Unit tests for core detection logic.
 */
public class DetectorTest {

    @Test
    public void testBoxIntersection_noOverlap() {
        RectF a = new RectF(0, 0, 10, 10);
        RectF b = new RectF(20, 20, 30, 30);
        // No overlap
        assertEquals(0f, boxIntersection(a, b), 0.001f);
    }

    @Test
    public void testBoxIntersection_partialOverlap() {
        RectF a = new RectF(0, 0, 10, 10);
        RectF b = new RectF(5, 5, 15, 15);
        // Overlap area: 5x5 = 25
        assertEquals(25f, boxIntersection(a, b), 0.001f);
    }

    @Test
    public void testBoxIntersection_contained() {
        RectF a = new RectF(0, 0, 10, 10);
        RectF b = new RectF(2, 2, 8, 8);
        // b is fully inside a, overlap = 6x6 = 36
        assertEquals(36f, boxIntersection(a, b), 0.001f);
    }

    @Test
    public void testBoxIntersection_identical() {
        RectF a = new RectF(0, 0, 10, 10);
        RectF b = new RectF(0, 0, 10, 10);
        assertEquals(100f, boxIntersection(a, b), 0.001f);
    }

    @Test
    public void testBoxIou_noOverlap() {
        RectF a = new RectF(0, 0, 10, 10);
        RectF b = new RectF(20, 20, 30, 30);
        assertEquals(0f, boxIou(a, b), 0.001f);
    }

    @Test
    public void testBoxIou_perfectOverlap() {
        RectF a = new RectF(0, 0, 10, 10);
        RectF b = new RectF(0, 0, 10, 10);
        assertEquals(1f, boxIou(a, b), 0.001f);
    }

    @Test
    public void testBoxIou_halfOverlap() {
        RectF a = new RectF(0, 0, 10, 10);
        RectF b = new RectF(5, 0, 15, 10);
        // intersection = 5*10=50, union = 100+100-50=150, iou = 50/150 = 0.333
        float iou = boxIou(a, b);
        assertTrue("IOU should be around 0.333, got " + iou, Math.abs(iou - 0.333f) < 0.01f);
    }

    @Test
    public void testRecognitionCreation() {
        Recognition r = new Recognition(0, "person", 0.95f, 0.90f, new RectF(10, 20, 100, 200));
        assertEquals(0, r.getLabelId().intValue());
        assertEquals("person", r.getLabelName());
        assertEquals(0.95f, r.getLabelScore().floatValue(), 0.001f);
        assertEquals(0.90f, r.getConfidence().floatValue(), 0.001f);
        // getLocation should return a copy
        RectF loc = r.getLocation();
        assertEquals(10f, loc.left, 0.001f);
        assertEquals(200f, loc.bottom, 0.001f);
    }

    @Test
    public void testRecognitionSetters() {
        Recognition r = new Recognition(0, "", 0f, 0f, new RectF(0, 0, 0, 0));
        r.setLabelName("car");
        r.setLabelId(3);
        r.setConfidence(0.85f);
        assertEquals("car", r.getLabelName());
        assertEquals(3, r.getLabelId().intValue());
        assertEquals(0.85f, r.getConfidence().floatValue(), 0.001f);
    }

    @Test
    public void testRecognitionToString() {
        Recognition r = new Recognition(0, "person", 0.95f, 0.90f, new RectF(10, 20, 100, 200));
        String str = r.toString();
        assertTrue(str.contains("person"));
        assertTrue(str.contains("90.0%"));
    }

    @Test
    public void testRecognitionLocationIsCopy() {
        RectF original = new RectF(10, 20, 100, 200);
        Recognition r = new Recognition(0, "person", 0.95f, 0.90f, original);
        RectF loc = r.getLocation();
        loc.set(0, 0, 0, 0);
        // Original should not be modified
        assertEquals(10f, r.getLocation().left, 0.001f);
    }

    // Helper methods mirroring Yolov5TFLiteDetector logic
    private float boxIntersection(RectF a, RectF b) {
        float maxLeft = Math.max(a.left, b.left);
        float maxTop = Math.max(a.top, b.top);
        float minRight = Math.min(a.right, b.right);
        float minBottom = Math.min(a.bottom, b.bottom);
        float w = minRight - maxLeft;
        float h = minBottom - maxTop;
        if (w < 0 || h < 0) return 0;
        return w * h;
    }

    private float boxUnion(RectF a, RectF b) {
        float i = boxIntersection(a, b);
        float u = (a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - i;
        return u;
    }

    private float boxIou(RectF a, RectF b) {
        float intersection = boxIntersection(a, b);
        float union = boxUnion(a, b);
        if (union <= 0) return 1;
        return intersection / union;
    }
}
