package com.crowdhuman.detector.analysis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import com.crowdhuman.detector.utils.Recognition;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Custom View that draws detection boxes directly on Canvas.
 * Replaces Bitmap-based rendering to avoid per-frame Bitmap allocation.
 */
public class DetectionOverlayView extends View {

    private final Paint boxPaint = new Paint();
    private final Paint textBgPaint = new Paint();
    private final Paint textPaint = new Paint();
    private final Paint fpsPaint = new Paint();
    private final float textScale;

    private ArrayList<Recognition> recognitions = new ArrayList<>();
    private boolean isFrontCamera = false;
    private float fps = 0;
    private int offsetX = 0, offsetY = 0;
    private int imageWidth = 0, imageHeight = 0;

    // Per-class colors (Material Design palette)
    private static final int[] CLASS_COLORS = {
        Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN,
        Color.MAGENTA, Color.parseColor("#FF9800"), Color.parseColor("#9C27B0"),
        Color.parseColor("#00BCD4"), Color.parseColor("#8BC34A"),
        Color.parseColor("#FF5722"), Color.parseColor("#3F51B5"),
        Color.parseColor("#E91E63"), Color.parseColor("#009688"),
        Color.parseColor("#CDDC39"), Color.parseColor("#795548"),
        Color.parseColor("#607D8B"), Color.parseColor("#FFC107"),
        Color.parseColor("#4CAF50"), Color.parseColor("#2196F3")
    };

    public DetectionOverlayView(Context context) {
        this(context, null);
    }

    public DetectionOverlayView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DetectionOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        this.textScale = context.getResources().getDisplayMetrics().density;

        boxPaint.setStrokeWidth(3 * textScale);
        boxPaint.setStyle(Paint.Style.STROKE);

        textPaint.setTextSize(14 * textScale);
        textPaint.setColor(Color.WHITE);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);

        textBgPaint.setStyle(Paint.Style.FILL);
        textBgPaint.setColor(Color.argb(180, 0, 0, 0));

        fpsPaint.setTextSize(16 * textScale);
        fpsPaint.setColor(Color.YELLOW);
        fpsPaint.setStyle(Paint.Style.FILL);
        fpsPaint.setTypeface(Typeface.DEFAULT_BOLD);
    }

    /**
     * Update detection results and trigger redraw.
     * Called from main thread.
     * Coordinates are already in preview space (no further transform needed).
     */
    public void updateResults(ArrayList<Recognition> recognitions,
                              boolean isFrontCamera, float fps,
                              int offsetX, int offsetY, int imageWidth, int imageHeight) {
        this.recognitions = recognitions != null ? recognitions : new ArrayList<>();
        this.isFrontCamera = isFrontCamera;
        this.fps = fps;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        postInvalidate();
    }

    /**
     * Clear all detections.
     */
    public void clearResults() {
        this.recognitions.clear();
        this.fps = 0;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (recognitions.isEmpty() && fps <= 0) return;

        for (Recognition res : recognitions) {
            RectF location = new RectF(res.getLocation());
            String label = res.getLabelName();
            float confidence = res.getConfidence();

            // Coordinates are already in preview space (including FIT_CENTER offset)
            // mapped by FullImageAnalyse's cameraToPreview matrix. No extra offset needed.

            // Clamp to view bounds
            location.left = Math.max(0, location.left);
            location.top = Math.max(0, location.top);
            location.right = Math.min((float) getWidth(), location.right);
            location.bottom = Math.min((float) getHeight(), location.bottom);

            // Per-class color
            int color = CLASS_COLORS[Math.abs(res.getLabelId()) % CLASS_COLORS.length];
            boxPaint.setColor(color);

            canvas.drawRect(location, boxPaint);

            // Draw label
            String text = label + ":" + String.format(Locale.US, "%.2f", confidence);
            float textWidth = textPaint.measureText(text);
            float textHeight = textPaint.getTextSize();
            float textX = location.left;
            float textY = location.top > textHeight ? location.top : location.top + textHeight;

            canvas.drawRect(textX - 2 * textScale, textY - textHeight - 2 * textScale,
                    textX + textWidth + 4 * textScale, textY + 2 * textScale, textBgPaint);
            canvas.drawText(text, textX, textY, textPaint);
        }

        // Draw FPS
        if (fps > 0) {
            canvas.drawText(String.format(Locale.US, "FPS: %.1f", fps),
                    10 * textScale, 24 * textScale, fpsPaint);
        }
    }
}
