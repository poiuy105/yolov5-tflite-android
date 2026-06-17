package com.crowdhuman.detector.analysis;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.crowdhuman.detector.utils.Recognition;

import java.util.ArrayList;
import java.util.Locale;

public class DetectionRenderer {

    private final Paint boxPaint = new Paint();
    private final Paint textBgPaint = new Paint();
    private final Paint textPaint = new Paint();
    private final Paint fpsPaint = new Paint();
    private final float textScale;

    // Per-class colors ( Material Design palette )
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

    public DetectionRenderer(float density) {
        this.textScale = density;

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

    public Bitmap render(ArrayList<Recognition> recognitions, int width, int height,
                         Matrix modelToPreviewTransform, boolean isFrontCamera, float fps) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        for (Recognition res : recognitions) {
            // P1-7 FIX: Copy location to avoid mutating the original Recognition object
            RectF location = new RectF(res.getLocation());
            String label = res.getLabelName();
            float confidence = res.getConfidence();
            modelToPreviewTransform.mapRect(location);

            // Front camera: horizontal mirror
            if (isFrontCamera) {
                float left = width - location.right;
                float right = width - location.left;
                location.left = left;
                location.right = right;
            }

            location.left = Math.max(0, location.left);
            location.top = Math.max(0, location.top);
            location.right = Math.min(width, location.right);
            location.bottom = Math.min(height, location.bottom);

            // Per-class color
            int color = CLASS_COLORS[Math.abs(res.getLabelId()) % CLASS_COLORS.length];
            boxPaint.setColor(color);

            canvas.drawRect(location, boxPaint);

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
            canvas.drawText(String.format(Locale.US, "FPS: %.1f", fps), 10 * textScale, 24 * textScale, fpsPaint);
        }

        return bitmap;
    }
}
