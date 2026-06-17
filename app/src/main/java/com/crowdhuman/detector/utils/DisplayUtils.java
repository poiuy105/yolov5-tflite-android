package com.crowdhuman.detector.utils;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

public class DisplayUtils {

    public static int getScreenOrientation(Context context) {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        @SuppressWarnings("deprecation")
        int rotation = wm.getDefaultDisplay().getRotation();
        switch (rotation) {
            case Surface.ROTATION_270: return 270;
            case Surface.ROTATION_180: return 180;
            case Surface.ROTATION_90: return 90;
            default: return 0;
        }
    }
}
