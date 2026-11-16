package com.wordlookup.screen.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextWatcher
import android.util.DisplayMetrics
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.wordlookup.screen.data.AppPreferences
import com.wordlookup.screen.data.DockSide
import com.wordlookup.screen.data.LocalDictionary
import com.wordlookup.screen.data.VocabularyDatabase
import com.wordlookup.screen.data.VocabularyEntity
import com.wordlookup.screen.widget.VocabWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

class ScreenLookupAccessibilityService : AccessibilityService(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_UPDATE_DOCK = "com.wordlookup.screen.ACTION_UPDATE_DOCK"
        private const val SCREENSHOT_COOLDOWN_MS = 1200L

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private lateinit var vocabDb: VocabularyDatabase
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private var dockView: View? = null
    private var dockParams: WindowManager.LayoutParams? = null
    private var captureOverlayView: View? = null
    private var popupCardView: View? = null

    private var flatElements: List<Pair<Text.Element, String>> = emptyList()
    private var recognizedBlocks: List<Pair<Rect, String>> = emptyList()
    private var savedWordBoxes: List<Rect> = emptyList()
    private var lastCapturedBitmap: Bitmap? = null
    private val slicedBitmaps = mutableListOf<Bitmap>()
    private var bilingualScrollView: View? = null
    private var bilingualMode: Boolean = false
    private var screenWidth = 1080
    private var screenHeight = 2400
    private var lastScreenshotTimeMs = 0L

    private val idleShrinkRunnable = Runnable {
        val handle = dockView ?: return@Runnable
        val side = AppPreferences.getDockSide(this)
        val shiftX = if (side == DockSide.LEFT) -handle.width * 0.48f else handle.width * 0.48f
        handle.animate().translationX(shiftX).alpha(0.42f).setDuration(280).start()
    }

    private val dockUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE_DOCK) {
                refreshDockPosition()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        vocabDb = VocabularyDatabase.getInstance(this)
        LocalDictionary.initFromAssetsIfPresent(this)
        tts = TextToSpeech(this, this)
        updateScreenMetrics()

        val filter = IntentFilter(ACTION_UPDATE_DOCK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dockUpdateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(dockUpdateReceiver, filter)
        }

        showDockedEdgeHandle()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenMetrics()
        refreshDockPosition()
        removeViewSafe(captureOverlayView)
        removeViewSafe(popupCardView)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val res = tts?.setLanguage(Locale.US)
            ttsReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        isRunning = false
        mainHandler.removeCallbacks(idleShrinkRunnable)
        runCatching { unregisterReceiver(dockUpdateReceiver) }
        tts?.stop()
        tts?.shutdown()
        removeViewSafe(dockView)
        removeViewSafe(captureOverlayView)
        removeViewSafe(popupCardView)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun updateScreenMetrics() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun resetIdleTimer() {
        mainHandler.removeCallbacks(idleShrinkRunnable)
        dockView?.animate()?.translationX(0f)?.alpha(1f)?.setDuration(160)?.start()
        mainHandler.postDelayed(idleShrinkRunnable, 3000L)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showDockedEdgeHandle() {
        removeViewSafe(dockView)
        val side = AppPreferences.getDockSide(this)
        val yRatio = AppPreferences.getDockYRatio(this)

        val handle = TextView(this).apply {
            text = "词"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(14), dp(8), dp(14))
            background = buildDockDrawable(side)
            elevation = dp(6).toFloat()
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    v.systemGestureExclusionRects = listOf(Rect(0, 0, v.width, v.height))
                }
            }
        }

        val widthPx = dp(36)
        val heightPx = dp(64)
        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            x = if (side == DockSide.LEFT) 0 else screenWidth - widthPx
            y = (screenHeight * yRatio).toInt()
        }

        var initialY = 0
        var initialTouchY = 0f
        var isDragging = false

        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    mainHandler.removeCallbacks(idleShrinkRunnable)
                    handle.animate().translationX(0f).alpha(1f).setDuration(120).start()
                    initialY = params.y
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dy) > dp(6)) {
                        isDragging = true
                        params.y = (initialY + dy).coerceIn(dp(60), screenHeight - dp(120))
                        windowManager.updateViewLayout(handle, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        val ratio = params.y.toFloat() / screenHeight.toFloat()
                        AppPreferences.setDockYRatio(this@ScreenLookupAccessibilityService, ratio)
                        resetIdleTimer()
                    } else {
                        triggerScreenCaptureAndLookup()
                    }
                    true
                }
                else -> false
            }
        }

        dockView = handle
        dockParams = params
        windowManager.addView(handle, params)
        resetIdleTimer()
    }

    private fun refreshDockPosition() {
        val handle = dockView as? TextView ?: return
        val params = dockParams ?: return
        val side = AppPreferences.getDockSide(this)
        val yRatio = AppPreferences.getDockYRatio(this)
        handle.background = buildDockDrawable(side)
        params.x = if (side == DockSide.LEFT) 0 else screenWidth - params.width
        params.y = (screenHeight * yRatio).toInt().coerceIn(dp(40), screenHeight - dp(100))
        windowManager.updateViewLayout(handle, params)
        resetIdleTimer()
    }

    private fun buildDockDrawable(side: DockSide): GradientDrawable {
        val r = dp(18).toFloat()
        val radii = if (side == DockSide.LEFT) {
            floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        } else {
            floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        }
        return GradientDrawable().apply {
            setColor(Color.parseColor("#D91A73E8"))
            cornerRadii = radii
        }
    }

    private fun triggerScreenCaptureAndLookup() {
        val now = System.currentTimeMillis()
        if (now - lastScreenshotTimeMs < SCREENSHOT_COOLDOWN_MS) {
            Toast.makeText(this, "操作太快啦，请稍等 1 秒再试", Toast.LENGTH_SHORT).show()
            return
        }
        lastScreenshotTimeMs = now

        mainHandler.removeCallbacks(idleShrinkRunnable)
        dockView?.visibility = View.INVISIBLE
        removeViewSafe(popupCardView)

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    dockView?.visibility = View.VISIBLE
                    resetIdleTimer()
                    val hardwareBuffer = screenshot.hardwareBuffer
                    val colorSpace = screenshot.colorSpace
                    val rawBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                    val softwareBitmap = rawBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                    hardwareBuffer.close()
                    rawBitmap?.recycle()

                    if (softwareBitmap == null) {
                        Toast.makeText(
                            this@ScreenLookupAccessibilityService,
                            "截屏转换失败，请重试",
                            Toast.LENGTH_SHORT
                        ).show()
                        return
                    }
                    runOcrAndOpenOverlay(softwareBitmap)
                }

                override fun onFailure(errorCode: Int) {
                    dockView?.visibility = View.VISIBLE
                    resetIdleTimer()
                    val msg = when (errorCode) {
                        ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
                            "当前页面受隐私或版权保护，暂不支持截屏取词"
                        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
                            "截屏过于频繁，请 1 秒后再试"
                        else -> "截屏失败 (错误码: $errorCode)"
                    }
                    Toast.makeText(this@ScreenLookupAccessibilityService, msg, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun clearCapturedBitmaps() {
        slicedBitmaps.forEach { if (!it.isRecycled) it.recycle() }
        slicedBitmaps.clear()
        lastCapturedBitmap?.let { if (!it.isRecycled) it.recycle() }
        lastCapturedBitmap = null
    }

    private fun runOcrAndOpenOverlay(bitmap: Bitmap) {
        clearCapturedBitmaps()
        lastCapturedBitmap = bitmap
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val list = mutableListOf<Pair<Text.Element, String>>()
                val blocks = mutableListOf<Pair<Rect, String>>()
                for (block in visionText.textBlocks) {
                    block.boundingBox?.let { bBox ->
                        val bText = block.text.replace("\n", " ").trim()
                        if (bText.length >= 16) blocks.add(bBox to bText)
                    }
                    for (line in block.lines) {
                        val lineText = line.text
                        for (element in line.elements) {
                            list.add(element to lineText)
                        }
                    }
                }
                flatElements = list
                recognizedBlocks = blocks
                bilingualMode = false
                serviceScope.launch {
                    val savedSet = withContext(Dispatchers.IO) {
                        vocabDb.vocabularyDao().getAllSavedWords().map { it.lowercase() }.toSet()
                    }
                    savedWordBoxes = flatElements.mapNotNull { (el, _) ->
                        val cleaned = el.text.trim().replace(Regex("^[^a-zA-Z]+|[^a-zA-Z]+$"), "").lowercase()
                        if (cleaned in savedSet) el.boundingBox else null
                    }
                    showLongPressCaptureOverlay()
                }
            }
            .addOnFailureListener {
                bitmap.recycle()
                Toast.makeText(this, "文字识别失败", Toast.LENGTH_SHORT).show()
            }
    }

    private class SelectionHighlightView(
        context: Context,
        private val savedBoxes: List<Rect>
    ) : View(context) {
        private val bilingualBgPaint = Paint().apply {
            color = Color.parseColor("#E6EFF6FF")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        private val bilingualBorderPaint = Paint().apply {
            color = Color.parseColor("#3B82F6")
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }
        private val bilingualTextPaint = Paint().apply {
            color = Color.parseColor("#1E293B")
            textSize = 30f
            isAntiAlias = true
        }
        var bilingualItems: List<Pair<Rect, String>> = emptyList()
            set(value) {
                field = value
                invalidate()
            }
        private val savedFillPaint = Paint().apply {
            color = Color.parseColor("#2EE69F00")
            style = Paint.Style.FILL
        }
        private val savedStrokePaint = Paint().apply {
            color = Color.parseColor("#CCEA8600")
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
            isAntiAlias = true
        }
        private val fillPaint = Paint().apply {
            color = Color.parseColor("#441A73E8")
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint().apply {
            color = Color.parseColor("#CC1A73E8")
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }
        var highlightedBoxes: List<Rect> = emptyList()
            set(value) {
                field = value
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (box in savedBoxes) {
                val rf = RectF(box.left - 3f, box.top - 3f, box.right + 3f, box.bottom + 3f)
                canvas.drawRoundRect(rf, 8f, 8f, savedFillPaint)
                canvas.drawRoundRect(rf, 8f, 8f, savedStrokePaint)
            }
            for (box in highlightedBoxes) {
                val rf = RectF(box.left - 4f, box.top - 4f, box.right + 4f, box.bottom + 4f)
                canvas.drawRoundRect(rf, 10f, 10f, fillPaint)
                canvas.drawRoundRect(rf, 10f, 10f, strokePaint)
            }
            for ((box, summary) in bilingualItems) {
                val topY = (box.bottom - 38f).coerceAtLeast(box.top.toFloat())
                val bannerRect = RectF(box.left.toFloat(), topY, box.right.toFloat(), topY + 44f)
                canvas.drawRoundRect(bannerRect, 10f, 10f, bilingualBgPaint)
                canvas.drawRoundRect(bannerRect, 10f, 10f, bilingualBorderPaint)
                canvas.drawText(summary.take(36), box.left + 12f, topY + 31f, bilingualTextPaint)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showLongPressCaptureOverlay() {
        removeViewSafe(captureOverlayView)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#1A000000"))
        }

        val highlightView = SelectionHighlightView(this, savedWordBoxes)
        root.addView(
            highlightView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E61E293B"))
                cornerRadius = dp(22).toFloat()
            }
        }
        val hintBanner = TextView(this).apply {
            text = "长按取词"
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(0, 0, dp(8), 0)
        }
        val bilingualBtn = TextView(this).apply {
            text = "🌐 双语对照"
            textSize = 11.5f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#33FFFFFF"))
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener {
                bilingualMode = !bilingualMode
                if (bilingualMode) {
                    val scrollContainer = buildSlicedBilingualScrollView()
                    if (scrollContainer != null) {
                        bilingualScrollView?.let { root.removeView(it) }
                        bilingualScrollView = scrollContainer
                        root.addView(
                            scrollContainer,
                            0,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            )
                        )
                        highlightView.visibility = View.GONE
                        hintBanner.text = "切片双语模式（上下滚动零遮挡）"
                    }
                } else {
                    bilingualScrollView?.let { root.removeView(it) }
                    bilingualScrollView = null
                    highlightView.visibility = View.VISIBLE
                    hintBanner.text = "长按取词"
                }
            }
        }
        val previewBtn = TextView(this).apply {
            text = "📑 预习词单"
            textSize = 11.5f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#33FFFFFF"))
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener {
                showPreviewWordListCard()
            }
        }
        topBar.addView(hintBanner)
        topBar.addView(bilingualBtn)
        topBar.addView(previewBtn)

        val hintParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = dp(48) }
        root.addView(topBar, hintParams)

        var isPhraseSelecting = false
        var anchorIdx = -1
        var endIdx = -1

        fun updateSelectionHighlight() {
            if (anchorIdx < 0 || endIdx < 0) {
                highlightView.highlightedBoxes = emptyList()
                return
            }
            val start = minOf(anchorIdx, endIdx)
            val end = minOf(maxOf(anchorIdx, endIdx), start + 4)
            highlightView.highlightedBoxes = (start..end).mapNotNull { flatElements[it].first.boundingBox }
            val currentPhrase = (start..end).joinToString(" ") { flatElements[it].first.text }
            hintBanner.text = "🔍 当前选中: $currentPhrase"
        }

        val gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onLongPress(e: MotionEvent) {
                    val idx = findElementIndexAt(e.rawX.toInt(), e.rawY.toInt())
                    if (idx >= 0) {
                        isPhraseSelecting = true
                        anchorIdx = idx
                        endIdx = idx
                                                updateSelectionHighlight()
                    } else {
                        Toast.makeText(
                            this@ScreenLookupAccessibilityService,
                            "未在该位置识别到英文单词",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    clearCapturedBitmaps()
                    removeViewSafe(captureOverlayView)
                    removeViewSafe(popupCardView)
                    return true
                }
            }
        )

        root.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            if (isPhraseSelecting) {
                when (event.action) {
                    MotionEvent.ACTION_MOVE -> {
                        val hoverIdx = findElementIndexAt(event.rawX.toInt(), event.rawY.toInt())
                        if (hoverIdx >= 0 && hoverIdx != endIdx) {
                            endIdx = hoverIdx
                            updateSelectionHighlight()
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isPhraseSelecting = false
                        hintBanner.text = "取词模式：长按单词（橙框为已收藏生词），拖动可选词组"
                        if (anchorIdx >= 0 && endIdx >= 0) {
                            val start = minOf(anchorIdx, endIdx)
                            val end = minOf(maxOf(anchorIdx, endIdx), start + 4)
                            val phrase = (start..end).joinToString(" ") { flatElements[it].first.text }
                            val sentence = flatElements[start].second
                            showWordPopupCard(phrase, sentence, event.rawX.toInt(), event.rawY.toInt())
                        }
                        anchorIdx = -1
                        endIdx = -1
                    }
                }
            }
            true
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        captureOverlayView = root
        windowManager.addView(root, params)
    }

    private fun findElementIndexAt(rawX: Int, rawY: Int): Int {
        val tolerance = dp(10)
        for (i in flatElements.indices) {
            val box = flatElements[i].first.boundingBox ?: continue
            val expanded = Rect(
                box.left - tolerance,
                box.top - tolerance,
                box.right + tolerance,
                box.bottom + tolerance
            )
            if (expanded.contains(rawX, rawY)) {
                return i
            }
        }
        return -1
    }

    /**
     * 方案二：按 OCR 段落底部坐标 [y1, y2, ...] 将屏幕截图 Bitmap 横向切分为多张图片条，
     * 在全屏 ScrollView 中交替放入「原屏幕切片 ImageView」与「中文译文条 TextView」，实现零遮挡真插行。
     */
    private fun buildSlicedBilingualScrollView(): ScrollView? {
        val fullBmp = lastCapturedBitmap ?: return null
        if (fullBmp.isRecycled) return null

        slicedBitmaps.forEach { if (!it.isRecycled) it.recycle() }
        slicedBitmaps.clear()

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            isVerticalScrollBarEnabled = false
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val bmpW = fullBmp.width
        val bmpH = fullBmp.height
        var currentTopY = 0
        var sliceNumber = 1

        for ((rect, blockText) in recognizedBlocks.sortedBy { it.first.bottom }) {
            val cutY = (rect.bottom + dp(4)).coerceIn(currentTopY + dp(24), bmpH)
            if (cutY <= currentTopY) continue

            val sliceH = cutY - currentTopY
            val sliceBmp = Bitmap.createBitmap(fullBmp, 0, currentTopY, bmpW, sliceH)
            slicedBitmaps.add(sliceBmp)

            val imageView = ImageView(this).apply {
                setImageBitmap(sliceBmp)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_XY
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            column.addView(imageView)

            val words = blockText.split(Regex("\\s+"))
                .mapNotNull { w ->
                    val e = LocalDictionary.lookup(w)
                    if (e.phonetic.isNotEmpty()) "${e.word}(${e.meaning.substringBefore("；")})" else null
                }
                .distinct()
                .take(5)
            val transSummary = if (words.isNotEmpty()) {
                "✂ 切片 #$sliceNumber 词汇解析：" + words.joinToString(" · ")
            } else {
                "✂ 切片 #$sliceNumber 原文：${blockText.take(60)}..."
            }

            val transStrip = TextView(this).apply {
                text = transSummary
                textSize = 13f
                setTextColor(Color.parseColor("#1E293B"))
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#EFF6FF"))
                    setStroke(dp(1), Color.parseColor("#BFDBFE"))
                }
            }
            column.addView(transStrip)

            currentTopY = cutY
            sliceNumber += 1
        }

        if (currentTopY < bmpH) {
            val tailBmp = Bitmap.createBitmap(fullBmp, 0, currentTopY, bmpW, bmpH - currentTopY)
            slicedBitmaps.add(tailBmp)
            val tailImage = ImageView(this).apply {
                setImageBitmap(tailBmp)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_XY
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            column.addView(tailImage)
        }

        val isDark = AppPreferences.isDarkMode(this)
        val exitBottomBtn = Button(this).apply {
            text = "✓ 本屏双语已读完 · 点击退出并继续翻页"
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(if (isDark) "#2563EB" else "#1A73E8"))
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(16), dp(12), dp(16), dp(36))
            }
            setOnClickListener {
                clearCapturedBitmaps()
                removeViewSafe(captureOverlayView)
                removeViewSafe(popupCardView)
            }
        }
        column.addView(exitBottomBtn)

        scrollView.addView(column)
        return scrollView
    }

    private fun showPreviewWordListCard() {
        removeViewSafe(popupCardView)
        val candidates = flatElements
            .map { it.first.text.trim().replace(Regex("^[^a-zA-Z]+|[^a-zA-Z]+$"), "") }
            .filter { it.length >= 6 }
            .distinctBy { it.lowercase() }
            .sortedByDescending { it.length }
            .take(6)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#E0E3E7"))
            }
            elevation = dp(10).toFloat()
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(this).apply {
            text = "📑 本屏预习难词单 (${candidates.size})"
            textSize = 15f
            setTextColor(Color.parseColor("#0F172A"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(Color.parseColor("#5F6368"))
            setPadding(dp(6), dp(2), dp(4), dp(2))
            setOnClickListener { removeViewSafe(popupCardView) }
        }
        headerRow.addView(titleView)
        headerRow.addView(closeBtn)
        card.addView(headerRow)

        if (candidates.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "当前屏幕未检测到明显的长难词。"
                textSize = 13f
                setTextColor(Color.parseColor("#64748B"))
                setPadding(0, dp(8), 0, dp(4))
            }
            card.addView(emptyView)
        } else {
            for (word in candidates) {
                val entry = LocalDictionary.lookup(word)
                val itemRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(6), 0, dp(6))
                }
                val textCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val wView = TextView(this).apply {
                    text = "${entry.word} ${entry.phonetic}"
                    textSize = 13.5f
                    setTextColor(Color.parseColor("#1E293B"))
                }
                val mView = TextView(this).apply {
                    text = entry.meaning
                    textSize = 12f
                    setTextColor(Color.parseColor("#475569"))
                }
                textCol.addView(wView)
                textCol.addView(mView)

                val alreadyInSaved = entry.word.lowercase() in savedWordBoxes.let { _ -> emptySet<String>() }
                val addBtn = TextView(this).apply {
                    text = "＋生词"
                    textSize = 11.5f
                    setTextColor(Color.parseColor("#1D4ED8"))
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#EFF6FF"))
                        cornerRadius = dp(8).toFloat()
                    }
                    serviceScope.launch {
                        val exists = withContext(Dispatchers.IO) {
                            vocabDb.vocabularyDao().exists(entry.word)
                        }
                        if (exists) {
                            text = "✓已存"
                            setTextColor(Color.parseColor("#047857"))
                        }
                    }
                    setOnClickListener {
                        serviceScope.launch {
                            val exists = withContext(Dispatchers.IO) {
                                val dao = vocabDb.vocabularyDao()
                                val found = dao.exists(entry.word)
                                if (!found) {
                                    dao.upsert(
                                        VocabularyEntity(
                                            word = entry.word.lowercase(),
                                            phonetic = entry.phonetic,
                                            meaning = entry.meaning,
                                            category = entry.category
                                        )
                                    )
                                }
                                found
                            }
                            text = "✓已存"
                            setTextColor(Color.parseColor("#047857"))
                            if (!exists) {
                                VocabWidgetProvider.refreshNow(this@ScreenLookupAccessibilityService)
                            }
                        }
                    }
                }
                itemRow.addView(textCol)
                itemRow.addView(addBtn)
                card.addView(itemRow)
            }
        }

        val cardWidth = dp(310)
        val params = WindowManager.LayoutParams(
            cardWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(96)
        }
        popupCardView = card
        windowManager.addView(card, params)
    }

    private fun showWordPopupCard(rawWord: String, sentence: String, touchX: Int, touchY: Int) {
        removeViewSafe(popupCardView)
        val entry = LocalDictionary.lookup(rawWord, sentence)
        val isDark = AppPreferences.isDarkMode(this)
        var noteDirty = false

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(if (isDark) "#1E293B" else "#FFFFFF"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor(if (isDark) "#334155" else "#E0E3E7"))
            }
            elevation = dp(10).toFloat()
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleText = if (entry.baseForm != null) "${entry.word}  (原形: ${entry.baseForm})" else entry.word
        val wordView = TextView(this).apply {
            text = titleText
            textSize = 17f
            setTextColor(Color.parseColor(if (isDark) "#F8FAFC" else "#1F1F1F"))
        }
        titleColumn.addView(wordView)

        if (entry.phonetic.isNotEmpty()) {
            val phoneticView = TextView(this).apply {
                text = entry.phonetic
                textSize = 12f
                setTextColor(Color.parseColor("#5F6368"))
            }
            titleColumn.addView(phoneticView)
        }

        val speakBtn = TextView(this).apply {
            text = "🔊"
            textSize = 15f
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setOnClickListener {
                if (ttsReady) {
                    tts?.speak(entry.word, TextToSpeech.QUEUE_FLUSH, null, "lookup_tts")
                } else {
                    Toast.makeText(this@ScreenLookupAccessibilityService, "语音引擎初始化中", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val copyOneBtn = TextView(this).apply {
            text = "📋"
            textSize = 15f
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setOnClickListener {
                val phoneticPart = if (entry.phonetic.isNotEmpty()) " ${entry.phonetic}" else ""
                val sentPart = if (sentence.isNotBlank()) "\n例句：$sentence" else ""
                val text = "${entry.word}$phoneticPart — ${entry.meaning}$sentPart"
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("WordLookup", text))
                Toast.makeText(this@ScreenLookupAccessibilityService, "已复制 ${entry.word}", Toast.LENGTH_SHORT).show()
            }
        }

        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(Color.parseColor("#5F6368"))
            setPadding(dp(6), dp(4), dp(4), dp(4))
            setOnClickListener { removeViewSafe(popupCardView) }
        }

        headerRow.addView(titleColumn)
        headerRow.addView(speakBtn)
        headerRow.addView(copyOneBtn)
        headerRow.addView(closeBtn)
        card.addView(headerRow)

        entry.posHint?.let { hint ->
            val posView = TextView(this).apply {
                text = hint
                textSize = 11.5f
                setTextColor(Color.parseColor("#3949AB"))
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#EEF2FF"))
                    cornerRadius = dp(8).toFloat()
                }
            }
            card.addView(posView)
        }

        val meaningView = TextView(this).apply {
            text = entry.meaning
            textSize = 14f
            setTextColor(Color.parseColor(if (isDark) "#E2E8F0" else "#3C4043"))
            setPadding(0, dp(8), 0, dp(6))
        }
        card.addView(meaningView)

        if (sentence.isNotBlank()) {
            val sentenceRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(8))
            }
            val sentenceView = TextView(this).apply {
                text = "原句：$sentence"
                textSize = 11.5f
                setTextColor(Color.parseColor("#5F6368"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val clauseView = TextView(this).apply {
                text = LocalDictionary.analyzeSentenceClauses(sentence)
                textSize = 11f
                setTextColor(Color.parseColor("#3730A3"))
                setPadding(dp(8), dp(6), dp(8), dp(6))
                visibility = View.GONE
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#EEF2FF"))
                    cornerRadius = dp(8).toFloat()
                }
            }
            val transSentBtn = TextView(this).apply {
                text = "语法拆解/译整句"
                textSize = 11f
                setTextColor(Color.parseColor("#1A73E8"))
                setPadding(dp(8), dp(4), dp(4), dp(4))
                setOnClickListener {
                    if (clauseView.visibility == View.GONE) {
                        clauseView.visibility = View.VISIBLE
                        card.post {
                            val lp = card.layoutParams as? WindowManager.LayoutParams ?: return@post
                            val bottomSafe = screenHeight - dp(68)
                            if (lp.y + card.height > bottomSafe) {
                                lp.y = (bottomSafe - card.height).coerceAtLeast(dp(56))
                                windowManager.updateViewLayout(card, lp)
                            }
                        }
                    } else {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://translate.google.com/?sl=en&tl=zh-CN&text=${Uri.encode(sentence)}")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { startActivity(intent) }
                    }
                }
            }
            sentenceRow.addView(sentenceView)
            sentenceRow.addView(transSentBtn)
            card.addView(sentenceRow)
            card.addView(clauseView)
        }

        val noteInput = EditText(this).apply {
            hint = "✏️ 添加词根/谐音/考点助记笔记..."
            textSize = 12f
            setTextColor(Color.parseColor("#334155"))
            setHintTextColor(Color.parseColor("#94A3B8"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFFBEB"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#FDE68A"))
            }
            setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    val lp = card.layoutParams as? WindowManager.LayoutParams
                    if (lp != null && (lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) {
                        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                        windowManager.updateViewLayout(card, lp)
                        v.requestFocus()
                    }
                }
                false
            }
        }
        card.addView(noteInput)

        val saveBtn = Button(this).apply {
            text = "＋ 加入生词本"
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A73E8"))
                cornerRadius = dp(10).toFloat()
            }
        }
        card.addView(saveBtn)

        var isCurrentlySaved = false
        var isProgrammaticNoteUpdate = false

        serviceScope.launch {
            val existingEntity = withContext(Dispatchers.IO) {
                val dao = vocabDb.vocabularyDao()
                val existing = dao.getByWord(entry.word)
                if (existing != null) dao.incrementLookupCount(entry.word)
                existing
            }
            isCurrentlySaved = existingEntity != null
            if (existingEntity != null && existingEntity.note.isNotEmpty()) {
                isProgrammaticNoteUpdate = true
                noteInput.setText(existingEntity.note)
                isProgrammaticNoteUpdate = false
                noteDirty = false
            }
            updateSaveButtonState(saveBtn, isCurrentlySaved, noteDirty)
        }

        noteInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isProgrammaticNoteUpdate) return
                val newNote = s?.toString()?.trim().orEmpty()
                noteDirty = true
                if (isCurrentlySaved) {
                    serviceScope.launch(Dispatchers.IO) {
                        vocabDb.vocabularyDao().updateNote(entry.word, newNote)
                    }
                }
                updateSaveButtonState(saveBtn, isCurrentlySaved, noteDirty)
            }
        })

        saveBtn.setOnClickListener {
            serviceScope.launch {
                val currentNote = noteInput.text?.toString()?.trim().orEmpty()
                if (isCurrentlySaved && noteDirty) {
                    withContext(Dispatchers.IO) {
                        vocabDb.vocabularyDao().updateNote(entry.word, currentNote)
                    }
                    noteDirty = false
                    updateSaveButtonState(saveBtn, true, false)
                    Toast.makeText(this@ScreenLookupAccessibilityService, "已保存助记笔记", Toast.LENGTH_SHORT).show()
                    if (AppPreferences.isAutoExitAfterSave(this@ScreenLookupAccessibilityService)) {
                        clearCapturedBitmaps()
                        removeViewSafe(captureOverlayView)
                        removeViewSafe(popupCardView)
                    }
                    return@launch
                }

                val nowSaved = withContext(Dispatchers.IO) {
                    val dao = vocabDb.vocabularyDao()
                    val exists = dao.exists(entry.word)
                    if (exists) {
                        dao.deleteByWord(entry.word)
                        false
                    } else {
                        dao.upsert(
                            VocabularyEntity(
                                word = entry.word.lowercase(),
                                phonetic = entry.phonetic,
                                meaning = entry.meaning,
                                sentence = sentence,
                                category = entry.category,
                                note = currentNote,
                                lookupCount = 1,
                                mastery = "new"
                            )
                        )
                        true
                    }
                }
                isCurrentlySaved = nowSaved
                noteDirty = false
                updateSaveButtonState(saveBtn, nowSaved, false)
                VocabWidgetProvider.refreshNow(this@ScreenLookupAccessibilityService)
                if (nowSaved && AppPreferences.isAutoExitAfterSave(this@ScreenLookupAccessibilityService)) {
                    clearCapturedBitmaps()
                    removeViewSafe(captureOverlayView)
                    removeViewSafe(popupCardView)
                }
            }
        }

        val cardWidth = dp(292)
        card.measure(
            View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((screenHeight * 0.6f).toInt(), View.MeasureSpec.AT_MOST)
        )
        val cardHeight = card.measuredHeight
        val topSafe = dp(56)
        val bottomSafe = screenHeight - dp(68)
        val candidateY = if (touchY + dp(28) + cardHeight <= bottomSafe) {
            touchY + dp(28)
        } else {
            touchY - cardHeight - dp(20)
        }
        val clampedY = candidateY.coerceIn(topSafe, (bottomSafe - cardHeight).coerceAtLeast(topSafe))

        val params = WindowManager.LayoutParams(
            cardWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            x = (touchX - cardWidth / 2).coerceIn(dp(16), screenWidth - cardWidth - dp(16))
            y = clampedY
        }

        popupCardView = card
        windowManager.addView(card, params)
    }

    private fun updateSaveButtonState(button: Button, saved: Boolean, noteDirty: Boolean = false) {
        if (saved && noteDirty) {
            button.text = "💾 保存助记笔记修改"
            button.setTextColor(Color.WHITE)
            button.background = GradientDrawable().apply {
                setColor(Color.parseColor("#D97706"))
                cornerRadius = dp(10).toFloat()
            }
        } else if (saved) {
            button.text = "✓ 已加入生词本（点击移除）"
            button.setTextColor(Color.parseColor("#137333"))
            button.background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6F4EA"))
                cornerRadius = dp(10).toFloat()
            }
        } else {
            button.text = "＋ 加入生词本"
            button.setTextColor(Color.WHITE)
            button.background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A73E8"))
                cornerRadius = dp(10).toFloat()
            }
        }
    }

    private fun removeViewSafe(view: View?) {
        if (view != null && view.isAttachedToWindow) {
            runCatching { windowManager.removeView(view) }
        }
    }
}
r