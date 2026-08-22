package com.orico.gestureassistant.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import com.orico.gestureassistant.accessibility.AccessibilityServiceState
import com.orico.gestureassistant.accessibility.GestureAccessibilityService
import com.orico.gestureassistant.R
import com.orico.gestureassistant.config.AppSettings
import com.orico.gestureassistant.databinding.ActivityMainBinding
import com.orico.gestureassistant.keepalive.GestureForegroundService
import com.orico.gestureassistant.recognizer.GestureLabel
import com.orico.gestureassistant.recognizer.trajectory.TrajectoryGesture
import com.orico.gestureassistant.recognizer.trajectory.TrajectoryGestureStore
import com.orico.gestureassistant.rules.ActionId
import com.orico.gestureassistant.smarthome.CloudDevice
import com.orico.gestureassistant.smarthome.CloudPrompts
import com.orico.gestureassistant.smarthome.CloudResult
import com.orico.gestureassistant.smarthome.MiioClient
import com.orico.gestureassistant.smarthome.SmartDevice
import com.orico.gestureassistant.smarthome.SmartDeviceController
import com.orico.gestureassistant.smarthome.SmartDeviceStore
import com.orico.gestureassistant.smarthome.XiaomiCloud
import com.orico.gestureassistant.smarthome.XiaomiSession
import com.orico.gestureassistant.smarthome.XiaomiSessionStore
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val settings by lazy { AppSettings(applicationContext) }
    private val trajectoryStore by lazy { TrajectoryGestureStore(applicationContext) }
    private val smartDeviceStore by lazy { SmartDeviceStore(applicationContext) }
    private val sessionStore by lazy { XiaomiSessionStore(applicationContext) }
    private val smartDeviceController = SmartDeviceController()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val tabOvershootInterpolator = OvershootInterpolator(2f)
    private val iconOvershootInterpolator = OvershootInterpolator(3f)
    private val tabDecelerateInterpolator = DecelerateInterpolator()
    private var currentTabIndex = -1
    private var onboardingDialogShown = false
    private val serviceSwitchListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        if (checked) GestureForegroundService.start(this) else GestureForegroundService.stop(this)
        // 前台服务启停为异步过程，稍后以真实 running 状态回填总开关与子开关。
        updateSubSwitchesEnabled(GestureForegroundService.running)
        binding.root.postDelayed({ updateServiceStatus() }, 300L)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        bindTabNavigation()
        binding.serviceSwitch.setOnCheckedChangeListener(serviceSwitchListener)
        binding.notificationPermissionButton.setOnClickListener { requestNotificationPermission() }
        binding.batteryButton.setOnClickListener { OemSettingsNavigator.requestIgnoreBatteryOptimizations(this) }
        binding.autoStartButton.setOnClickListener { OemSettingsNavigator.openAutoStart(this) }
        bindSettings()
        bindRuleSelectors()
        bindTrajectorySummary()
        bindAccessibilityControls()
        bindSmartDevices()
        binding.aboutAppButton.setOnClickListener { showAboutDialog(firstLaunch = false) }
        lifecycleScope.launch {
            if (!settings.values.first().onboardingDone && !onboardingDialogShown) {
                showAboutDialog(firstLaunch = true)
            }
        }
    }

    /** 绑定底部标签栏；单个入口失败时不影响主界面的其他既有功能。 */
    private fun bindTabNavigation() {
        runCatching {
            binding.tabBtnBackTap.setOnClickListener { selectTab(0) }
            binding.tabBtnAir.setOnClickListener { selectTab(1) }
            binding.tabBtnHome.setOnClickListener { selectTab(2) }
            binding.tabBtnSettings.setOnClickListener { selectTab(3) }
            selectTab(0)
            binding.tabBarContainer.doOnLayout { bar ->
                val tabWidth = bar.width / 4
                binding.tabIndicator.layoutParams = binding.tabIndicator.layoutParams.apply { width = tabWidth - dp(14) }
                binding.tabIndicator.translationX =
                    (currentTabIndex.coerceAtLeast(0) * tabWidth + dp(7)).toFloat()
            }
        }
    }

    /** 切换内容页，并同步驱动指示块、页面、标题与图标的弹性动效。 */
    private fun selectTab(index: Int) {
        val pages = listOf(binding.tabBackTap, binding.tabAir, binding.tabHome, binding.tabSettings)
        if (index !in pages.indices || index == currentTabIndex) return

        val titles = listOf("背部轻点", "空中手势", "智能家居", "设置")
        val icons = listOf(
            binding.tabBtnBackTapIcon,
            binding.tabBtnAirIcon,
            binding.tabBtnHomeIcon,
            binding.tabBtnSettingsIcon,
        )
        val labels = listOf(
            binding.tabBtnBackTapLabel,
            binding.tabBtnAirLabel,
            binding.tabBtnHomeLabel,
            binding.tabBtnSettingsLabel,
        )
        val oldIndex = currentTabIndex
        currentTabIndex = index

        runCatching {
            val tabWidth = binding.tabBarContainer.width / 4
            if (tabWidth > 0) {
                binding.tabIndicator.layoutParams = binding.tabIndicator.layoutParams.apply { width = tabWidth - dp(14) }
                binding.tabIndicator.animate()
                    .translationX((index * tabWidth + dp(7)).toFloat())
                    .setInterpolator(tabOvershootInterpolator)
                    .setDuration(360L)
                    .start()
            }

            if (oldIndex in pages.indices) {
                val oldPage = pages[oldIndex]
                oldPage.animate().cancel()
                oldPage.animate()
                    .alpha(0f)
                    .scaleX(0.98f)
                    .scaleY(0.98f)
                    .setDuration(200L)
                    .withEndAction {
                        if (currentTabIndex != oldIndex) oldPage.visibility = View.GONE
                    }
                    .start()
            } else {
                pages.forEachIndexed { pageIndex, page ->
                    page.visibility = if (pageIndex == index) View.VISIBLE else View.GONE
                }
            }

            val newPage = pages[index]
            newPage.animate().cancel()
            newPage.visibility = View.VISIBLE
            newPage.alpha = if (oldIndex in pages.indices) 0f else 1f
            newPage.scaleX = if (oldIndex in pages.indices) 0.98f else 1f
            newPage.scaleY = if (oldIndex in pages.indices) 0.98f else 1f
            newPage.translationX = when {
                oldIndex !in pages.indices -> 0f
                index > oldIndex -> dp(40).toFloat()
                else -> -dp(40).toFloat()
            }
            if (oldIndex in pages.indices) {
                newPage.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationX(0f)
                    .setInterpolator(tabDecelerateInterpolator)
                    .setDuration(300L)
                    .start()
            }

            binding.appTitle.animate().cancel()
            binding.appTitle.text = titles[index]
            binding.appTitle.alpha = 0f
            binding.appTitle.translationY = dp(8).toFloat()
            binding.appTitle.animate()
                .alpha(1f)
                .translationY(0f)
                .setInterpolator(tabDecelerateInterpolator)
                .setDuration(180L)
                .start()

            icons.zip(labels).forEachIndexed { tabIndex, (icon, label) ->
                val color = ContextCompat.getColor(
                    this,
                    if (tabIndex == index) R.color.primary else R.color.text_secondary,
                )
                ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(color))
                label.setTextColor(color)
            }
            val selectedIcon = icons[index]
            selectedIcon.animate().cancel()
            selectedIcon.animate()
                .scaleX(1.18f)
                .scaleY(1.18f)
                .setDuration(150L)
                .withEndAction {
                    selectedIcon.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setInterpolator(iconOvershootInterpolator)
                        .setDuration(220L)
                        .start()
                }
                .start()

            // 切页时同步一次子开关置灰状态，避免总开关变化后切到其它 tab 未刷新导致状态不一致。
            updateSubSwitchesEnabled(GestureForegroundService.running)
        }.onFailure {
            pages.forEachIndexed { pageIndex, page ->
                page.animate().cancel()
                page.visibility = if (pageIndex == index) View.VISIBLE else View.GONE
                page.alpha = 1f
                page.scaleX = 1f
                page.scaleY = 1f
                page.translationX = 0f
            }
            binding.appTitle.text = titles[index]
            binding.appTitle.alpha = 1f
            binding.appTitle.translationY = 0f
            icons.zip(labels).forEachIndexed { tabIndex, (icon, label) ->
                val color = ContextCompat.getColor(
                    this,
                    if (tabIndex == index) R.color.primary else R.color.text_secondary,
                )
                ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(color))
                label.setTextColor(color)
                icon.scaleX = 1f
                icon.scaleY = 1f
            }
            val tabWidth = binding.tabBarContainer.width / 4
            if (tabWidth > 0) {
                binding.tabIndicator.layoutParams = binding.tabIndicator.layoutParams.apply { width = tabWidth - dp(14) }
                binding.tabIndicator.translationX = (index * tabWidth + dp(7)).toFloat()
            }
        }
        if (oldIndex in pages.indices) vibrateTabSwitch()
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        updateAccessibilityServiceStatus()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun bindSettings() {
        lifecycleScope.launch {
            val current = settings.values.first()
            binding.sensitivitySeekBar.progress = (current.sensitivity * 100).toInt()
            binding.sensitivityLabel.text = "灵敏度：${binding.sensitivitySeekBar.progress}%"
            binding.cooldownSeekBar.progress = ((current.cooldownMs - 300L) / 100L).toInt()
            binding.cooldownLabel.text = "冷却时间：${current.cooldownMs} ms"
            binding.trajectoryThresholdSeekBar.progress = ((current.trajectoryThreshold - 0.2f) * 100f).toInt()
            binding.trajectoryThresholdLabel.text = "识别灵敏度：${"%.2f".format(current.trajectoryThreshold)}（越高越容易识别，过高易误触）"
            binding.backTapSwitch.setOnCheckedChangeListener(null)
            binding.backTapSwitch.isChecked = current.backTapEnabled
            binding.backTapSwitch.setOnCheckedChangeListener { _, checked ->
                lifecycleScope.launch { runCatching { settings.setBackTapEnabled(checked) } }
            }
            binding.airGestureSwitch.setOnCheckedChangeListener(null)
            binding.airGestureSwitch.isChecked = current.airGestureEnabled
            binding.airGestureSwitch.setOnCheckedChangeListener { _, checked ->
                lifecycleScope.launch { runCatching { settings.setAirGestureEnabled(checked) } }
            }
            binding.silentKeepAliveSwitch.isChecked = current.silentKeepAliveEnabled
            binding.silentKeepAliveSwitch.setOnCheckedChangeListener { _, checked ->
                lifecycleScope.launch { runCatching { settings.setSilentKeepAliveEnabled(checked) } }
            }
            updateSubSwitchesEnabled(GestureForegroundService.running)
        }
        binding.sensitivitySeekBar.setOnSeekBarChangeListener(seekListener { progress, fromUser ->
            binding.sensitivityLabel.text = "灵敏度：$progress%"
            if (fromUser) lifecycleScope.launch { runCatching { settings.setSensitivity(progress / 100f) } }
        })
        binding.cooldownSeekBar.setOnSeekBarChangeListener(seekListener { progress, fromUser ->
            val cooldown = 300L + progress * 100L
            binding.cooldownLabel.text = "冷却时间：$cooldown ms"
            if (fromUser) lifecycleScope.launch { runCatching { settings.setCooldown(cooldown) } }
        })
        binding.trajectoryThresholdSeekBar.setOnSeekBarChangeListener(seekListener { progress, fromUser ->
            val threshold = 0.2f + progress / 100f
            binding.trajectoryThresholdLabel.text = "识别灵敏度：${"%.2f".format(threshold)}（越高越容易识别，过高易误触）"
            if (fromUser) lifecycleScope.launch { runCatching { settings.setTrajectoryThreshold(threshold) } }
        })
    }

    private fun bindAccessibilityControls() {
        binding.openAccessibilitySettingsButton.setOnClickListener { openAccessibilitySettingsSafely() }
        updateAccessibilityServiceStatus()
    }

    /** 首次引导和设置页共用同一份关于内容，避免两处说明漂移。 */
    private fun showAboutDialog(firstLaunch: Boolean) {
        if (firstLaunch && onboardingDialogShown) return
        if (firstLaunch) onboardingDialogShown = true

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        fun paragraph(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 15f
            setLineSpacing(0f, 1.2f)
            setPadding(0, dp(8), 0, dp(8))
        }

        content.addView(paragraph("用法要点\n① 打开右上角总开关；\n② 在“背部轻点 / 空中手势”里绑定动作、录入手势；\n③ 可选：在“智能家居”导入米家设备，或把手势绑定到设备。"))
        content.addView(paragraph("来源与更新"))
        val website = getString(R.string.about_website_url)
        content.addView(TextView(this).apply {
            text = website
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.primary))
            setPadding(0, dp(4), 0, dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(website))) }
                    .onFailure { showToast("无法打开官网") }
            }
        })
        content.addView(paragraph("隐私声明\n本应用不上传任何数据、无广告、无追踪、无后台统计；联网仅用于你自己局域网内的设备控制，以及你主动发起的小米账号登录取 token。纯个人兴趣项目，开源可查。"))

        val doNotShowAgain = CheckBox(this).apply {
            text = "不再显示"
            visibility = if (firstLaunch) View.VISIBLE else View.GONE
        }
        content.addView(doNotShowAgain)
        val scrollView = ScrollView(this).apply { addView(content) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("手势助手 · 关于")
            .setView(scrollView)
            .setPositiveButton("关闭", null)
            .create()
        dialog.setOnDismissListener {
            if (firstLaunch && doNotShowAgain.isChecked) {
                lifecycleScope.launch { runCatching { settings.setOnboardingDone(true) } }
            }
        }
        dialog.show()
    }

    private fun openAccessibilitySettingsSafely() {
        if (runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.isSuccess) return
        if (runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }.isFailure) {
            Toast.makeText(this, "无法打开系统设置，请手动进入无障碍设置", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateAccessibilityServiceStatus() {
        val enabled = runCatching {
            AccessibilityServiceState.isEnabled(
                Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                packageName,
                GestureAccessibilityService::class.java.name,
            )
        }.getOrDefault(false)
        binding.accessibilityServiceStatus.text = if (enabled) {
            "无障碍服务已启用，可在任意界面按住音量减键绘制"
        } else {
            "无障碍服务未启用，请点下方按钮并手动开启本服务"
        }
    }

    /** 主页只展示识别配置与管理入口。 */
    private fun bindTrajectorySummary() {
        binding.openRecordingButton.setOnClickListener {
            runCatching { startActivity(Intent(this, RecordingActivity::class.java)) }
                .onFailure { Toast.makeText(this, "无法打开手势录入页", Toast.LENGTH_SHORT).show() }
        }
        lifecycleScope.launch {
            trajectoryStore.gestures.collectLatest { gestures ->
                binding.trajectoryGestureSummary.text = "已配置 ${gestures.size} 个手势"
                renderAirGestureList(gestures)
            }
        }
    }

    /** 在空中手势页直接展示已录手势，每个开关只修改对应手势的识别资格。 */
    private fun renderAirGestureList(gestures: List<TrajectoryGesture>) {
        binding.airGestureList.removeAllViews()
        if (gestures.isEmpty()) {
            binding.airGestureList.addView(TextView(this).apply {
                text = "尚未录制手势（去下方‘录入/管理手势’）"
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                gravity = Gravity.CENTER
                minHeight = dp(52)
                setPadding(dp(16), 0, dp(16), 0)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            return
        }

        gestures.forEachIndexed { index, gesture ->
            binding.airGestureList.addView(createAirGestureRow(gesture))
            if (index < gestures.lastIndex) {
                binding.airGestureList.addView(View(this).apply {
                    setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.divider))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (0.5f * resources.displayMetrics.density).toInt().coerceAtLeast(1),
                    ).apply { marginStart = dp(16) }
                })
            }
        }
    }

    private fun createAirGestureRow(gesture: TrajectoryGesture) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        setPadding(dp(16), 0, dp(16), 0)

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = gesture.name
                textSize = 16f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            })
            addView(TextView(context).apply {
                text = actionSummary(gesture.action)
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        addView(Switch(context).apply {
            setOnCheckedChangeListener(null)
            isChecked = gesture.enabled
            setOnCheckedChangeListener { _, checked ->
                lifecycleScope.launch {
                    runCatching { trajectoryStore.setEnabled(gesture.id, checked) }
                        .onFailure { showToast("手势开关保存失败") }
                }
            }
        })
    }

    private fun actionSummary(action: ActionId): String = when (action) {
        ActionId.SMART_DEVICE -> "智能家居"
        ActionId.LAUNCH_APP -> "打开 App"
        ActionId.HTTP_WEBHOOK -> "Webhook"
        else -> ActionOption.entries.firstOrNull { it.id == action }?.label ?: action.name
    }

    private fun bindRuleSelectors() {
        bindActionRow(GestureLabel.BACK_DOUBLE, binding.backDoubleRow)
        bindActionRow(GestureLabel.BACK_TRIPLE, binding.backTripleRow)
        lifecycleScope.launch {
            settings.values.collectLatest { current ->
                refreshActionValue(
                    GestureLabel.BACK_DOUBLE,
                    current.bindings.backDoubleAction,
                    current.bindings.backDoublePackage,
                )
                refreshActionValue(
                    GestureLabel.BACK_TRIPLE,
                    current.bindings.backTripleAction,
                    current.bindings.backTriplePackage,
                )
            }
        }
    }

    /** iOS 风格动作行：先选动作，需要补充配置时继续进入对应选择器。 */
    private fun bindActionRow(label: GestureLabel, row: View) {
        row.setOnClickListener {
            val options = ActionOption.entries
            runCatching {
                AlertDialog.Builder(this)
                    .setTitle(if (label == GestureLabel.BACK_DOUBLE) "双击动作" else "三击动作")
                    .setItems(options.map { it.label }.toTypedArray()) { _, index ->
                        val selected = options[index]
                        lifecycleScope.launch {
                            runCatching { settings.setAction(label, selected.id) }
                                .onFailure { showToast("动作保存失败") }
                        }
                        when (selected.id) {
                            ActionId.LAUNCH_APP -> showInstalledAppPicker(label)
                            ActionId.HTTP_WEBHOOK -> showRuleWebhookDialog(label)
                            else -> Unit
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }.onFailure { showToast("无法打开动作选择器") }
        }
    }

    /** 根据持久化设置刷新行尾值；应用仍存在时优先显示它的可读名称。 */
    private fun refreshActionValue(label: GestureLabel, action: ActionId, packageOrUrl: String?) {
        val value = when (action) {
            ActionId.LAUNCH_APP -> packageOrUrl?.let { packageName ->
                runCatching {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
                }.getOrNull()
            } ?: "打开 App"
            ActionId.HTTP_WEBHOOK -> "Webhook"
            else -> ActionOption.entries.firstOrNull { it.id == action }?.label ?: action.name
        }
        (if (label == GestureLabel.BACK_DOUBLE) binding.backDoubleValue else binding.backTripleValue).text = value
    }

    private fun bindSmartDevices() {
        findViewById<Button>(R.id.cloudImportButton).setOnClickListener {
            runCatching { showCloudImportDialog() }
                .onFailure { showToast("无法打开小米云导入") }
        }
        binding.importSmartDevicesButton.setOnClickListener {
            runCatching { showSmartDeviceImportDialog() }
                .onFailure { showToast("无法打开设备导入") }
        }
        lifecycleScope.launch {
            smartDeviceStore.devices.collectLatest { devices ->
                runCatching { renderSmartDevices(devices) }
                    .onFailure { showToast("设备列表显示失败") }
            }
        }
    }

    /** 登录信息仅存在于当前对话框和协程局部变量，密码在请求结束后立即清空。 */
    private fun showCloudImportDialog() {
        // 先读「记住此设备」的会话，用于预填账号/服务器并支持免密登录。
        lifecycleScope.launch {
            val saved = runCatching { sessionStore.load() }.getOrNull()
            buildCloudImportDialog(saved)
        }
    }

    private fun buildCloudImportDialog(saved: XiaomiSession?) {
        val usernameInput = EditText(this).apply {
            hint = "小米账号（邮箱 / 手机号 / ID）"
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            setText(saved?.username.orEmpty())
        }
        val passwordInput = EditText(this).apply {
            hint = if (saved != null) "已记住本机，可留空直接登录" else "小米账号密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            maxLines = 1
        }
        val serverSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                XiaomiCloud.SERVERS.map { if (it == "cn") "cn（中国大陆）" else it },
            )
            setSelection(XiaomiCloud.SERVERS.indexOf(saved?.server).coerceAtLeast(0))
        }
        val rememberCheck = CheckBox(this).apply {
            text = "记住此设备（下次免验证 / 免登录）"
            isChecked = true
        }
        // 处理中进度指示：转圈 + 状态文字，默认隐藏，登录/导入期间显示。
        val progressText = TextView(this).apply {
            text = "正在登录…"
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        }
        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, dp(14), 0, dp(4))
            addView(android.widget.ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleSmall),
                LinearLayout.LayoutParams(dp(26), dp(26)))
            addView(progressText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
            addView(usernameInput)
            addView(passwordInput)
            addView(TextView(context).apply { text = "服务器"; setPadding(0, dp(10), 0, 0) })
            addView(serverSpinner)
            addView(rememberCheck, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            addView(progressRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("从小米云一键导入")
            .setMessage("登录后会拉取家庭设备与 token，并在当前局域网探测开关协议。密码不会保存。")
            .setView(content)
            .setPositiveButton("登录并导入", null)
            .setNegativeButton("取消", null)
        if (saved != null) {
            builder.setNeutralButton("清除记住的登录", null)
        }
        builder.create().also { dialog ->
                dialog.setOnShowListener {
                    if (saved != null) {
                        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                            lifecycleScope.launch {
                                runCatching { sessionStore.clear() }
                                showToast("已清除记住的登录")
                                dialog.dismiss()
                            }
                        }
                    }
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val username = usernameInput.text.toString().trim()
                        var password = passwordInput.text.toString()
                        if (username.isBlank()) {
                            showToast("请输入小米账号")
                            return@setOnClickListener
                        }
                        // 无已记住会话时必须输密码；有会话则可留空走免密快路径。
                        if (saved == null && password.isBlank()) {
                            showToast("请输入密码")
                            return@setOnClickListener
                        }
                        setCloudLoginEnabled(dialog, usernameInput, passwordInput, serverSpinner, false)
                        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        val positiveLabel = positiveButton.text
                        positiveButton.text = "登录中…"
                        progressText.text = "正在登录…请稍候（可能需十几秒）"
                        progressRow.visibility = View.VISIBLE
                        lifecycleScope.launch {
                            try {
                                val server = XiaomiCloud.SERVERS[serverSpinner.selectedItemPosition]
                                val remember = rememberCheck.isChecked
                                // 45 秒兜底：网络不稳时也不会一直卡在灰按钮上，超时即给明确提示。
                                val result = withTimeoutOrNull(45_000L) {
                                    XiaomiCloud(saved).importDevices(
                                        username, password, server, activityCloudPrompts(), remember,
                                        onSession = if (remember) { session -> runCatching { sessionStore.save(session) }.let {} } else null,
                                    )
                                } ?: CloudResult.Err("登录 / 导入超时（45 秒）。请确认手机 WiFi 正常后重试。")
                                when (result) {
                                    is CloudResult.Err -> showCloudError(result.message)
                                    is CloudResult.Ok -> {
                                        runCatching {
                                            if (remember) sessionStore.save(result.session) else sessionStore.clear()
                                        }
                                        progressText.text = "正在局域网探测设备协议…"
                                        val summary = importCloudDevices(result.devices)
                                        dialog.dismiss()
                                        AlertDialog.Builder(this@MainActivity)
                                            .setTitle("小米云导入完成")
                                            .setMessage("成功 ${summary.imported} 台，跳过 ${summary.skipped} 台，${summary.needsReview} 台需核对开关。")
                                            .setPositiveButton("知道了", null)
                                            .show()
                                    }
                                }
                            } catch (error: Exception) {
                                showCloudError(error.message.orEmpty().ifBlank { "网络请求异常" })
                            } finally {
                                password = ""
                                passwordInput.text.clear()
                                positiveButton.text = positiveLabel
                                progressRow.visibility = View.GONE
                                if (dialog.isShowing) setCloudLoginEnabled(dialog, usernameInput, passwordInput, serverSpinner, true)
                            }
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun setCloudLoginEnabled(
        dialog: AlertDialog,
        username: EditText,
        password: EditText,
        server: Spinner,
        enabled: Boolean,
    ) {
        username.isEnabled = enabled
        password.isEnabled = enabled
        server.isEnabled = enabled
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = enabled
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = enabled
    }

    private fun activityCloudPrompts(): CloudPrompts = object : CloudPrompts {
        override suspend fun onCaptcha(imageBytes: ByteArray, refresh: suspend () -> ByteArray?): String? =
            withContext(Dispatchers.Main) { suspendCaptchaPrompt(imageBytes, refresh) }

        override suspend fun on2faCode(title: String, info: String): String? = withContext(Dispatchers.Main) {
            suspendTextPrompt(title, message = info)
        }
    }

    /** 图片验证码专用对话框：带"刷新验证码"按钮，点一次就在同一登录会话内换一张新图。 */
    private suspend fun suspendCaptchaPrompt(initial: ByteArray, refresh: suspend () -> ByteArray?): String? =
        suspendCancellableCoroutine { continuation ->
            val input = EditText(this).apply {
                hint = "图片验证码（区分大小写）"
                inputType = InputType.TYPE_CLASS_TEXT
                maxLines = 1
            }
            val imageView = ImageView(this).apply {
                adjustViewBounds = true
                BitmapFactory.decodeByteArray(initial, 0, initial.size)?.let { setImageBitmap(it) }
            }
            val refreshButton = Button(this).apply {
                text = "刷新验证码"
                isAllCaps = false
                setOnClickListener {
                    isEnabled = false
                    lifecycleScope.launch {
                        val bytes = runCatching { withContext(Dispatchers.IO) { refresh() } }.getOrNull()
                        if (bytes != null) {
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { imageView.setImageBitmap(it) }
                        } else {
                            showToast("刷新失败，请重试")
                        }
                        isEnabled = true
                    }
                }
            }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(imageView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)))
                addView(refreshButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
                addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            }
            val dialog = AlertDialog.Builder(this)
                .setTitle("请输入图片验证码")
                .setView(content)
                .setPositiveButton("确定", null)
                .setNegativeButton("取消", null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = input.text.toString().trim()
                    if (value.isNotBlank() && continuation.isActive) {
                        continuation.resume(value)
                        dialog.dismiss()
                    }
                }
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                    if (continuation.isActive) continuation.resume(null)
                    dialog.dismiss()
                }
            }
            dialog.setOnCancelListener { if (continuation.isActive) continuation.resume(null) }
            continuation.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
        }

    private suspend fun suspendTextPrompt(title: String, imageBytes: ByteArray? = null, message: String? = null): String? =
        suspendCancellableCoroutine { continuation ->
            val input = EditText(this).apply {
                hint = "验证码"
                inputType = InputType.TYPE_CLASS_TEXT
                maxLines = 1
            }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), 0)
                message?.takeIf { it.isNotBlank() }?.let { text ->
                    addView(TextView(context).apply {
                        this.text = text
                        setPadding(0, 0, 0, dp(8))
                    })
                }
                imageBytes?.let { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bitmap ->
                        addView(ImageView(context).apply {
                            setImageBitmap(bitmap)
                            adjustViewBounds = true
                        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)))
                    }
                }
                addView(input)
            }
            val dialog = AlertDialog.Builder(this)
                .setTitle(title)
                .setView(content)
                .setPositiveButton("确定", null)
                .setNegativeButton("取消", null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = input.text.toString().trim()
                    if (value.isNotBlank() && continuation.isActive) {
                        continuation.resume(value)
                        dialog.dismiss()
                    }
                }
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                    if (continuation.isActive) continuation.resume(null)
                    dialog.dismiss()
                }
            }
            dialog.setOnCancelListener { if (continuation.isActive) continuation.resume(null) }
            continuation.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
        }

    private suspend fun importCloudDevices(cloudDevices: List<CloudDevice>): CloudImportSummary {
        var skipped = 0
        var needsReview = 0
        val imported = mutableListOf<SmartDevice>()
        cloudDevices.forEach { cloud ->
            val ip = cloud.ip
            val token = cloud.token
            if (ip == null || token == null || !isPrivateIpv4(ip) || !token.matches(Regex("[0-9a-fA-F]{32}"))) {
                skipped++
                return@forEach
            }
            val detected = detectCloudProtocol(ip, token, cloud.model)
            if (detected.needsReview) needsReview++
            imported += SmartDevice(
                id = UUID.nameUUIDFromBytes("${cloud.name}|$ip".toByteArray(StandardCharsets.UTF_8)).toString(),
                name = cloud.name,
                ip = ip,
                token = token.lowercase(),
                protocol = detected.protocol,
                siid = detected.siid,
                piid = detected.piid,
            )
        }
        if (imported.isNotEmpty()) {
            smartDeviceStore.importJson(com.orico.gestureassistant.smarthome.SmartDeviceJson.encode(imported))
        }
        return CloudImportSummary(imported.size, skipped, needsReview)
    }

    private suspend fun detectCloudProtocol(ip: String, token: String, model: String): ProtocolDetection {
        val client = MiioClient(ip, token)
        val legacy = client.getProp("power").optJSONArray("result")?.optString(0)?.lowercase()
        if (legacy == "on" || legacy == "off") {
            return ProtocolDetection(SmartDevice.Protocol.MIIO_POWER, null, null, false)
        }
        listOf(2 to 1, 2 to 2, 3 to 1).forEach { (siid, piid) ->
            val property = client.getProperties(siid, piid).optJSONArray("result")?.optJSONObject(0)
            if (property?.optInt("code", -1) == 0 && property.opt("value") is Boolean) {
                return ProtocolDetection(SmartDevice.Protocol.MIOT, siid, piid, false)
            }
        }
        return if (model.startsWith("yeelink.")) {
            ProtocolDetection(SmartDevice.Protocol.MIIO_POWER, null, null, true)
        } else {
            ProtocolDetection(SmartDevice.Protocol.MIOT, 2, 1, true)
        }
    }

    private fun isPrivateIpv4(ip: String): Boolean {
        val parts = ip.split('.').mapNotNull { it.toIntOrNull()?.takeIf { value -> value in 0..255 } }
        return parts.size == 4 && (parts[0] == 10 ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 172 && parts[1] in 16..31))
    }

    private data class ProtocolDetection(
        val protocol: SmartDevice.Protocol,
        val siid: Int?,
        val piid: Int?,
        val needsReview: Boolean,
    )

    private data class CloudImportSummary(val imported: Int, val skipped: Int, val needsReview: Int)

    private fun showSmartDeviceImportDialog() {
        val input = EditText(this).apply {
            hint = """[{"name":"台灯","ip":"192.168.1.20","token":"32位hex","proto":"miio_power"}]"""
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 7
            maxLines = 14
            setHorizontallyScrolling(false)
        }
        AlertDialog.Builder(this)
            .setTitle("导入智能家居设备")
            .setMessage("粘贴 JSON 数组。MIoT 设备还需 siid 和 piid；token 仅保存在本机。")
            .setView(input)
            .setPositiveButton("导入", null)
            .setNegativeButton("取消", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        lifecycleScope.launch {
                            val result = runCatching { smartDeviceStore.importJson(input.text.toString()) }
                            result.onSuccess { imported ->
                                showToast("已导入 ${imported.size} 个设备")
                                dialog.dismiss()
                            }.onFailure { error ->
                                val detail = error.message?.take(140).orEmpty().ifBlank { "JSON 格式错误" }
                                showToast("导入失败：$detail", Toast.LENGTH_LONG)
                            }
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun renderSmartDevices(devices: List<SmartDevice>) {
        binding.smartDeviceList.removeAllViews()
        binding.smartDeviceEmptyText.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
        devices.forEach { device -> binding.smartDeviceList.addView(createSmartDeviceRow(device)) }
    }

    private fun createSmartDeviceRow(device: SmartDevice): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(context, R.drawable.card_bg)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        addView(TextView(context).apply {
            text = device.name
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, com.orico.gestureassistant.R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        addView(TextView(context).apply {
            text = "${device.ip} · ${device.protocol.wireName}"
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, com.orico.gestureassistant.R.color.text_secondary))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(deviceButton("开") { controlDevice(device) { smartDeviceController.turnOn(it) } })
            addView(deviceButton("关") { controlDevice(device) { smartDeviceController.turnOff(it) } })
            addView(deviceButton("切换") { controlDevice(device) { smartDeviceController.toggle(it) } })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(12) })
        addView(TextView(context).apply {
            text = "删除设备"
            isAllCaps = false
            textSize = 13f
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(context, android.R.color.transparent)
            setTextColor(ContextCompat.getColor(context, com.orico.gestureassistant.R.color.text_secondary))
            setOnClickListener { confirmRemoveSmartDevice(device) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)).apply { topMargin = dp(4) })
    }.also { row ->
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        }
    }

    private fun LinearLayout.deviceButton(label: String, onClick: () -> Unit): Button = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        background = ContextCompat.getDrawable(context, R.drawable.spinner_bg)
        setTextColor(ContextCompat.getColor(context, com.orico.gestureassistant.R.color.primary))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setOnClickListener { runCatching { onClick() }.onFailure { showToast("无法执行设备操作") } }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            marginStart = dp(3)
            marginEnd = dp(3)
        }
    }

    private fun controlDevice(
        device: SmartDevice,
        operation: suspend (SmartDevice) -> com.orico.gestureassistant.smarthome.SmartDeviceResult,
    ) {
        lifecycleScope.launch {
            val result = runCatching { operation(device) }.getOrElse { error ->
                com.orico.gestureassistant.smarthome.SmartDeviceResult(false, "操作失败：${error.message.orEmpty().take(120)}")
            }
            showToast(result.message, if (result.successful) Toast.LENGTH_SHORT else Toast.LENGTH_LONG)
        }
    }

    private fun confirmRemoveSmartDevice(device: SmartDevice) {
        runCatching {
            AlertDialog.Builder(this).setTitle("删除 ${device.name}？")
                .setMessage("只删除 App 内配置，不会重置实体设备。")
                .setPositiveButton("删除") { _, _ ->
                    lifecycleScope.launch {
                        runCatching { smartDeviceStore.remove(device.id) }
                            .onFailure { showToast("删除失败") }
                    }
                }
                .setNegativeButton("取消", null).show()
        }.onFailure { showToast("无法打开删除确认") }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT) =
        Toast.makeText(this, message, duration).show()

    /** 云导入失败用持久弹窗展示完整原因（含服务器返回片段），方便照着排查，不像 toast 一闪而过。 */
    private fun showCloudError(message: String) {
        runCatching {
            AlertDialog.Builder(this)
                .setTitle("云导入失败")
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show()
        }.onFailure { showToast("云导入失败：$message", Toast.LENGTH_LONG) }
    }

    private fun showRuleWebhookDialog(label: GestureLabel) {
        lifecycleScope.launch {
            val current = runCatching { settings.values.first() }.getOrNull()
            val existing = if (label == GestureLabel.BACK_DOUBLE) current?.bindings?.backDoublePackage else current?.bindings?.backTriplePackage
            showWebhookDialog(existing) { url ->
                lifecycleScope.launch {
                    runCatching {
                        settings.setAppPackage(label, url)
                        settings.setAction(label, ActionId.HTTP_WEBHOOK)
                    }.onFailure { Toast.makeText(this@MainActivity, "Webhook URL 保存失败", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    private fun showWebhookDialog(existingUrl: String?, onSave: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = "http://192.168.1.10:8080/light/on"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 1; setText(existingUrl.orEmpty()); setSelection(text.length)
        }
        runCatching {
            AlertDialog.Builder(this).setTitle("设置 webhook URL").setMessage("支持 http:// 或 https://；当前动作默认发送 GET")
                .setView(input).setPositiveButton("保存") { _, _ -> onSave(input.text.toString().trim()) }
                .setNegativeButton("取消", null).show()
        }.onFailure { Toast.makeText(this, "无法打开 webhook 设置", Toast.LENGTH_SHORT).show() }
    }

    private fun showInstalledAppPicker(label: GestureLabel) {
        val apps = runCatching {
            packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { AppChoice(it.loadLabel(packageManager).toString(), it.activityInfo.packageName) }
                .distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
        if (apps.isEmpty()) { Toast.makeText(this, "未找到可启动的 App", Toast.LENGTH_SHORT).show(); return }
        runCatching {
            AlertDialog.Builder(this).setTitle("选择要打开的 App").setItems(apps.map { it.label }.toTypedArray()) { _, index ->
                lifecycleScope.launch {
                    runCatching {
                        settings.setAppPackage(label, apps[index].packageName)
                        settings.setAction(label, ActionId.LAUNCH_APP)
                    }
                }
            }.setNegativeButton("取消", null).show()
        }.onFailure { Toast.makeText(this, "无法打开 App 选择器", Toast.LENGTH_SHORT).show() }
    }

    private fun seekListener(onChange: (Int, Boolean) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = onChange(progress, fromUser)
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }
    /** 同步真实服务状态；临时解绑监听，避免程序设值再次启停服务。 */
    private fun updateServiceStatus() {
        val running = GestureForegroundService.running
        binding.serviceSwitch.setOnCheckedChangeListener(null)
        binding.serviceSwitch.isChecked = running
        binding.serviceSwitch.setOnCheckedChangeListener(serviceSwitchListener)
        binding.serviceStatus.text = if (running) "监听中" else "未运行"
        binding.serviceStatus.setTextColor(
            ContextCompat.getColor(this, if (running) R.color.primary else R.color.text_secondary),
        )
        updateSubSwitchesEnabled(running)
    }

    /** 总开关关闭时保留子开关值，只禁用并降低卡片透明度。 */
    private fun updateSubSwitchesEnabled(masterOn: Boolean) {
        binding.backTapSwitch.isEnabled = masterOn
        binding.airGestureSwitch.isEnabled = masterOn
        val alpha = if (masterOn) 1f else 0.4f
        binding.backTapSwitchCard.alpha = alpha
        binding.airGestureSwitchCard.alpha = alpha
    }

    /** 标签切换成功后给出极短触感反馈；设备无马达或系统拒绝时保持静默。 */
    private fun vibrateTabSwitch() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            }
            vibrator?.vibrate(VibrationEffect.createOneShot(15L, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
    private data class AppChoice(val label: String, val packageName: String)
    private enum class ActionOption(val id: ActionId, val label: String) {
        FLASHLIGHT(ActionId.TOGGLE_FLASHLIGHT, "切换手电筒"), MEDIA(ActionId.MEDIA_PLAY_PAUSE, "媒体播放 / 暂停"),
        LAUNCH_APP(ActionId.LAUNCH_APP, "打开指定 App"), WEBHOOK(ActionId.HTTP_WEBHOOK, "HTTP Webhook"),
        SCREENSHOT(ActionId.TAKE_SCREENSHOT, "截图"), LOCK(ActionId.LOCK_SCREEN, "锁定屏幕"),
        NOTIFICATIONS(ActionId.OPEN_NOTIFICATIONS, "打开通知栏"), BACK(ActionId.GLOBAL_BACK, "系统返回"),
        HOME(ActionId.GLOBAL_HOME, "系统主页"), RECENTS(ActionId.GLOBAL_RECENTS, "最近任务"),
        SMART_DEVICE(ActionId.SMART_DEVICE, "智能家居"),
    }
}
