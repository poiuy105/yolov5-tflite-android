# Orientation Switch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the rotation offset button with a true screen orientation toggle (portrait/landscape/auto), add a landscape layout variant, and ensure camera preview and detection overlay correctly adapt to orientation changes.

**Architecture:** Remove `screenOrientation="portrait"` lock from Manifest. The rotate button cycles through three states: auto (sensor-driven), portrait locked, landscape locked. When orientation changes, `onConfigurationChanged()` updates the layout and restarts the camera with the new dimensions. A dedicated `res/layout-land/activity_main.xml` provides an optimized landscape UI with the control panel on the right side.

**Tech Stack:** Java, Android SDK, CameraX, ConstraintLayout, Material Design Components

---

## File Structure

| File | Responsibility |
|------|---------------|
| `AndroidManifest.xml` | Remove portrait lock, keep configChanges |
| `MainActivity.java` | Replace rotation offset logic with orientation toggle; handle config changes |
| `activity_main.xml` | Minor adjustments for dynamic orientation |
| `activity_main.xml` (land) | New landscape layout: preview left, controls right |
| `FullImageAnalyse.java` | Remove rotation offset field (no longer needed) |
| `strings.xml` | Update rotate button description |

---

## Task 1: Unlock orientation in Manifest

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Remove screenOrientation lock**

```xml
<!-- Before -->
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:screenOrientation="portrait">

<!-- After -->
<activity
    android:name=".MainActivity"
    android:exported="true">
```

Keep `android:configChanges="orientation|screenSize|keyboardHidden"` at application level (already present).

- [ ] **Step 2: Commit**

```bash
git add -A
git commit -m "feat: unlock screen orientation in manifest"
```

---

## Task 2: Replace rotation offset with orientation toggle

**Files:**
- Modify: `app/src/main/java/com/crowdhuman/detector/MainActivity.java`

- [ ] **Step 1: Replace rotation constants with orientation mode constants**

```java
// Remove:
// private static final int[] ROTATION_OFFSETS = {0, 90, 270};
// private static final String[] ROTATION_LABELS = {"0°", "90°", "270°"};
// private int rotationOffsetIndex = 0;

// Add:
private static final int ORIENTATION_AUTO = 0;
private static final int ORIENTATION_PORTRAIT = 1;
private static final int ORIENTATION_LANDSCAPE = 2;
private static final String[] ORIENTATION_LABELS = {"Auto", "Portrait", "Landscape"};
private int orientationMode = ORIENTATION_AUTO;
```

- [ ] **Step 2: Update rotate button click handler**

```java
rotateButton.setOnClickListener(v -> {
    orientationMode = (orientationMode + 1) % 3;
    applyOrientationMode();
});
```

- [ ] **Step 3: Add applyOrientationMode() method**

```java
private void applyOrientationMode() {
    switch (orientationMode) {
        case ORIENTATION_AUTO:
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
            break;
        case ORIENTATION_PORTRAIT:
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            break;
        case ORIENTATION_LANDSCAPE:
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            break;
    }
    Toast.makeText(this, "Orientation: " + ORIENTATION_LABELS[orientationMode], Toast.LENGTH_SHORT).show();
}
```

- [ ] **Step 4: Remove setRotationOffset() method and simplify startCameraWithCurrentMode()**

```java
// In startCameraWithCurrentMode(), remove:
// int effectiveRotation = (rotation + ROTATION_OFFSETS[rotationOffsetIndex]) % 360;
// currentAnalyser.setRotation(effectiveRotation);

// Just use:
int rotation = DisplayUtils.getScreenOrientation(this);
currentAnalyser = new FullImageAnalyse(this, cameraPreviewMatch, rotation,
        detector, cameraProcess.isFrontCamera());
```

- [ ] **Step 5: Add onConfigurationChanged() override**

```java
@Override
public void onConfigurationChanged(android.content.res.Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
    // Restart camera to adapt to new dimensions
    startCameraWithCurrentMode();
}
```

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: replace rotation offset with orientation toggle (auto/portrait/landscape)"
```

---

## Task 3: Remove rotation offset from FullImageAnalyse

**Files:**
- Modify: `app/src/main/java/com/crowdhuman/detector/analysis/FullImageAnalyse.java`

- [ ] **Step 1: Remove setRotation() method and make rotation final again**

```java
// Change:
// private int rotation;
// To:
private final int rotation;

// Remove setRotation() method entirely
```

- [ ] **Step 2: Commit**

```bash
git add -A
git commit -m "refactor: remove setRotation() - rotation now comes from screen orientation only"
```

---

## Task 4: Create landscape layout

**Files:**
- Create: `app/src/main/res/layout-land/activity_main.xml`

- [ ] **Step 1: Create landscape layout directory**

```bash
mkdir -p app/src/main/res/layout-land
```

- [ ] **Step 2: Write landscape layout**

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <!-- Preview takes left 70% -->
    <androidx.camera.view.PreviewView
        android:id="@+id/camera_preview_match"
        android:layout_width="0dp"
        android:layout_height="0dp"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintLeft_toLeftOf="parent"
        app:layout_constraintRight_toLeftOf="@id/side_panel"
        app:layout_constraintHorizontal_weight="7"/>

    <ImageView
        android:id="@+id/box_label_canvas"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:contentDescription="@string/detection_overlay_desc"
        android:clickable="false"
        android:focusable="false"
        app:layout_constraintTop_toTopOf="@id/camera_preview_match"
        app:layout_constraintBottom_toBottomOf="@id/camera_preview_match"
        app:layout_constraintLeft_toLeftOf="@id/camera_preview_match"
        app:layout_constraintRight_toRightOf="@id/camera_preview_match"/>

    <!-- Side panel: right 30% -->
    <androidx.constraintlayout.widget.ConstraintLayout
        android:id="@+id/side_panel"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:background="@color/control_panel_bg"
        android:padding="8dp"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintRight_toRightOf="parent"
        app:layout_constraintLeft_toRightOf="@id/camera_preview_match"
        app:layout_constraintHorizontal_weight="3">

        <!-- FPS -->
        <TextView
            android:id="@+id/fps_text"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="@color/yellow"
            android:textSize="14sp"
            android:textStyle="bold"
            app:layout_constraintTop_toTopOf="parent"
            app:layout_constraintRight_toRightOf="parent"/>

        <!-- Buttons row -->
        <LinearLayout
            android:id="@+id/top_toolbar"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            app:layout_constraintTop_toBottomOf="@id/fps_text"
            app:layout_constraintRight_toRightOf="parent"
            android:layout_marginTop="8dp">

            <ImageView
                android:id="@+id/camera_switch"
                android:layout_width="36dp"
                android:layout_height="36dp"
                android:padding="6dp"
                android:src="@drawable/ic_camera_switch"
                android:background="@drawable/bg_circle_button"
                android:layout_marginEnd="6dp"/>

            <ImageView
                android:id="@+id/screenshot"
                android:layout_width="36dp"
                android:layout_height="36dp"
                android:padding="6dp"
                android:src="@drawable/ic_screenshot"
                android:background="@drawable/bg_circle_button"
                android:layout_marginEnd="6dp"/>

            <ImageView
                android:id="@+id/rotate_button"
                android:layout_width="36dp"
                android:layout_height="36dp"
                android:padding="6dp"
                android:src="@drawable/ic_rotate"
                android:background="@drawable/bg_circle_button"
                android:layout_marginEnd="6dp"/>

            <ImageView
                android:id="@+id/filter_button"
                android:layout_width="36dp"
                android:layout_height="36dp"
                android:padding="6dp"
                android:src="@drawable/ic_filter"
                android:background="@drawable/bg_circle_button"/>
        </LinearLayout>

        <!-- Threshold -->
        <TextView
            android:id="@+id/threshold_label"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="@color/white"
            android:text="@string/threshold_label"
            app:layout_constraintTop_toBottomOf="@id/top_toolbar"
            app:layout_constraintLeft_toLeftOf="parent"
            android:layout_marginTop="12dp"/>

        <SeekBar
            android:id="@+id/threshold_seekbar"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:max="100"
            android:progress="25"
            app:layout_constraintTop_toBottomOf="@id/threshold_label"
            app:layout_constraintLeft_toLeftOf="parent"
            app:layout_constraintRight_toRightOf="parent"/>

        <!-- Stats -->
        <TextView
            android:id="@+id/detect_name"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="@color/white"
            android:text="@string/detect_count_label"
            app:layout_constraintTop_toBottomOf="@id/threshold_seekbar"
            app:layout_constraintLeft_toLeftOf="parent"
            android:layout_marginTop="8dp"/>

        <TextView
            android:id="@+id/detect_count"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="@color/white"
            app:layout_constraintTop_toTopOf="@id/detect_name"
            app:layout_constraintRight_toRightOf="parent"/>

        <TextView
            android:id="@+id/inference"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="@color/white"
            android:text="@string/inference_time_label"
            app:layout_constraintTop_toBottomOf="@id/detect_name"
            app:layout_constraintLeft_toLeftOf="parent"
            android:layout_marginTop="4dp"/>

        <TextView
            android:id="@+id/inference_time"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="@color/white"
            app:layout_constraintTop_toTopOf="@id/inference"
            app:layout_constraintRight_toRightOf="parent"/>

        <TextView
            android:id="@+id/frame"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="@color/white"
            android:text="@string/frame_size_label"
            app:layout_constraintTop_toBottomOf="@id/inference"
            app:layout_constraintLeft_toLeftOf="parent"
            android:layout_marginTop="4dp"/>

        <TextView
            android:id="@+id/frame_size"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="@color/white"
            app:layout_constraintTop_toTopOf="@id/frame"
            app:layout_constraintRight_toRightOf="parent"/>

        <!-- Model selector -->
        <TextView
            android:id="@+id/model_name"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="@color/white"
            android:text="@string/model_label"
            app:layout_constraintTop_toBottomOf="@id/frame"
            app:layout_constraintLeft_toLeftOf="parent"
            android:layout_marginTop="8dp"/>

        <Spinner
            android:id="@+id/model"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:background="#00000000"
            android:entries="@array/model"
            app:layout_constraintTop_toTopOf="@id/model_name"
            app:layout_constraintRight_toRightOf="parent"/>

    </androidx.constraintlayout.widget.ConstraintLayout>

    <!-- Loading & Error (centered on preview) -->
    <com.google.android.material.progressindicator.CircularProgressIndicator
        android:id="@+id/loading_indicator"
        android:layout_width="48dp"
        android:layout_height="48dp"
        android:indeterminate="true"
        android:visibility="gone"
        app:indicatorColor="@color/white"
        app:layout_constraintTop_toTopOf="@id/camera_preview_match"
        app:layout_constraintBottom_toBottomOf="@id/camera_preview_match"
        app:layout_constraintLeft_toLeftOf="@id/camera_preview_match"
        app:layout_constraintRight_toRightOf="@id/camera_preview_match"/>

    <LinearLayout
        android:id="@+id/error_panel"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:gravity="center"
        android:padding="24dp"
        android:background="@color/error_panel_bg"
        android:visibility="gone"
        app:layout_constraintTop_toTopOf="@id/camera_preview_match"
        app:layout_constraintBottom_toBottomOf="@id/camera_preview_match"
        app:layout_constraintLeft_toLeftOf="@id/camera_preview_match"
        app:layout_constraintRight_toRightOf="@id/camera_preview_match">

        <TextView
            android:id="@+id/error_text"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="@color/white"
            android:textSize="16sp"
            android:gravity="center"
            android:layout_marginBottom="16dp"/>

        <com.google.android.material.button.MaterialButton
            android:id="@+id/retry_button"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/retry"
            app:cornerRadius="8dp"/>
    </LinearLayout>

</androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: add landscape layout with side panel"
```

---

## Task 5: Update strings

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Update rotate button description**

```xml
<!-- Change from rotation angle to orientation mode -->
<string name="rotate_desc">Switch orientation</string>
```

- [ ] **Step 2: Commit**

```bash
git add -A
git commit -m "chore: update rotate button description for orientation toggle"
```

---

## Task 6: Push and verify CI

- [ ] **Step 1: Push all commits**

```bash
git push origin master
```

- [ ] **Step 2: Wait for CI success**

Check: `https://github.com/poiuy105/yolov5-tflite-android/actions`

---

## Self-Review Checklist

- [ ] **Spec coverage:** Orientation toggle, landscape layout, config change handling
- [ ] **Placeholder scan:** No TBD/TODO
- [ ] **Type consistency:** `orientationMode` is int (0/1/2) throughout
- [ ] **API compatibility:** `setRequestedOrientation()` available since API 1
- [ ] **Layout fallback:** Portrait layout unchanged, landscape is additive
