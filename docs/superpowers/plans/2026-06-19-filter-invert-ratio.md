# Filter Invert + 3:4 Aspect Ratio Fix Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add "Select All" / "Select None" / "Invert Selection" buttons to the class filter dialog, and fix the preview aspect ratio to 3:4 (portrait).

**Architecture:** The filter dialog gets three new action buttons. Preview uses `setTargetAspectRatio(AspectRatio.RATIO_4_3)` which in portrait orientation gives 3:4 (width:height = 3:4). ImageAnalysis keeps max resolution.

**Tech Stack:** Java, Android SDK, AlertDialog, CameraX

---

## File Structure

| File | Responsibility |
|------|---------------|
| `MainActivity.java` | Add select all/none/invert buttons to filter dialog |
| `CameraProcess.java` | Restore RATIO_4_3 for Preview (portrait = 3:4) |

---

## Task 1: Add select all / none / invert to filter dialog

**Files:**
- Modify: `app/src/main/java/com/crowdhuman/detector/MainActivity.java`

- [ ] **Step 1: Replace showClassFilterDialog with enhanced version**

```java
private void showClassFilterDialog() {
    String[] labels = detector.getLabels();
    if (labels.length == 0) {
        Toast.makeText(this, "Labels not loaded yet", Toast.LENGTH_SHORT).show();
        return;
    }
    boolean[] checked = new boolean[labels.length];
    for (int i = 0; i < labels.length; i++) {
        checked[i] = enabledLabels.contains(i);
    }

    AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Filter Classes")
            .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> {
                if (isChecked) {
                    enabledLabels.add(which);
                } else {
                    enabledLabels.remove(which);
                }
            })
            .setPositiveButton("OK", (d, which) -> {
                if (currentAnalyser != null) {
                    currentAnalyser.setEnabledLabels(new java.util.HashSet<>(enabledLabels));
                }
            })
            .setNegativeButton("Cancel", null)
            .create();

    dialog.setOnShowListener(d -> {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (currentAnalyser != null) {
                currentAnalyser.setEnabledLabels(new java.util.HashSet<>(enabledLabels));
            }
            dialog.dismiss();
        });
    });

    // Add custom buttons via a footer view
    android.widget.LinearLayout footer = new android.widget.LinearLayout(this);
    footer.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    footer.setGravity(android.view.Gravity.CENTER);
    footer.setPadding(16, 16, 16, 16);

    android.widget.Button btnAll = new android.widget.Button(this);
    btnAll.setText("All");
    btnAll.setOnClickListener(v -> {
        for (int i = 0; i < labels.length; i++) {
            enabledLabels.add(i);
            dialog.getListView().setItemChecked(i, true);
        }
    });

    android.widget.Button btnNone = new android.widget.Button(this);
    btnNone.setText("None");
    btnNone.setOnClickListener(v -> {
        enabledLabels.clear();
        for (int i = 0; i < labels.length; i++) {
            dialog.getListView().setItemChecked(i, false);
        }
    });

    android.widget.Button btnInvert = new android.widget.Button(this);
    btnInvert.setText("Invert");
    btnInvert.setOnClickListener(v -> {
        for (int i = 0; i < labels.length; i++) {
            boolean newState = !enabledLabels.contains(i);
            if (newState) {
                enabledLabels.add(i);
            } else {
                enabledLabels.remove(i);
            }
            dialog.getListView().setItemChecked(i, newState);
        }
    });

    footer.addView(btnAll);
    footer.addView(btnNone);
    footer.addView(btnInvert);

    dialog.getListView().addFooterView(footer);
    dialog.show();
}
```

- [ ] **Step 2: Commit**

```bash
git add -A
git commit -m "feat: add select all/none/invert buttons to class filter dialog"
```

---

## Task 2: Fix preview aspect ratio to 3:4 (portrait)

**Files:**
- Modify: `app/src/main/java/com/crowdhuman/detector/utils/CameraProcess.java`

- [ ] **Step 1: Restore RATIO_4_3 for Preview**

```java
// Preview: use 4:3 aspect ratio (in portrait this is 3:4 width:height)
Preview preview = new Preview.Builder()
        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
        .build();
```

- [ ] **Step 2: Commit**

```bash
git add -A
git commit -m "fix: restore Preview RATIO_4_3 for 3:4 portrait aspect ratio"
```

---

## Task 3: Push and verify CI

- [ ] **Step 1: Push**

```bash
git push origin master
```

- [ ] **Step 2: Wait for CI success**

---

## Self-Review Checklist

- [ ] **Spec coverage:** Filter invert, 3:4 ratio
- [ ] **Placeholder scan:** No TBD/TODO
- [ ] **Type consistency:** `enabledLabels` is `Set<Integer>` throughout
