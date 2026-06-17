package com.crowdhuman.detector.utils;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.OutputStream;

public class ScreenshotUtils {

    public interface SaveCallback {
        void onSuccess();
        void onError(String message);
    }

    public static void saveToGallery(Context context, Bitmap bitmap, SaveCallback callback) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "detection_" + System.currentTimeMillis() + ".png");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CrowdHuman");
            }
            android.net.Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                    if (out != null) {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                        if (callback != null) callback.onSuccess();
                        return;
                    }
                }
            }
            if (callback != null) callback.onError("Failed to create output stream");
        } catch (Exception e) {
            Log.e("ScreenshotUtils", "Failed to save: " + e.getMessage(), e);
            if (callback != null) callback.onError(e.getMessage());
        } finally {
            // P2-13 FIX: Recycle the bitmap after saving to free memory
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }
}
