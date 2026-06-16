package com.example.yolov5tfliteandroid.analysis;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.example.yolov5tfliteandroid.utils.Recognition;

import java.util.ArrayList;
import java.util.Locale;

public class DetectionRenderer {

    private final Paint boxPaint = new Paint();
    private final Paint textBgPaint = new Paint();
    private final Paint textPaint = new Paint();
    private final float textScale;

    public DetectionRenderer(float density) {
        this.textScale = density;

        boxPaint.setStrokeWidth(3 * textScale);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setColor(Color.RED);

        textPaint.setTextSize(14 * textScale);
        textPaint.setColor(Color.WHITE);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);

        textBgPaint.setStyle(Paint.Style.FILL);
        textBgPaint.setColor(Color.argb(180, 0, 0, 0));
    }

    public Bitmap render(ArrayList<Recognition> recognitions, int width, int height, Matrix modelToPreviewTransform) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        for (Recognition res : recognitions) {
            RectF location = res.getLocation();
            String label = res.getLabelName();
            float confidence = res.getConfidence();
            modelToPreviewTransform.mapRect(location);

            location.left = Math.max(0, location.left);
            location.top = Math.max(0, location.top);
            location.right = Math.min(width, location.right);
            location.bottom = Math.min(height, location.bottom);

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

        return bitmap;
    }
}
