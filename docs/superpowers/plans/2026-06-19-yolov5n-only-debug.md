# YOLOv5n Only Debug Build Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a debug APK containing only the yolov5n model, download it locally, and upload to Baidu NetDisk.

**Architecture:** Modify the CI workflow to only verify yolov5n model exists, skip release build, skip GitHub release creation, and only upload debug artifact. Then download the artifact via GitHub API and upload to Baidu NetDisk using bypy.

**Tech Stack:** GitHub Actions, Gradle, PowerShell, bypy (Python CLI)

---

## File Structure

| File | Responsibility |
|------|---------------|
| `.github/workflows/build-apk.yml` | Modified CI: only yolov5n verification, debug only, no release |
| `strings.xml` | Model array: only yolov5n |
| `Yolov5TFLiteDetector.java` | MODEL_CONFIGS: only yolov5n |
| Local download script | Download artifact from GitHub Actions |
| bypy upload | Upload APK to Baidu NetDisk |

---

## Task 1: Modify CI workflow for yolov5n-only debug build

**Files:**
- Modify: `.github/workflows/build-apk.yml`

- [ ] **Step 1: Update workflow to only verify yolov5n model**

```yaml
name: Build Android APK - YOLOv5n Debug Only

on:
  push:
    branches: [master]
  workflow_dispatch:

permissions:
  contents: read
  actions: read

jobs:
  build-apk:
    runs-on: ubuntu-latest
    timeout-minutes: 30

    steps:
      - name: Checkout repository
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Setup Android SDK
        uses: android-actions/setup-android@v3

      - name: Verify yolov5n model exists in assets
        run: |
          echo "=== Assets files ==="
          ls -lh app/src/main/assets/*.tflite
          echo "=== Checking yolov5n model ==="
          test -f app/src/main/assets/yolov5n-fp16-320.tflite && echo "OK: yolov5n model exists" || { echo "FAIL: yolov5n model missing!"; exit 1; }

      - name: Grant execute permission for gradlew
        run: chmod +x gradlew

      - name: Build Debug APK
        run: ./gradlew assembleDebug

      - name: List build outputs
        run: |
          echo "=== Debug APK ==="
          ls -lh app/build/outputs/apk/debug/

      - name: Upload Debug APK artifact
        uses: actions/upload-artifact@v4
        with:
          name: yolov5n-debug
          path: app/build/outputs/apk/debug/*.apk
          retention-days: 7
```

Key changes:
- Remove release build step
- Remove GitHub release creation step
- Only verify yolov5n model
- Artifact name: `yolov5n-debug`
- Reduced retention to 7 days

- [ ] **Step 2: Commit**

```bash
git add -A
git commit -m "ci: yolov5n-only debug build, skip release"
```

---

## Task 2: Update app to only support yolov5n model

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/crowdhuman/detector/detector/Yolov5TFLiteDetector.java`

- [ ] **Step 1: Update model array to only yolov5n**

```xml
<string-array name="model">
    <item>yolov5n</item>
</string-array>
```

- [ ] **Step 2: Update MODEL_CONFIGS to only yolov5n**

```java
static {
    MODEL_CONFIGS.put("yolov5n", new ModelConfig("yolov5n", "yolov5n-fp16-320.tflite", "coco_label.txt", 80, false));
}
```

- [ ] **Step 3: Update DEFAULT_MODEL**

```java
private static final String DEFAULT_MODEL = "yolov5n";
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: yolov5n-only model support"
```

---

## Task 3: Push and trigger CI

- [ ] **Step 1: Push to master**

```bash
git push origin master
```

- [ ] **Step 2: Wait for CI completion**

Monitor: `https://github.com/poiuy105/yolov5-tflite-android/actions`

---

## Task 4: Download debug APK artifact

**Files:**
- Local: PowerShell script

- [ ] **Step 1: Get latest run ID**

```powershell
$run = Invoke-RestMethod -Uri 'https://api.github.com/repos/poiuy105/yolov5-tflite-android/actions/runs?per_page=1' | Select-Object -ExpandProperty workflow_runs | Select-Object -First 1
$runId = $run.id
Write-Host "Run ID: $runId"
```

- [ ] **Step 2: Get artifact download URL**

```powershell
$artifacts = Invoke-RestMethod -Uri "https://api.github.com/repos/poiuy105/yolov5-tflite-android/actions/runs/$runId/artifacts"
$debugArtifact = $artifacts.artifacts | Where-Object { $_.name -eq "yolov5n-debug" }
$artifactId = $debugArtifact.id
Write-Host "Artifact ID: $artifactId"
```

- [ ] **Step 3: Download artifact zip**

```powershell
$headers = @{Authorization = "token $(gh auth token)"}
$outDir = "e:\Espidf\camera\yolov5n-debug"
New-Item -ItemType Directory -Force -Path $outDir
Invoke-RestMethod -Uri "https://api.github.com/repos/poiuy105/yolov5-tflite-android/actions/artifacts/$artifactId/zip" -Headers $headers -OutFile "$outDir\yolov5n-debug.zip"
```

- [ ] **Step 4: Extract APK**

```powershell
Expand-Archive -Path "$outDir\yolov5n-debug.zip" -DestinationPath $outDir -Force
Get-ChildItem $outDir -Recurse -Filter "*.apk"
```

---

## Task 5: Upload to Baidu NetDisk

- [ ] **Step 1: Ensure bypy is installed and authorized**

```powershell
python -m bypy info
```

If not authorized, follow the URL and paste the code.

- [ ] **Step 2: Upload APK**

```powershell
$apkFile = Get-ChildItem "e:\Espidf\camera\yolov5n-debug" -Recurse -Filter "*.apk" | Select-Object -First 1
python -m bypy upload "$($apkFile.FullName)" "/apps/bypy/yolov5n-debug.apk"
```

- [ ] **Step 3: Verify upload**

```powershell
python -m bypy list /apps/bypy/
```

---

## Self-Review Checklist

- [ ] **Spec coverage:** yolov5n-only, debug only, no release, download, upload
- [ ] **Placeholder scan:** No TBD/TODO
- [ ] **Type consistency:** Artifact name `yolov5n-debug` used consistently
- [ ] **CI permissions:** `contents: read` (no write needed without release)
