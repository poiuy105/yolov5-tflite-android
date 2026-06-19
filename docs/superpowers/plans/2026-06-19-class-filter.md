# Class Filter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove crowdhuman model, restore multi-class detection, and add UI controls to filter which object classes are displayed/recognized.

**Architecture:** The YOLOv5 model outputs 80 COCO classes. Detection results flow through `decodeOutput` → `NMS` → `FullImageAnalyse` → `DetectionRenderer`. We add a `Set<Integer> enabledLabels` filter that can be applied at either the detector level (affects NMS) or the renderer level (affects display only). UI uses a multi-select dialog triggered from a new filter button.

**Tech Stack:** Java, Android SDK, CameraX, TensorFlow Lite, RxJava3, Material Design Components

---

## File Structure

| File | Responsibility |
|------|---------------|
| `Yolov5TFLiteDetector.java` | Remove crowdhuman config; restore original multi-class `decodeOutput`; add `enabledLabels` filter set |
| `NmsProcessor.java` | Accept `enabledLabels` filter; skip filtered classes during NMS |
| `FullImageAnalyse.java` | Pass `enabledLabels` to detector; expose `setEnabledLabels()` method |
| `MainActivity.java` | Add filter button; manage `enabledLabels` state; pass to analyser |
| `activity_main.xml` | Add filter button to top_toolbar |
| `strings.xml` | Add filter button description |
| `ic_filter.xml` | New filter icon drawable |
| `build-apk.yml` | Remove crowdhuman model download step |

---

## Task 1: Remove crowdhuman model and restore multi-class detection

**Files:**
- Modify: `app/src/main/java/com/crowdhuman/detector/detector/Yolov5TFLiteDetector.java`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `.github/workflows/build-apk.yml`
- Delete: `app/src/main/assets/person_label.txt` (if exists)

- [ ] **Step 1: Remove crowdhuman from MODEL_CONFIGS**

```java
// In Yolov5TFLiteDetector.java, static block:
static {
    MODEL_CONFIGS.put("yolov5s", new ModelConfig("yolov5s", "yolov5s-fp16-320-metadata.tflite", "coco_label.txt", 80, false));
    MODEL_CONFIGS.put("yolov5n", new ModelConfig("yolov5n", "yolov5n-fp16-320.tflite", "coco_label.txt", 80, false));
    MODEL_CONFIGS.put("yolov5m", new ModelConfig("yolov5m", "yolov5m-fp16-320.tflite", "coco_label.txt", 80, false));
    MODEL_CONFIGS.put("yolov5s-int8", new ModelConfig("yolov5s-int8", "yolov5s-int8-320.tflite", "coco_label.txt", 80, true));
    // REMOVED: crowdhuman
}
```

- [ ] **Step 2: Restore original decodeOutput (remove PERSON_LABEL_ID forced assignment)**

```java
// In decodeOutput(), change:
// FROM:
// results.add(new Recognition(PERSON_LABEL_ID, "", maxScore, confidence, new RectF(...)));
// TO:
results.add(new Recognition(labelId, "", maxScore, confidence, new RectF(xmin, ymin, xmax, ymax)));
```

Also remove the `PERSON_LABEL_ID` constant if it exists.

- [ ] **Step 3: Change DEFAULT_MODEL to "yolov5s"**

```java
// In MainActivity.java:
private static final String DEFAULT_MODEL = "yolov5s";
```

- [ ] **Step 4: Remove crowdhuman from Spinner options**

```xml
<!-- In strings.xml, model array -->
<string-array name="model">
    <item>yolov5s</item>
    <item>yolov5n</item>
    <item>yolov5m</item>
    <item>yolov5s-int8</item>
</string-array>
```

- [ ] **Step 5: Remove crowdhuman model download from CI**

```yaml
# In build-apk.yml, remove the crowdhuman download step
# Keep only the verify step that checks existing assets
```

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: remove crowdhuman model, restore multi-class detection"
```

---

## Task 2: Add enabledLabels filter to detector

**Files:**
- Modify: `app/src/main/java/com/crowdhuman/detector/detector/Yolov5TFLiteDetector.java`
- Modify: `app/src/main/java/com/crowdhuman/detector/detector/NmsProcessor.java`

- [ ] **Step 1: Add enabledLabels field and setter to Yolov5TFLiteDetector**

```java
// In Yolov5TFLiteDetector.java:
private java.util.Set<Integer> enabledLabels;

public void setEnabledLabels(java.util.Set<Integer> labels) {
    this.enabledLabels = labels;
}

public java.util.Set<Integer> getEnabledLabels() {
    return enabledLabels;
}
```

- [ ] **Step 2: Pass enabledLabels to NmsProcessor**

```java
// In detect() method, change nmsProcessor.suppress call:
ArrayList<Recognition> filtered = nmsProcessor.suppress(
    allRecognitions, currentConfig.numClasses, 
    IOU_CLASS_DUPLICATED_THRESHOLD, enabledLabels);
```

- [ ] **Step 3: Update NmsProcessor.suppress signature and implementation**

```java
// In NmsProcessor.java:
public ArrayList<Recognition> suppress(ArrayList<Recognition> all, int numClasses,
                                       float iouDupThreshold, java.util.Set<Integer> enabledLabels) {
    // In filterByClass, add check:
    // if (enabledLabels != null && !enabledLabels.contains(classId)) continue;
    
    ArrayList<Recognition> nmsResult = nms(allRecognitions, numClasses, enabledLabels);
    return nmsAllClass(nmsResult, iouDupThreshold);
}

// Update nms() to accept enabledLabels:
protected ArrayList<Recognition> nms(ArrayList<Recognition> all, int numClasses, 
                                      java.util.Set<Integer> enabledLabels) {
    for (int i = 0; i < numClasses; i++) {
        if (enabledLabels != null && !enabledLabels.contains(i)) continue;
        // ... rest of NMS logic
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: add enabledLabels filter to detector and NMS"
```

---

## Task 3: Add enabledLabels to FullImageAnalyse

**Files:**
- Modify: `app/src/main/java/com/crowdhuman/detector/analysis/FullImageAnalyse.java`

- [ ] **Step 1: Add enabledLabels field and setter**

```java
// In FullImageAnalyse.java:
private java.util.Set<Integer> enabledLabels;

public void setEnabledLabels(java.util.Set<Integer> labels) {
    this.enabledLabels = labels;
}
```

- [ ] **Step 2: Pass enabledLabels to detector before detect()**

```java
// In analyze(), before detector.detect():
if (enabledLabels != null) {
    detector.setEnabledLabels(enabledLabels);
}
ArrayList<Recognition> recognitions = detector.detect(modelInputBitmap);
```

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: FullImageAnalyse accepts enabledLabels filter"
```

---

## Task 4: Add filter button and UI

**Files:**
- Create: `app/src/main/res/drawable/ic_filter.xml`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/crowdhuman/detector/MainActivity.java`

- [ ] **Step 1: Create filter icon**

```xml
<!-- app/src/main/res/drawable/ic_filter.xml -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="#FFFFFF">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M10,18h4v-2h-4v2zM3,6v2h18V6H3zM6,13h12v-2H6v2z"/>
</vector>
```

- [ ] **Step 2: Add filter button to layout**

```xml
<!-- In activity_main.xml, top_toolbar LinearLayout, after rotate_button -->
<ImageView
    android:id="@+id/filter_button"
    android:layout_width="40dp"
    android:layout_height="40dp"
    android:padding="8dp"
    android:contentDescription="@string/filter_desc"
    android:src="@drawable/ic_filter"
    android:background="@drawable/bg_circle_button"
    android:layout_marginStart="8dp"/>
```

- [ ] **Step 3: Add string resource**

```xml
<string name="filter_desc">Filter classes</string>
```

- [ ] **Step 4: Add filter button to MainActivity**

```java
// In MainActivity.java:
private ImageView filterButton;
private java.util.Set<Integer> enabledLabels = new java.util.HashSet<>();

// In bindViews():
filterButton = findViewById(R.id.filter_button);

// In setupListeners():
filterButton.setOnClickListener(v -> showClassFilterDialog());

// Default: enable all labels (or just person)
private void initDefaultLabels() {
    // Enable all 80 COCO classes by default
    for (int i = 0; i < 80; i++) {
        enabledLabels.add(i);
    }
}
```

- [ ] **Step 5: Implement class filter dialog**

```java
private void showClassFilterDialog() {
    String[] labels = detector.getLabels(); // Need to add this method
    boolean[] checked = new boolean[labels.length];
    for (int i = 0; i < labels.length; i++) {
        checked[i] = enabledLabels.contains(i);
    }
    
    new androidx.appcompat.app.AlertDialog.Builder(this)
        .setTitle("Select Classes")
        .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
            if (isChecked) enabledLabels.add(which);
            else enabledLabels.remove(which);
        })
        .setPositiveButton("OK", (dialog, which) -> {
            if (currentAnalyser != null) {
                currentAnalyser.setEnabledLabels(new java.util.HashSet<>(enabledLabels));
            }
        })
        .setNegativeButton("Cancel", null)
        .show();
}
```

- [ ] **Step 6: Add getLabels() to Yolov5TFLiteDetector**

```java
public String[] getLabels() {
    if (associatedAxisLabels == null) return new String[0];
    return associatedAxisLabels.toArray(new String[0]);
}
```

- [ ] **Step 7: Pass enabledLabels when creating analyser**

```java
// In startCameraWithCurrentMode():
currentAnalyser = new FullImageAnalyse(this, cameraPreviewMatch, effectiveRotation,
        detector, cameraProcess.isFrontCamera());
currentAnalyser.setEnabledLabels(new java.util.HashSet<>(enabledLabels));
```

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: add class filter dialog with multi-select UI"
```

---

## Task 5: Update CI workflow

**Files:**
- Modify: `.github/workflows/build-apk.yml`

- [ ] **Step 1: Remove crowdhuman download step**

The workflow should only verify that the 4 base model files exist in assets. Remove any crowdhuman-specific download or mention.

- [ ] **Step 2: Commit**

```bash
git add -A
git commit -m "ci: remove crowdhuman from build workflow"
```

---

## Task 6: Push and verify CI

- [ ] **Step 1: Push all commits**

```bash
git push origin master
```

- [ ] **Step 2: Wait for CI and verify success**

Check: `https://github.com/poiuy105/yolov5-tflite-android/actions`

---

## Self-Review Checklist

- [ ] **Spec coverage:** All requirements addressed (remove crowdhuman, multi-class detection, UI filter)
- [ ] **Placeholder scan:** No TBD/TODO in plan
- [ ] **Type consistency:** `enabledLabels` is `Set<Integer>` throughout
- [ ] **API compatibility:** Uses `androidx.appcompat.app.AlertDialog` (already in dependencies)
- [ ] **Default behavior:** All classes enabled by default; user can deselect
