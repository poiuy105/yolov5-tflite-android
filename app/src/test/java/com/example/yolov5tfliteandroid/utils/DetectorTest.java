package com.example.yolov5tfliteandroid.utils;

import android.graphics.RectF;

import org.junit.Test;

import static org.junit.Assert.*;

public class DetectorTest {

    @Test
    public void testBoxIntersection_noOverlap() {
        assertEquals(0f, boxIntersection(new RectF(0,0,10,10), new RectF(20,20,30,30)), 0.001f);
    }

    @Test
    public void testBoxIntersection_partialOverlap() {
        assertEquals(25f, boxIntersection(new RectF(0,0,10,10), new RectF(5,5,15,15)), 0.001f);
    }

    @Test
    public void testBoxIntersection_contained() {
        assertEquals(36f, boxIntersection(new RectF(0,0,10,10), new RectF(2,2,8,8)), 0.001f);
    }

    @Test
    public void testBoxIntersection_identical() {
        assertEquals(100f, boxIntersection(new RectF(0,0,10,10), new RectF(0,0,10,10)), 0.001f);
    }

    @Test
    public void testBoxIou_noOverlap() {
        assertEquals(0f, boxIou(new RectF(0,0,10,10), new RectF(20,20,30,30)), 0.001f);
    }

    @Test
    public void testBoxIou_perfectOverlap() {
        assertEquals(1f, boxIou(new RectF(0,0,10,10), new RectF(0,0,10,10)), 0.001f);
    }

    @Test
    public void testBoxIou_halfOverlap() {
        float iou = boxIou(new RectF(0,0,10,10), new RectF(5,0,15,10));
        assertTrue("IOU should be ~0.333, got " + iou, Math.abs(iou - 0.333f) < 0.01f);
    }

    @Test
    public void testRecognitionCreation() {
        Recognition r = new Recognition(0, "person", 0.95f, 0.90f, new RectF(10,20,100,200));
        assertEquals(0, r.getLabelId());
        assertEquals("person", r.getLabelName());
        assertEquals(0.95f, r.getLabelScore(), 0.001f);
        assertEquals(0.90f, r.getConfidence(), 0.001f);
        assertEquals(10f, r.getLocation().left, 0.001f);
    }

    @Test
    public void testRecognitionSetters() {
        Recognition r = new Recognition(0, "", 0f, 0f, new RectF(0,0,0,0));
        r.setLabelName("car");
        r.setLabelId(3);
        r.setConfidence(0.85f);
        assertEquals("car", r.getLabelName());
        assertEquals(3, r.getLabelId());
        assertEquals(0.85f, r.getConfidence(), 0.001f);
    }

    @Test
    public void testRecognitionDefensiveCopy() {
        Recognition r = new Recognition(0, "person", 0.95f, 0.90f, new RectF(10,20,100,200));
        r.getLocation().set(0,0,0,0);
        assertEquals(10f, r.getLocation().left, 0.001f);
    }

    private float boxIntersection(RectF a, RectF b) {
        float w = Math.min(a.right, b.right) - Math.max(a.left, b.left);
        float h = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
        if (w < 0 || h < 0) return 0;
        return w * h;
    }

    private float boxUnion(RectF a, RectF b) {
        float i = boxIntersection(a, b);
        return (a.right-a.left)*(a.bottom-a.top) + (b.right-b.left)*(b.bottom-b.top) - i;
    }

    private float boxIou(RectF a, RectF b) {
        float u = boxUnion(a, b);
        return u <= 0 ? 1 : boxIntersection(a, b) / u;
    }
}
