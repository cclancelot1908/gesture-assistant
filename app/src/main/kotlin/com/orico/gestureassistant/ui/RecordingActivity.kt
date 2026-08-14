package com.orico.gestureassistant.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.orico.gestureassistant.R
import com.orico.gestureassistant.config.AppSettings
import com.orico.gestureassistant.databinding.ActivityRecordingBinding
import com.orico.gestureassistant.feedback.NotificationFeedback
import com.orico.gestureassistant.recognizer.trajectory.EnrollmentGuide
import com.orico.gestureassistant.recognizer.trajectory.GestureConsistency
import com.orico.gestureassistant.smarthome.SmartDevice
import com.orico.gestureassistant.smarthome.SmartDeviceStore
import kotlinx.coroutines.flow.first
import com.orico.gestureassistant.recognizer.trajectory.GesturePreset
import com.orico.gestureassistant.recognizer.trajectory.GesturePresets
import com.orico.gestureassistant.recognizer.trajectory.ImuPoint
import com.orico.gestureassistant.recognizer.trajectory.TrajectoryGesture
import com.orico.gestureassistant.recognizer.trajectory.TrajectoryGestureStore
import com.orico.gestureassistant.recognizer.trajectory.TrajectoryRecognizer
import com.orico.gestureassistant.rules.ActionId
import com.orico.gestureassistant.sensor.RawImuSample
import com.orico.gestureassistant.sensor.SensorEngine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 独立的手势录入与管理页；页面可见期间始终处于录制态。 */
class RecordingActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRecordingBinding
    private val settings by lazy { AppSettings(applicationContext) }
    private val store by lazy { TrajectoryGestureStore(applicationContext) }
    private val smartDeviceStore by lazy { SmartDeviceStore(applicationContext) }
    private val feedback by lazy { NotificationFeedback(applicationContext) }
    private var sensor: SensorEngine? = null
    private val capturedPoints = mutableListOf<ImuPoint>()
    private val expandedIds = mutableSetOf<String>()
    private var latestGestures: List<TrajectoryGesture> = emptyList()
    // 录入引导按“当前识别阈值”聚握法簇，与识别口径一致，避免同握法被拆成多簇。
    private var latestThreshold: Double = EnrollmentGuide.DEFAULT_MERGE_DISTANCE
    private var guidedPresetId: String? = null
    private var guidedDialog: AlertDialog? = null
    private var guidedProgressView: TextView? = null
    private var guidedConsistencyView: TextView? = null
    private var customDialog: AlertDialog? = null
    private var customNameInput: EditText? = null
    private var customProgressView: TextView? = null
    private var customConsistencyView: TextView? = null
    private var recordingGestureName: String = DEFAULT_CUSTOM_NAME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            binding = ActivityRecordingBinding.inflate(layoutInflater)
            setContentView(binding.root)
            binding.backButton.setOnClickListener { runCatching { finish() } }
            loadRecordingName()
            binding.addCustomGestureButton.setOnClickListener {
                runCatching { showCustomRecordingDialog() }
                    .onFailure { showError("无法打开自定义手势录入", it) }
            }
            lifecycleScope.launch { store.gestures.collectLatest(::renderGestures) }
            lifecycleScope.launch {
                settings.values.collectLatest { value ->
                    latestThreshold = value.trajectoryThreshold.toDouble()
                    // 阈值变化后按新口径刷新引导提示。
                    refreshGuidedDialog(latestGestures)
                    refreshCustomDialog(latestGestures)
                }
            }
        }.onFailure {
            Toast.makeText(this, "手势录入页加载失败", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { runCatching { settings.setTrajectoryRecordMode(true) } }
    }

    override fun onPause() {
        stopCapture()
        exitGuidedRecording(updateStatus = false)
        exitCustomRecording(updateStatus = false)
        lifecycleScope.launch {
            runCatching {
                recordingGestureName.takeIf { it.isNotBlank() }?.let { settings.setRecordingGestureName(it) }
                settings.setTrajectoryRecordMode(false)
            }
        }
        super.onPause()
    }

    override fun onDestroy() {
        stopCapture()
        exitGuidedRecording(updateStatus = false)
        exitCustomRecording(updateStatus = false)
        super.onDestroy()
    }

    private fun loadRecordingName() {
        lifecycleScope.launch {
            runCatching { settings.values.first().recordingGestureName }
                .onSuccess { recordingGestureName = it.ifBlank { DEFAULT_CUSTOM_NAME } }
        }
    }

    @Suppress("ClickableViewAccessibility")
    private fun bindCaptureButton(button: Button) {
        button.setOnTouchListener { touchedButton, event ->
            runCatching {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { touchedButton.isPressed = true; startCapture() }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        touchedButton.isPressed = false
                        if (event.actionMasked == MotionEvent.ACTION_UP) touchedButton.performClick()
                        finishCapture()
                    }
                }
            }.onFailure { showError("绘制失败", it) }
            true
        }
        button.setOnClickListener { /* performClick 供无障碍语义使用。 */ }
    }

    private fun startCapture() {
        if (sensor != null) return
        capturedPoints.clear()
        val engine = runCatching {
            SensorEngine(applicationContext, { }, { sample -> capturedPoints += sample.toPoint() })
        }.getOrElse { showError("无法初始化运动传感器", it); return }
        sensor = engine
        if (!runCatching { engine.start() }.getOrDefault(false)) {
            stopCapture(); showError("无法启动加速度传感器", null); return
        }
        binding.trajectoryStatus.text = "正在采集六轴轨迹…松开结束"
    }

    private fun finishCapture() {
        if (sensor == null) return
        stopCapture()
        val points = capturedPoints.toList()
        if (points.size < TrajectoryRecognizer.MIN_SAMPLE_COUNT) {
            binding.trajectoryStatus.text = "采样太短(${points.size}点)，请按住并完整绘制"
            return
        }
        val presetName = guidedPresetId?.let { id -> GesturePresets.all.firstOrNull { it.id == id }?.displayName }
        saveTemplate(presetName ?: customNameInput?.text?.toString().orEmpty().ifBlank { recordingGestureName }, points)
    }

    private fun saveTemplate(rawName: String, points: List<ImuPoint>) {
        val name = rawName.trim()
        if (name.isEmpty()) { showError("手势名称不能为空", null); return }
        binding.trajectoryStatus.text = "正在保存“$name”…"
        recordingGestureName = name
        lifecycleScope.launch {
            runCatching {
                settings.setRecordingGestureName(name)
                store.addTemplate(name, points)
                store.gestures.first().firstOrNull { it.name.equals(name, true) }?.templates?.size ?: 0
            }.onSuccess { count -> binding.trajectoryStatus.text = "已录制“$name”（共${count}次）" }
                .onFailure { showError("保存失败", it) }
        }
    }

    private fun stopCapture() {
        val active = sensor
        sensor = null
        runCatching { active?.stop() }
    }

    private fun renderGestures(gestures: List<TrajectoryGesture>) {
        latestGestures = gestures
        renderPresets(gestures)
        refreshGuidedDialog(gestures)
        refreshCustomDialog(gestures)
        binding.trajectoryGestureList.removeAllViews()
        expandedIds.retainAll(gestures.map { it.id }.toSet())
        if (gestures.isEmpty()) {
            binding.trajectoryGestureList.addView(textView("尚未录制手势；录制成功后会在这里出现预览"))
        } else gestures.forEachIndexed { index, gesture ->
            if (index > 0) binding.trajectoryGestureList.addView(sectionDivider())
            binding.trajectoryGestureList.addView(createGestureRow(gesture))
        }
    }

    private fun renderPresets(gestures: List<TrajectoryGesture>) {
        binding.gesturePresetsList.removeAllViews()
        GesturePresets.all.forEachIndexed { index, preset ->
            val gesture = gestures.firstOrNull { it.name.equals(preset.displayName, true) }
            if (index > 0) binding.gesturePresetsList.addView(sectionDivider())
            binding.gesturePresetsList.addView(createPresetRow(preset, gesture))
        }
    }

    private fun createPresetRow(preset: GesturePreset, gesture: TrajectoryGesture?) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(12), 0, dp(12))
        addView(GesturePreviewView(context).apply {
            background = ContextCompat.getDrawable(context, R.drawable.card_bg)
            setPath(preset.referenceStroke)
        }, LinearLayout.LayoutParams(dp(76), dp(76)).apply { rightMargin = dp(12) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "${preset.displayName} · 已录${gesture?.templates?.size ?: 0}次"
                textSize = 16f
                setTextColor(color(R.color.text_primary)); setTypeface(typeface, Typeface.BOLD)
            })
            addView(textView(GestureConsistency.label(gesture?.templates.orEmpty())))
            if (gesture != null) {
                addView(
                    actionSpinner(gesture),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
                        topMargin = dp(5)
                    },
                )
            }
            addView(Button(context).apply {
                text = if (guidedPresetId == preset.id) "正在录入…" else "引导录入"
                isAllCaps = false; background = ContextCompat.getDrawable(context, R.drawable.btn_outline)
                setTextColor(color(R.color.primary)); setOnClickListener { enterGuidedRecording(preset) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(5) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun enterGuidedRecording(preset: GesturePreset) {
        runCatching {
            exitGuidedRecording(updateStatus = false)
            exitCustomRecording(updateStatus = false)
            guidedPresetId = preset.id
            recordingGestureName = preset.displayName
            lifecycleScope.launch { runCatching { settings.setRecordingGestureName(preset.displayName) } }
            showGuidedRecordingDialog(preset)
        }.onFailure {
            exitGuidedRecording(updateStatus = false)
            showError("无法开始引导录入", it)
        }
    }

    /** 关闭弹窗只退出预设引导态，页面整体录制态仍由 onResume/onPause 管理。 */
    private fun exitGuidedRecording(updateStatus: Boolean = true) {
        val dialog = guidedDialog
        guidedDialog = null
        guidedProgressView = null
        guidedConsistencyView = null
        guidedPresetId = null
        runCatching { if (dialog?.isShowing == true) dialog.dismiss() }
        if (updateStatus) runCatching {
            binding.trajectoryStatus.text = "可继续自定义录入，离开本页后自动恢复识别"
        }
    }

    private fun showGuidedRecordingDialog(preset: GesturePreset) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(20), dp(20), dp(18))
            background = ContextCompat.getDrawable(context, R.drawable.card_bg)
            addView(TextView(context).apply {
                text = "引导录入：${preset.displayName}"
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.text_primary))
            })
            addView(GesturePreviewView(context).apply {
                contentDescription = "${preset.displayName}参考图"
                background = ContextCompat.getDrawable(context, R.drawable.card_bg)
                setPath(preset.referenceStroke)
            }, LinearLayout.LayoutParams(dp(240), dp(240)).apply { topMargin = dp(14) })
            addView(TextView(context).apply {
                text = "长按音量减键，照着上图画一次"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.text_primary))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            addView(TextView(context).apply {
                // 识别取最近模板、不求平均：想支持多种握法就每种各录≥2条，触发时自动命中最近的那种。
                text = "小贴士：识别取最近的一条模板，不合并。想正着、倒着都能用？就每种握法各画 2 条以上，触发时会自动命中最近的那种。"
                textSize = 13f
                setTextColor(color(R.color.text_secondary))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(TextView(context).also { progress ->
                guidedProgressView = progress
                progress.textSize = 16f
                progress.setTextColor(color(R.color.primary))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(TextView(context).also { consistency ->
                guidedConsistencyView = consistency
                consistency.textSize = 14f
                consistency.setTextColor(color(R.color.text_secondary))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
            addView(Button(context).apply {
                text = "完成/关闭"
                isAllCaps = false
                background = ContextCompat.getDrawable(context, R.drawable.btn_primary)
                setTextColor(color(R.color.text_on_primary))
                setOnClickListener { runCatching { guidedDialog?.dismiss() }.onFailure { showError("无法关闭引导录入", it) } }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(16) })
        }
        val dialog = AlertDialog.Builder(this).setView(content).create()
        guidedDialog = dialog
        dialog.setOnDismissListener {
            runCatching {
                if (guidedDialog === dialog) {
                    guidedDialog = null
                    guidedProgressView = null
                    guidedConsistencyView = null
                    guidedPresetId = null
                    binding.trajectoryStatus.text = "可继续自定义录入，离开本页后自动恢复识别"
                    renderPresets(latestGestures)
                }
            }.onFailure { showError("退出引导录入失败", it) }
        }
        dialog.show()
        runCatching { dialog.window?.setBackgroundDrawableResource(android.R.color.transparent) }
        refreshGuidedDialog(latestGestures)
    }

    private fun refreshGuidedDialog(gestures: List<TrajectoryGesture>) {
        runCatching {
            val preset = guidedPresetId?.let { id -> GesturePresets.all.firstOrNull { it.id == id } }
                ?: return@runCatching
            if (guidedDialog?.isShowing != true) return@runCatching
            val templates = gestures.firstOrNull { it.name.equals(preset.displayName, true) }?.templates.orEmpty()
            val count = templates.size
            val advice = EnrollmentGuide.advise(templates, latestThreshold)
            guidedProgressView?.text = "已录 $count 条 · ${advice.clusterCount} 种握法"
            guidedConsistencyView?.text = advice.message
            if (advice.ready) binding.trajectoryStatus.text = "“${preset.displayName}”已可保存"
        }.onFailure { showError("引导录入进度刷新失败", it) }
    }

    /** 自定义录入复用预设引导的卡片层级，录制目标名同时供页面按钮与音量减键使用。 */
    private fun showCustomRecordingDialog() {
        exitGuidedRecording(updateStatus = false)
        exitCustomRecording(updateStatus = false)
        val nameInput = EditText(this).apply {
            hint = "给手势起个名，如 我的手势"
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            background = ContextCompat.getDrawable(context, R.drawable.spinner_bg)
            setText(recordingGestureName.ifBlank { DEFAULT_CUSTOM_NAME })
            setSelection(text.length)
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(color(R.color.text_primary))
            setHintTextColor(color(R.color.text_secondary))
        }
        customNameInput = nameInput
        updateCustomRecordingName(nameInput.text?.toString().orEmpty())

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(20), dp(20), dp(18))
            background = ContextCompat.getDrawable(context, R.drawable.card_bg)
            addView(TextView(context).apply {
                text = "添加自定义手势"
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.text_primary))
            })
            addView(nameInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(14) })
            addView(TextView(context).apply {
                text = "长按音量减键 或 按住下方按钮，在空中画一次"
                textSize = 15f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.text_primary))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
            addView(TextView(context).apply {
                // 与引导录入同款提示：识别取最近模板，支持多握法——每种握法各录≥2条即可。
                text = "小贴士：识别取最近的一条模板，不合并。想支持多种握法，就每种各画 2 条以上，触发时自动命中最近的那种。"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(color(R.color.text_secondary))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(Button(context).apply {
                text = "按住绘制"
                isAllCaps = false
                background = ContextCompat.getDrawable(context, R.drawable.btn_hold)
                setTextColor(color(R.color.primary))
                bindCaptureButton(this)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)).apply { topMargin = dp(12) })
            addView(TextView(context).also { progress ->
                customProgressView = progress
                progress.textSize = 16f
                progress.setTextColor(color(R.color.primary))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
            addView(TextView(context).also { consistency ->
                customConsistencyView = consistency
                consistency.textSize = 14f
                consistency.setTextColor(color(R.color.text_secondary))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
            addView(Button(context).apply {
                text = "完成/关闭"
                isAllCaps = false
                background = ContextCompat.getDrawable(context, R.drawable.btn_primary)
                setTextColor(color(R.color.text_on_primary))
                setOnClickListener { runCatching { customDialog?.dismiss() }.onFailure { showError("无法关闭自定义录入", it) } }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(16) })
        }

        nameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) {
                runCatching {
                    updateCustomRecordingName(value?.toString().orEmpty())
                    refreshCustomDialog(latestGestures)
                }.onFailure { showError("手势名称更新失败", it) }
            }
            override fun afterTextChanged(value: Editable?) = Unit
        })

        val dialog = AlertDialog.Builder(this).setView(content).create()
        customDialog = dialog
        dialog.setOnDismissListener {
            runCatching {
                if (customDialog === dialog) {
                    stopCapture()
                    customDialog = null
                    customNameInput = null
                    customProgressView = null
                    customConsistencyView = null
                    binding.trajectoryStatus.text = "可继续添加自定义手势，离开本页后自动恢复识别"
                }
            }.onFailure { showError("退出自定义录入失败", it) }
        }
        dialog.show()
        runCatching { dialog.window?.setBackgroundDrawableResource(android.R.color.transparent) }
        refreshCustomDialog(latestGestures)
    }

    private fun updateCustomRecordingName(rawName: String) {
        recordingGestureName = rawName.trim()
        recordingGestureName.takeIf { it.isNotBlank() }?.let { validName ->
            lifecycleScope.launch { runCatching { settings.setRecordingGestureName(validName) } }
        }
    }

    private fun refreshCustomDialog(gestures: List<TrajectoryGesture>) {
        runCatching {
            if (customDialog?.isShowing != true) return@runCatching
            val name = customNameInput?.text?.toString().orEmpty().trim()
            val templates = gestures.firstOrNull { it.name.equals(name, true) }?.templates.orEmpty()
            val count = templates.size
            val advice = EnrollmentGuide.advise(templates, latestThreshold)
            customProgressView?.text = "已录 $count 条 · ${advice.clusterCount} 种握法"
            customConsistencyView?.text = advice.message
        }.onFailure { showError("自定义录入进度刷新失败", it) }
    }

    private fun exitCustomRecording(updateStatus: Boolean = true) {
        stopCapture()
        val dialog = customDialog
        customDialog = null
        customNameInput = null
        customProgressView = null
        customConsistencyView = null
        runCatching { if (dialog?.isShowing == true) dialog.dismiss() }
        if (updateStatus) runCatching { binding.trajectoryStatus.text = "可继续添加自定义手势，离开本页后自动恢复识别" }
    }

    private fun createGestureRow(gesture: TrajectoryGesture) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(12), 0, dp(12))
        val previews = createPreviewSection(gesture).apply { visibility = if (gesture.id in expandedIds) View.VISIBLE else View.GONE }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "${gesture.name}\n${gesture.templates.size} 条模板"
                textSize = 16f
                setTextColor(color(R.color.text_primary)); setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(managementButton(if (previews.visibility == View.VISIBLE) "收起 ︿" else "展开 ﹀") {
                val expand = previews.visibility != View.VISIBLE
                previews.visibility = if (expand) View.VISIBLE else View.GONE
                (it as Button).text = if (expand) "收起 ︿" else "展开 ﹀"
                if (expand) expandedIds += gesture.id else expandedIds -= gesture.id
            }, LinearLayout.LayoutParams(dp(78), dp(40)))
        })
        addView(previews)
        addView(actionSpinner(gesture), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(managementButton(if (gesture.appPackage.isNullOrBlank()) "选择 App" else "App 已选择") { showAppPicker(gesture) }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(4) })
            addView(managementButton(if (gesture.webhookUrl.isNullOrBlank()) "设置 Webhook" else "Webhook 已设置") { showWebhookDialog(gesture) }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(4) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        addView(managementButton(smartDeviceButtonLabel(gesture)) { showSmartDevicePicker(gesture) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(8) })
        addView(quietButton("删除“${gesture.name}”") { confirmDelete(gesture) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(8) })
    }

    private fun createPreviewSection(gesture: TrajectoryGesture) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gesture.templates.forEachIndexed { index, template ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(GesturePreviewView(context).apply {
                        background = ContextCompat.getDrawable(context, R.drawable.card_bg)
                        setWaveform(template)
                    }, LinearLayout.LayoutParams(dp(84), dp(84)))
                    addView(quietButton("删除") {
                        lifecycleScope.launch { runCatching { store.deleteTemplate(gesture.id, index) }.onFailure { showError("模板删除失败", it) } }
                    }, LinearLayout.LayoutParams(dp(84), dp(34)))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
            }
        })
    }

    private fun actionSpinner(gesture: TrajectoryGesture) = Spinner(this).apply {
        val options = ActionOption.entries
        background = ContextCompat.getDrawable(context, R.drawable.spinner_bg)
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, options.map { it.label }).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        setSelection(options.indexOfFirst { it.id == gesture.action }.coerceAtLeast(0), false)
        onItemSelectedListener = spinnerListener { position ->
            if (options[position].id != gesture.action) lifecycleScope.launch { runCatching { store.setAction(gesture.id, options[position].id) }.onFailure { showError("动作绑定失败", it) } }
        }
    }

    private fun showAppPicker(gesture: TrajectoryGesture) {
        val apps = runCatching {
            packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }.distinctBy { it.second }.sortedBy { it.first.lowercase() }
        }.getOrDefault(emptyList())
        if (apps.isEmpty()) { showError("未找到可启动的 App", null); return }
        runCatching { AlertDialog.Builder(this).setTitle("选择要打开的 App").setItems(apps.map { it.first }.toTypedArray()) { _, index ->
            lifecycleScope.launch { runCatching { store.setAppPackage(gesture.id, apps[index].second); store.setAction(gesture.id, ActionId.LAUNCH_APP) }.onFailure { showError("App 绑定失败", it) } }
        }.setNegativeButton("取消", null).show() }.onFailure { showError("无法打开 App 选择器", it) }
    }

    private fun showWebhookDialog(gesture: TrajectoryGesture) {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; setText(gesture.webhookUrl.orEmpty()); setSelection(text.length) }
        runCatching { AlertDialog.Builder(this).setTitle("设置 webhook URL").setView(input).setPositiveButton("保存") { _, _ ->
            lifecycleScope.launch { runCatching { store.setWebhookUrl(gesture.id, input.text.toString().trim()); store.setAction(gesture.id, ActionId.HTTP_WEBHOOK) }.onFailure { showError("Webhook URL 保存失败", it) } }
        }.setNegativeButton("取消", null).show() }.onFailure { showError("无法打开 webhook 设置", it) }
    }

    private fun smartDeviceButtonLabel(gesture: TrajectoryGesture): String {
        if (gesture.action != ActionId.SMART_DEVICE || gesture.smartDeviceId.isNullOrBlank()) return "🏠 绑定智能家居"
        val op = when (gesture.smartOp) { "on" -> "开"; "off" -> "关"; else -> "切换" }
        return "🏠 智能家居已绑定 · $op（点此修改）"
    }

    /** 两步选择：先选设备，再选开/关/切换；均从本地设备库读取，绑定后走 App 内置 miIO 局域网直控。 */
    private fun showSmartDevicePicker(gesture: TrajectoryGesture) {
        lifecycleScope.launch {
            val devices = runCatching { smartDeviceStore.devices.first() }.getOrDefault(emptyList())
            if (devices.isEmpty()) {
                showError("还没有导入智能家居设备，请先在主页“智能家居设备”里导入", null)
                return@launch
            }
            val names = devices.map { "${it.name}（${it.ip}）" }.toTypedArray()
            runCatching {
                AlertDialog.Builder(this@RecordingActivity)
                    .setTitle("选择要控制的设备")
                    .setItems(names) { _, index -> chooseSmartOp(gesture, devices[index]) }
                    .setNegativeButton("取消", null)
                    .show()
            }.onFailure { showError("无法打开设备选择器", it) }
        }
    }

    private fun chooseSmartOp(gesture: TrajectoryGesture, device: SmartDevice) {
        val ops = listOf("切换" to "toggle", "打开" to "on", "关闭" to "off")
        runCatching {
            AlertDialog.Builder(this)
                .setTitle("“${device.name}” 执行什么动作")
                .setItems(ops.map { it.first }.toTypedArray()) { _, index ->
                    lifecycleScope.launch {
                        runCatching { store.setSmartDevice(gesture.id, device.id, ops[index].second) }
                            .onFailure { showError("绑定智能家居失败", it) }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }.onFailure { showError("无法选择设备动作", it) }
    }

    private fun confirmDelete(gesture: TrajectoryGesture) {
        runCatching { AlertDialog.Builder(this).setMessage("确定删除“${gesture.name}”及其全部模板？").setPositiveButton("删除") { _, _ ->
            lifecycleScope.launch { runCatching { store.delete(gesture.id) }.onFailure { showError("删除失败", it) } }
        }.setNegativeButton("取消", null).show() }.onFailure { showError("无法打开删除对话框", it) }
    }

    private fun managementButton(label: String, onClick: (View) -> Unit) = Button(this).apply {
        text = label; textSize = 12f; isAllCaps = false
        setTextColor(color(R.color.text_primary)); background = ContextCompat.getDrawable(context, R.drawable.btn_outline)
        setOnClickListener { view -> runCatching { onClick(view) }.onFailure { showError("操作失败", it) } }
    }

    /** 删除类操作保持低调，避免与红色主操作争抢视觉层级。 */
    private fun quietButton(label: String, onClick: (View) -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        setTextColor(color(R.color.text_secondary))
        background = null
        setOnClickListener { view -> runCatching { onClick(view) }.onFailure { showError("操作失败", it) } }
    }

    /** iOS grouped 列表的内缩细分隔线。 */
    private fun sectionDivider() = View(this).apply {
        setBackgroundColor(color(R.color.divider))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpHairline()).apply {
            marginStart = dp(16)
        }
    }

    private fun textView(value: String) = TextView(this).apply { text = value; textSize = 13f; setTextColor(color(R.color.text_secondary)); setPadding(dp(4), dp(6), dp(4), dp(6)) }
    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun dpHairline() = (0.5f * resources.displayMetrics.density).toInt().coerceAtLeast(1)
    private fun RawImuSample.toPoint() = ImuPoint(worldE.toDouble(), worldN.toDouble(), worldU.toDouble(), gx.toDouble(), gy.toDouble(), gz.toDouble())
    private fun showError(prefix: String, error: Throwable?) { val message = error?.message?.takeIf { it.isNotBlank() }?.let { "$prefix：$it" } ?: prefix; runCatching { binding.trajectoryStatus.text = message }; runCatching { feedback.showAction(message, false) } }
    private fun spinnerListener(onSelected: (Int) -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = onSelected(position)
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }

    private enum class ActionOption(val id: ActionId, val label: String) {
        FLASHLIGHT(ActionId.TOGGLE_FLASHLIGHT, "切换手电筒"), MEDIA(ActionId.MEDIA_PLAY_PAUSE, "媒体播放 / 暂停"),
        LAUNCH_APP(ActionId.LAUNCH_APP, "打开指定 App"), WEBHOOK(ActionId.HTTP_WEBHOOK, "HTTP Webhook"),
        SCREENSHOT(ActionId.TAKE_SCREENSHOT, "截图"), LOCK(ActionId.LOCK_SCREEN, "锁定屏幕"),
        NOTIFICATIONS(ActionId.OPEN_NOTIFICATIONS, "打开通知栏"), BACK(ActionId.GLOBAL_BACK, "系统返回"),
        HOME(ActionId.GLOBAL_HOME, "系统主页"), RECENTS(ActionId.GLOBAL_RECENTS, "最近任务"),
        SMART_DEVICE(ActionId.SMART_DEVICE, "智能家居设备"),
    }

    private companion object {
        const val RECOMMENDED_COUNT = 4
        const val DEFAULT_CUSTOM_NAME = "我的手势"
    }
}
