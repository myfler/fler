package com.ai.fler.core.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ai.fler.MainActivity
import com.ai.fler.R
import com.ai.fler.core.mcp.McpConfig
import com.ai.fler.features.mcp.McpServerManager
import com.ai.fler.features.mcp.McpServerService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 悬浮窗保活服务。
 *
 * 在桌面上显示一个可拖动的小型悬浮球（TYPE_APPLICATION_OVERLAY）：
 * - 进程持有可见 UI 层 → OOM 优先级提升，MIUI/EMUI 等厂商 ROM 后台杀进程概率降低
 * - 与电池优化豁免 + 前台服务叠加，构成 fler 的后台保活组合
 *
 * 交互：
 * - 点击悬浮球 → 弹出功能菜单（MCP 开关/重启、补丁工具、仿真工具、打开 fler、收起、关闭）
 * - 拖动悬浮球 → 移动；拖到屏幕底部 → 关闭悬浮窗（并同步设置页开关）
 */
@AndroidEntryPoint
class OverlayKeepAliveService : Service() {

    @Inject
    lateinit var appLogger: com.ai.fler.core.log.AppLogger

    @Inject
    lateinit var config: McpConfig

    @Inject
    lateinit var mcpServerManager: McpServerManager

    private lateinit var windowManager: WindowManager
    private var currentView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var isMenuMode = false

    /** 悬浮球自身的锚点位置（菜单展开/收起时保持原位）。 */
    private var lastBallX = 0
    private var lastBallY = 0

    /** 下滑关闭引导蒙板层（仅拖动球体时显示）。 */
    private var dropZoneView: View? = null
    private var dropZoneParams: WindowManager.LayoutParams? = null
    private var dropZoneActive = false

    private var isDragging = false
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0

    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                serviceRunning = false
                setOverlayEnabledPref(this, false)
                removeCurrent()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    appLogger.error(TAG, "悬浮窗权限缺失，无法启动保活悬浮球")
                    serviceRunning = false
                    stopSelf()
                    return START_NOT_STICKY
                }
                serviceRunning = true
                startForeground(NOTIFICATION_ID, buildNotification())
                showBall()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceRunning = false
        removeCurrent()
        hideDropZone()
        super.onDestroy()
    }

    // ========== 视图构建 ==========

    @SuppressLint("ClickableViewAccessibility")
    private fun showBall() {
        val lp = ensureParams()
        if (isMenuMode) isMenuMode = false
        lp.x = lastBallX
        lp.y = lastBallY

        val size = dp(48)
        val root = ImageView(this).apply {
            setImageDrawable(buildBallDrawable(size))
            layoutParams = LinearLayout.LayoutParams(size, size)
            setOnTouchListener { _, event -> handleTouch(event) }
        }
        replaceContent(root, size, size)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showMenu() {
        val lp = ensureParams()
        // 仅首次从球体展开时记录球体锚点；refreshMenu 复用时不更新，
        // 否则 lp.x/lp.y 已是菜单坐标，会把锚点污染成菜单位置导致收起后球体跑偏
        if (!isMenuMode) {
            isMenuMode = true
            lastBallX = lp.x
            lastBallY = lp.y
        }

        val mcpRunning = mcpServerManager.isRunning()
        val patchEnabled = config.patchEnabled.value
        val emuEnabled = config.emuToolsEnabled.value

        val menuWidth = dp(250)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedMenuBackground()
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnTouchListener { _, event -> handleTouch(event) }
        }

        // 头部：品牌标记 + 标题 + 收起
        root.addView(menuHeader())

        // ---- 服务管理 ----
        root.addView(menuSectionLabel("服务管理"))
        root.addView(
            menuRow(
                label = "MCP 服务",
                dotColor = if (mcpRunning) color(R.color.overlay_status_on)
                    else color(R.color.overlay_status_off),
                pillText = if (mcpRunning) "运行中" else "已停止",
                pillColor = if (mcpRunning) color(R.color.overlay_status_on)
                    else color(R.color.overlay_status_off),
                onClick = { toggleMcp() },
            )
        )
        root.addView(
            menuRow(
                label = "重启 MCP 服务",
                dotColor = color(R.color.fler_primary),
                onClick = { restartMcp() },
            )
        )

        // ---- 工具开关 ----
        root.addView(menuSectionLabel("工具开关"))
        root.addView(
            menuRow(
                label = "补丁工具",
                dotColor = if (patchEnabled) color(R.color.overlay_status_on)
                    else color(R.color.overlay_status_off),
                pillText = if (patchEnabled) "开" else "关",
                pillColor = if (patchEnabled) color(R.color.overlay_status_on)
                    else color(R.color.overlay_status_off),
                onClick = {
                    config.setPatchEnabled(!config.patchEnabled.value)
                    toast(if (config.patchEnabled.value) "补丁工具已开启" else "补丁工具已关闭")
                    refreshMenu()
                },
            )
        )
        root.addView(
            menuRow(
                label = "仿真工具",
                dotColor = if (emuEnabled) color(R.color.overlay_status_on)
                    else color(R.color.overlay_status_off),
                pillText = if (emuEnabled) "开" else "关",
                pillColor = if (emuEnabled) color(R.color.overlay_status_on)
                    else color(R.color.overlay_status_off),
                onClick = {
                    config.setEmuToolsEnabled(!config.emuToolsEnabled.value)
                    toast(if (config.emuToolsEnabled.value) "仿真工具已开启" else "仿真工具已关闭")
                    refreshMenu()
                },
            )
        )

        // ---- 其他操作 ----
        root.addView(menuSectionLabel("其他"))
        root.addView(
            menuRow(
                label = "打开 fler",
                dotColor = color(R.color.fler_primary),
                onClick = { openApp() },
            )
        )
        root.addView(
            menuRow(
                label = "关闭悬浮窗",
                dotColor = color(R.color.overlay_danger),
                textColor = color(R.color.overlay_danger),
                onClick = { closeOverlay() },
            )
        )

        replaceContent(root, menuWidth, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    /** 菜单头部：品牌圆标 + 标题 + 收起按钮。 */
    private fun menuHeader(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(4), dp(6))

            // 品牌小圆标（取应用图标裁剪圆形）
            val badgeSize = dp(26)
            addView(ImageView(this@OverlayKeepAliveService).apply {
                setImageDrawable(buildBallDrawable(badgeSize))
                layoutParams = LinearLayout.LayoutParams(badgeSize, badgeSize).apply {
                    setMargins(0, 0, dp(10), 0)
                }
            })

            // 标题
            addView(LinearLayout(this@OverlayKeepAliveService).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@OverlayKeepAliveService).apply {
                    text = "fler"
                    setTextColor(Color.WHITE)
                    setTextSize(15f)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                })
                addView(TextView(this@OverlayKeepAliveService).apply {
                    text = "后台保活 · 悬浮菜单"
                    setTextColor(color(R.color.overlay_status_off))
                    setTextSize(10f)
                })
            })

            addView(closeButton("✕") { showBall() })
        }

    /** 分组小标题。 */
    private fun menuSectionLabel(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(color(R.color.overlay_status_off))
            setTextSize(10f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(14), dp(8), dp(10), dp(2))
            setLetterSpacing(0.08f)
        }

    /** 重建当前菜单（动作后刷新状态文本）。 */
    private fun refreshMenu() {
        if (!isMenuMode) return
        showMenu()
    }

    /** 构建圆形悬浮球图标：图标位图先裁剪为圆形（四角透明），再叠在深色圆底上。 */
    private fun buildBallDrawable(size: Int): LayerDrawable {
        val icon = ContextCompat.getDrawable(this, R.mipmap.ic_launcher) ?: return LayerDrawable(
            arrayOf(
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ContextCompat.getColor(this@OverlayKeepAliveService, R.color.overlay_ball))
                }
            )
        )
        val inset = dp(2)
        val iconSize = size - inset * 2

        // 1) 图标绘制到方形位图（兼容 PNG 与 adaptive icon）
        val square = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(square)
        icon.setBounds(0, 0, iconSize, iconSize)
        icon.draw(canvas)

        // 2) 圆形裁剪：BitmapShader + drawCircle，四角透明
        val circle = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
        val circleCanvas = Canvas(circle)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(square, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        circleCanvas.drawCircle(iconSize / 2f, iconSize / 2f, iconSize / 2f, paint)

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(this@OverlayKeepAliveService, R.color.overlay_ball))
        }
        return LayerDrawable(arrayOf(bg, BitmapDrawable(resources, circle))).apply {
            setLayerInset(1, inset, inset, inset, inset)
        }
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)

    private fun roundedMenuBackground(): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(color(R.color.overlay_menu_bg))
            setStroke(dp(1), color(R.color.overlay_menu_border))
        }

    private fun closeButton(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(16f)
            setPadding(dp(10), dp(2), dp(10), dp(2))
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = selectableItemBackground()
            setOnClickListener { onClick() }
        }

    /**
     * 菜单行：左状态圆点 + 标签 + 可选状态胶囊。
     */
    private fun menuRow(
        label: String,
        dotColor: Int,
        onClick: () -> Unit,
        pillText: String? = null,
        pillColor: Int = 0,
        textColor: Int = Color.WHITE,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(10), dp(12))
            isClickable = true
            isFocusable = true
            background = selectableItemBackground()
            setOnClickListener { onClick() }

            // 状态圆点（带深色描边，提升辨识度）
            addView(View(this@OverlayKeepAliveService).apply {
                setBackgroundDrawable(
                    android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(dotColor)
                        setStroke(dp(2), color(R.color.overlay_menu_bg))
                    }
                )
                layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                    setMargins(0, 0, dp(10), 0)
                }
            })

            // 标签
            addView(TextView(this@OverlayKeepAliveService).apply {
                text = label
                setTextColor(textColor)
                setTextSize(14f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            // 状态胶囊
            if (pillText != null) {
                addView(TextView(this@OverlayKeepAliveService).apply {
                    text = pillText
                    setTextColor(Color.WHITE)
                    setTextSize(11f)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(dp(10), dp(3), dp(10), dp(3))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = dp(9).toFloat()
                        setColor(pillColor)
                    }
                })
            }
        }

    private fun selectableItemBackground(): android.graphics.drawable.Drawable? {
        val outValue = TypedValue()
        if (theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)) {
            return ContextCompat.getDrawable(this, outValue.resourceId)
        }
        return null
    }

    // ========== 触摸处理 ==========

    @SuppressLint("ClickableViewAccessibility")
    private fun handleTouch(event: MotionEvent): Boolean {
        val lp = params ?: return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = false
                downX = event.rawX
                downY = event.rawY
                startX = lp.x
                startY = lp.y
                // 菜单模式下让子项先拿到 DOWN（保留点击），球体模式下直接接管
                !isMenuMode
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!isDragging && (kotlin.math.abs(dx) > TOUCH_SLOP || kotlin.math.abs(dy) > TOUCH_SLOP)) {
                    isDragging = true
                }
                if (isDragging) {
                    lp.x = startX + dx.toInt()
                    lp.y = startY + dy.toInt()
                    runCatching { windowManager.updateViewLayout(currentView!!, lp) }
                    // 球体拖动时显示/更新底部引导蒙板
                    if (!isMenuMode) updateDropZone(lp)
                    true
                } else {
                    false
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    hideDropZone()
                    if (isNearBottom(lp)) {
                        toast("已关闭悬浮窗保活")
                        closeOverlay()
                    } else if (!isMenuMode) {
                        snapToEdge()
                    }
                    true
                } else if (!isMenuMode) {
                    // 球体点击 → 打开菜单
                    showMenu()
                    true
                } else {
                    // 菜单项点击由子 TextView 处理
                    false
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                hideDropZone()
                false
            }
            else -> false
        }
    }

    private fun isNearBottom(lp: WindowManager.LayoutParams): Boolean {
        val point = Point()
        runCatching { windowManager.defaultDisplay.getRealSize(point) }
        val height = if (lp.height == WindowManager.LayoutParams.WRAP_CONTENT) dp(48) else lp.height
        return lp.y + height >= point.y - dp(100)
    }

    // ========== 下滑关闭引导蒙板 ==========

    /** 半圆蒙板高度（宽度超出屏幕，左右溢出，顶部为横跨全宽的圆弧）。 */
    private val dropZoneHeight: Int get() = dp(80)

    /** 半圆蒙板宽度：超出屏幕宽度，左右对称溢出（FLAG_LAYOUT_NO_LIMITS 允许越界）。 */
    private val dropZoneWidth: Int
        get() {
            val point = Point()
            runCatching { windowManager.defaultDisplay.getRealSize(point) }
            return (point.x * 1.35f).toInt()
        }

    /** 半圆蒙板背景：底部平直、顶部为横跨全宽的圆弧。 */
    private fun dropZoneBackground(active: Boolean): android.graphics.drawable.Drawable =
        object : android.graphics.drawable.Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color(
                    if (active) R.color.overlay_drop_zone_active else R.color.overlay_drop_zone
                )
            }
            private val path = android.graphics.Path()

            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                // 半椭圆穹顶：椭圆短轴半径 = 高度，顶边拱到蒙板顶部（底部平、顶部圆）
                path.reset()
                path.moveTo(0f, h)
                path.arcTo(android.graphics.RectF(0f, 0f, w, 2f * h), 180f, 180f)
                path.close()
                canvas.drawPath(path, paint)
            }

            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }

    /** 创建/复用底部引导蒙板（仅显示一次，拖动期间复用）。 */
    private fun ensureDropZone(): LinearLayout {
        dropZoneView?.let { return it as LinearLayout }
        val zone = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = dropZoneBackground(false)
        }
        zone.addView(TextView(this).apply {
            text = "▼"
            setTextColor(Color.WHITE)
            setTextSize(22f)
            setPadding(dp(8), 0, dp(8), 0)
        })
        zone.addView(TextView(this).apply {
            text = "下滑到此关闭"
            setTextColor(Color.WHITE)
            setTextSize(15f)
        })
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val zlp = WindowManager.LayoutParams(
            dropZoneWidth,
            dropZoneHeight,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        dropZoneParams = zlp
        dropZoneView = zone
        runCatching {
            windowManager.addView(zone, zlp)
        }.onFailure {
            appLogger.warn(TAG, "底部蒙板添加失败: ${it.message}")
            dropZoneView = null
            dropZoneParams = null
        }
        return zone
    }

    /** 拖动期间更新蒙板：球体进入底部判定区则高亮。 */
    private fun updateDropZone(lp: WindowManager.LayoutParams) {
        val zone = ensureDropZone()
        val active = isNearBottom(lp)
        if (active == dropZoneActive) return
        dropZoneActive = active
        zone.background = dropZoneBackground(active)
        if (active) {
            (zone.getChildAt(0) as? TextView)?.text = "▲"
            (zone.getChildAt(1) as? TextView)?.text = "松开关闭"
        } else {
            (zone.getChildAt(0) as? TextView)?.text = "▼"
            (zone.getChildAt(1) as? TextView)?.text = "下滑到此关闭"
        }
    }

    private fun hideDropZone() {
        dropZoneActive = false
        dropZoneView?.let { view ->
            runCatching { windowManager.removeView(view) }
            dropZoneView = null
            dropZoneParams = null
        }
    }

    /** 拖动结束吸附到最近边缘（避免挡住内容）。 */
    private fun snapToEdge() {
        val lp = params ?: return
        val wm = windowManager
        val point = Point()
        wm.defaultDisplay.getRealSize(point)
        val size = dp(48)
        val margin = dp(8)
        // 球心在左半屏 → 吸附左侧，否则吸附右侧
        lp.x = if (lp.x + size / 2 < point.x / 2) margin else point.x - size - margin
        lp.y = lp.y.coerceIn(0, point.y - size)
        lastBallX = lp.x
        lastBallY = lp.y
        runCatching { wm.updateViewLayout(currentView!!, lp) }
    }

    // ========== 窗口生命周期 ==========

    private fun ensureParams(): WindowManager.LayoutParams {
        val existing = params
        if (existing != null) return existing
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            dp(48),
            dp(48),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(160)
        }
        lastBallX = lp.x
        lastBallY = lp.y
        params = lp
        return lp
    }

    private fun replaceContent(newView: View, width: Int, height: Int) {
        val lp = ensureParams()
        currentView?.let { runCatching { windowManager.removeView(it) } }
        currentView = null
        lp.width = width
        lp.height = height
        clampPosition(lp)
        // 每次 addView 使用全新的 LayoutParams，避免复用已被系统挂载/修改过的实例
        val addLp = WindowManager.LayoutParams(
            lp.width,
            lp.height,
            lp.type,
            lp.flags,
            lp.format,
        ).apply {
            gravity = lp.gravity
            x = lp.x
            y = lp.y
            token = lp.token
        }
        params = addLp
        runCatching {
            windowManager.addView(newView, addLp)
            currentView = newView
            appLogger.info(TAG, if (isMenuMode) "悬浮菜单已展开" else "悬浮球已显示")
        }.onFailure {
            appLogger.error(TAG, "悬浮窗添加失败: ${it.message}")
            currentView = null
            Toast.makeText(this, "悬浮窗启动失败", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun clampPosition(lp: WindowManager.LayoutParams) {
        val point = Point()
        runCatching { windowManager.defaultDisplay.getRealSize(point) }
        lp.x = lp.x.coerceIn(0, (point.x - lp.width).coerceAtLeast(0))
        if (lp.height != WindowManager.LayoutParams.WRAP_CONTENT) {
            lp.y = lp.y.coerceIn(0, (point.y - lp.height).coerceAtLeast(0))
        } else {
            lp.y = lp.y.coerceIn(0, (point.y - dp(320)).coerceAtLeast(0))
        }
    }

    private fun removeCurrent() {
        currentView?.let { view ->
            runCatching { windowManager.removeView(view) }
            currentView = null
        }
    }

    // ========== 功能动作 ==========

    private fun toggleMcp() {
        if (mcpServerManager.isRunning()) {
            config.setEnabled(false)
            McpServerService.stop(this)
            toast("MCP 服务已停止")
        } else {
            config.setEnabled(true)
            McpServerService.start(this)
            toast("MCP 服务已启动")
        }
        handler.postDelayed({ refreshMenu() }, 150)
    }

    private fun restartMcp() {
        config.setEnabled(true)
        if (mcpServerManager.isRunning()) {
            mcpServerManager.stop()
        }
        McpServerService.start(this)
        toast("MCP 服务已重启")
        handler.postDelayed({ refreshMenu() }, 200)
    }

    private fun closeOverlay() {
        setOverlayEnabledPref(this, false)
        removeCurrent()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun openApp() {
        runCatching {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        }.onFailure {
            appLogger.warn(TAG, "打开 App 失败: ${it.message}")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "悬浮窗保活",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "fler 悬浮窗保活服务"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayKeepAliveService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("fler 悬浮窗保活")
            .setContentText("点击悬浮球可管理 MCP / 工具开关")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(0, "停止", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val TAG = "OverlayKeepAlive"
        private const val CHANNEL_ID = "fler_overlay_keepalive"
        private const val NOTIFICATION_ID = 2003
        private const val TOUCH_SLOP = 6

        /** 服务当前是否在运行（进程内标志，用于设置页自动恢复判断）。 */
        @Volatile
        private var serviceRunning = false

        fun isRunning(): Boolean = serviceRunning

        const val ACTION_STOP = "com.ai.fler.ACTION_STOP_OVERLAY_KEEPALIVE"

        /** 悬浮窗保活开关持久化位置（与 SettingsViewModel 共享）。 */
        const val PREFS_NAME = "keep_alive"
        const val KEY_OVERLAY_ENABLED = "overlay_enabled"

        /** 同步设置页开关状态。 */
        fun setOverlayEnabledPref(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_OVERLAY_ENABLED, enabled).apply()
        }

        fun isOverlayEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_OVERLAY_ENABLED, false)

        fun start(context: Context) {
            val intent = Intent(context, OverlayKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, OverlayKeepAliveService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}