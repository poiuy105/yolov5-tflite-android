# Edge-to-Edge Status Bar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Android Edge-to-Edge layout with proper WindowInsets handling so content extends behind the status bar while UI elements avoid overlap, achieving a polished commercial-grade appearance.

**Architecture:** Use `WindowInsetsCompat` to dynamically obtain status bar and navigation bar heights, then apply appropriate padding to the root layout and bottom control panel. Status bar icons will remain visible over the camera preview with proper contrast.

**Tech Stack:** Java, Android SDK, AndroidX Core (WindowInsetsCompat), ConstraintLayout

---

## File Structure

| File | Responsibility |
|------|---------------|
| `MainActivity.java` | Edge-to-Edge setup, WindowInsets listener, status bar icon color control |
| `activity_main.xml` | Root layout id for insets targeting |
| `themes.xml` | Status bar text color (light/dark icons) |

---

## Task 1: Setup Edge-to-Edge and WindowInsets handling

**Files:**
- Modify: `app/src/main/java/com/crowdhuman/detector/MainActivity.java`

- [ ] **Step 1: Replace onCreate window setup with Edge-to-Edge**

```java
@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    // Edge-to-Edge: content extends behind system bars
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        getWindow().setDecorFitsSystemWindows(false);
    } else {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }
    getWindow().setStatusBarColor(Color.TRANSPARENT);
    getWindow().setNavigationBarColor(Color.TRANSPARENT);

    // Apply WindowInsets to root layout
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(R.id.root_layout),
            (view, insets) -> {
                androidx.core.graphics.Insets statusBars = insets.getInsets(
                        androidx.core.view.WindowInsetsCompat.Type.statusBars());
                androidx.core.graphics.Insets navBars = insets.getInsets(
                        androidx.core.view.WindowInsetsCompat.Type.navigationBars());

                view.setPadding(statusBars.left, statusBars.top, statusBars.right, navBars.bottom);
                return insets;
            });

    bindViews();
    setupListeners();
    initDefaultLabels();
    initModel(DEFAULT_MODEL);
    requestCameraPermission();
}
```

- [ ] **Step 2: Commit**

```bash
git add -A
git commit -m "feat: implement Edge-to-Edge with WindowInsets handling"
```

---

## Task 2: Add root layout id to XML

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`

- [ ] **Step 1: Add id to root ConstraintLayout**

```xml
<!-- Change root element from: -->
<androidx.constraintlayout.widget.ConstraintLayout ...>

<!-- To: -->
<androidx.constraintlayout.widget.ConstraintLayout
    android:id="@+id/root_layout"
    ...>
```

- [ ] **Step 2: Commit**

```bash
git add -A
git commit -m "chore: add root_layout id for WindowInsets targeting"
```

---

## Task 3: Control status bar icon color

**Files:**
- Modify: `app/src/main/res/values/themes.xml`
- Modify: `app/src/main/java/com/crowdhuman/detector/MainActivity.java`

- [ ] **Step 1: Check and update themes.xml for light status bar icons**

```xml
<!-- In themes.xml, ensure the theme has: -->
<item name="android:windowLightStatusBar">false</item>
```

This makes status bar icons white (visible on dark camera preview).

- [ ] **Step 2: Add dynamic status bar icon color control in MainActivity**

```java
// Add method to MainActivity:
private void setStatusBarIconColor(boolean light) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
        View decorView = getWindow().getDecorView();
        int flags = decorView.getSystemUiVisibility();
        if (light) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        } else {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        decorView.setSystemUiVisibility(flags);
    }
}
```

Call `setStatusBarIconColor(false)` in `onCreate` to ensure white icons.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: control status bar icon color for visibility on camera preview"
```

---

## Task 4: Update landscape layout with same insets handling

**Files:**
- Modify: `app/src/main/res/layout-land/activity_main.xml`

- [ ] **Step 1: Add root_layout id to landscape root**

```xml
<androidx.constraintlayout.widget.ConstraintLayout
    android:id="@+id/root_layout"
    ...>
```

- [ ] **Step 2: Commit**

```bash
git add -A
git commit -m "chore: add root_layout id to landscape layout"
```

---

## Task 5: Push and verify CI

- [ ] **Step 1: Push**

```bash
git push origin master
```

- [ ] **Step 2: Verify CI**

Check: `https://github.com/poiuy105/yolov5-tflite-android/actions`

---

## Self-Review Checklist

- [ ] **Spec coverage:** Edge-to-Edge, insets padding, status bar icon color
- [ ] **Placeholder scan:** No TBD/TODO
- [ ] **Type consistency:** WindowInsetsCompat used consistently
- [ ] **API compatibility:** API 30+ uses setDecorFitsSystemWindows, older uses flags
