package com.orico.gestureassistant.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 防止录入控件再次混回主页的轻量结构契约。 */
class RecordingScreenContractTest {
    private val projectDir = File(System.getProperty("user.dir"))

    @Test
    fun `recording screen owns recording controls and is registered`() {
        val mainLayout = source("src/main/res/layout/activity_main.xml")
        val recordingLayout = source("src/main/res/layout/activity_recording.xml")
        val manifest = source("src/main/AndroidManifest.xml")

        assertFalse(mainLayout.contains("trajectoryRecordModeSwitch"))
        assertFalse(mainLayout.contains("recordingGestureNameInput"))
        assertFalse(mainLayout.contains("trajectoryHoldButton"))
        assertTrue(mainLayout.contains("openRecordingButton"))
        assertFalse(recordingLayout.contains("recordingGestureNameInput"))
        assertFalse(recordingLayout.contains("trajectoryHoldButton"))
        assertTrue(recordingLayout.contains("addCustomGestureButton"))
        assertTrue(recordingLayout.contains("trajectoryGestureList"))
        assertTrue(manifest.contains(".ui.RecordingActivity"))
        assertTrue(manifest.contains("android:exported=\"false\""))
    }

    @Test
    fun `recorded gestures appear before presets and guided recording uses a dialog`() {
        val recordingLayout = source("src/main/res/layout/activity_recording.xml")
        val recordingActivity = source("src/main/kotlin/com/orico/gestureassistant/ui/RecordingActivity.kt")

        val recordedIndex = recordingLayout.indexOf("android:text=\"已录手势\"")
        val presetsIndex = recordingLayout.indexOf("android:text=\"预设动作（引导录入）\"")
        val customIndex = recordingLayout.indexOf("android:text=\"自定义手势\"")

        assertNotEquals(-1, recordedIndex)
        assertNotEquals(-1, presetsIndex)
        assertNotEquals(-1, customIndex)
        assertTrue(recordedIndex < presetsIndex)
        assertTrue(presetsIndex < customIndex)
        assertFalse(recordingLayout.contains("guidedRecordingPanel"))
        assertTrue(recordingActivity.contains("AlertDialog.Builder(this)"))
        assertTrue(recordingActivity.contains("长按音量减键，照着上图画一次"))
        assertTrue(recordingActivity.contains("已录 \$count 条 · \${advice.clusterCount} 种握法"))
        assertTrue(recordingActivity.contains("setPath(preset.referenceStroke)"))
    }

    @Test
    fun `custom gesture entry opens a styled recording dialog with live progress`() {
        val recordingLayout = source("src/main/res/layout/activity_recording.xml")
        val recordingActivity = source("src/main/kotlin/com/orico/gestureassistant/ui/RecordingActivity.kt")

        assertTrue(recordingLayout.contains("android:id=\"@+id/addCustomGestureButton\""))
        assertTrue(recordingLayout.contains("android:text=\"+ 添加自定义手势\""))
        assertTrue(recordingLayout.contains("android:background=\"@drawable/btn_primary\""))
        assertTrue(recordingActivity.contains("给手势起个名，如 我的手势"))
        assertTrue(recordingActivity.contains("长按音量减键 或 按住下方按钮，在空中画一次"))
        assertTrue(recordingActivity.contains("text = \"按住绘制\""))
        assertTrue(recordingActivity.contains("val advice = EnrollmentGuide.advise(templates, latestThreshold)"))
        assertTrue(recordingActivity.contains("customConsistencyView?.text = advice.message"))
        assertTrue(recordingActivity.contains("text = \"完成/关闭\""))
    }

    @Test
    fun `air gesture tab lists recorded gestures with independent switches`() {
        val mainLayout = source("src/main/res/layout/activity_main.xml")
        val mainActivity = source("src/main/kotlin/com/orico/gestureassistant/ui/MainActivity.kt")
        val accessibilityService = source("src/main/kotlin/com/orico/gestureassistant/accessibility/GestureAccessibilityService.kt")

        assertTrue(mainLayout.contains("android:text=\"已录手势\""))
        assertTrue(mainLayout.contains("android:id=\"@+id/airGestureList\""))
        assertTrue(mainActivity.contains("renderAirGestureList"))
        assertTrue(mainActivity.contains("trajectoryStore.setEnabled(gesture.id, checked)"))
        assertTrue(accessibilityService.contains("latestGestures.filter { it.enabled }"))
    }

    private fun source(relativePath: String): String {
        val candidates = listOf(
            File(projectDir, relativePath),
            File(projectDir, "app/$relativePath"),
            File(projectDir.parentFile, "app/$relativePath"),
        )
        return candidates.first { it.isFile }.readText()
    }
}
