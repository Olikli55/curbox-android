package neth.iecal.curbox.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors

class WeeklyBarGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class ZoomLevel {
        DAYS,
        WEEKS
    }

    data class BarData(
        val label: String,
        val value: Float,
        val dateMillis: Long
    )

    private var entries: List<BarData> = emptyList()
    private var selectedIndex: Int = -1
    private var onBarSelected: ((BarData) -> Unit)? = null
    private var onZoomLevelChanged: ((ZoomLevel) -> Unit)? = null
    private var zoomLevel = ZoomLevel.DAYS
    private var zoomEnabled = false
    private var awaitingZoomData = false
    private var accumulatedScale = 1f
    private var hadMultiplePointers = false
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                accumulatedScale = 1f
                hadMultiplePointers = true
                return zoomEnabled
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!zoomEnabled || awaitingZoomData) return false
                accumulatedScale *= detector.scaleFactor
                val nextLevel = when {
                    accumulatedScale <= ZOOM_OUT_THRESHOLD -> ZoomLevel.WEEKS
                    accumulatedScale >= ZOOM_IN_THRESHOLD -> ZoomLevel.DAYS
                    else -> return true
                }
                if (nextLevel != zoomLevel) {
                    zoomLevel = nextLevel
                    awaitingZoomData = true
                    onZoomLevelChanged?.invoke(nextLevel)
                }
                return true
            }
        }
    )

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectedBarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val selectedLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val barRadius = 8f
    private val barRect = RectF()
    private val minBarHeight = 6f // dp

    init {
        isClickable = true
        isFocusable = true
    }

    fun setData(data: List<BarData>, selected: Int = -1) {
        entries = data
        awaitingZoomData = false
        selectedIndex = selected
        resolveColors()
        invalidate()
    }

    fun setOnBarSelectedListener(listener: (BarData) -> Unit) {
        onBarSelected = listener
    }

    fun enableZoom(
        initialLevel: ZoomLevel = ZoomLevel.DAYS,
        listener: (ZoomLevel) -> Unit
    ) {
        zoomEnabled = true
        zoomLevel = initialLevel
        onZoomLevelChanged = listener
    }

    fun setZoomLevel(level: ZoomLevel) {
        zoomLevel = level
    }

    fun setSelectedIndex(index: Int) {
        selectedIndex = index
        invalidate()
    }

    private fun resolveColors() {
        val colorPrimary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
        val colorOnSurfaceVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)

        barPaint.color = ColorUtils.setAlphaComponent(colorPrimary, 192)
        selectedBarPaint.color = colorPrimary
        labelPaint.color = colorOnSurfaceVariant
        labelPaint.textSize = 11f * resources.displayMetrics.density
        selectedLabelPaint.color = colorPrimary
        selectedLabelPaint.textSize = labelPaint.textSize
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resolveColors()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (180 * resources.displayMetrics.density).toInt()
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (entries.isEmpty()) return

        val density = resources.displayMetrics.density
        val count = entries.size
        val labelAreaHeight = 28f * density  // space for day labels
        val titleAreaHeight = 24f * density  // space for "WEEKLY ACTIVITY"
        val topPadding = 16f * density
        val bottomPadding = 8f * density

        val availableWidth = width.toFloat() - paddingStart - paddingEnd
        val barAreaWidth = availableWidth * 0.85f
        val leftOffset = (availableWidth - barAreaWidth) / 2f + paddingStart

        val barWidth = barAreaWidth / count * 0.35f
        val gapWidth = barAreaWidth / count

        val chartTop = topPadding
        val chartBottom = height - labelAreaHeight - titleAreaHeight - bottomPadding
        val maxBarHeight = chartBottom - chartTop
        val maxValue = entries.maxOfOrNull { it.value } ?: 1f
        val effectiveMax = if (maxValue == 0f) 1f else maxValue

        val minBarPx = minBarHeight * density

        for (i in entries.indices) {
            val cx = leftOffset + gapWidth * i + gapWidth / 2f
            val ratio = entries[i].value / effectiveMax
            var barHeight = maxBarHeight * ratio
            if (entries[i].value > 0f && barHeight < minBarPx) barHeight = minBarPx

            val left = cx - barWidth / 2f
            val right = cx + barWidth / 2f
            val top = chartBottom - barHeight
            val bottom = chartBottom

            barRect.set(left, top, right, bottom)
            val paint = if (i == selectedIndex) selectedBarPaint else barPaint
            canvas.drawRoundRect(barRect, barRadius * density, barRadius * density, paint)
        }

        val labelY = chartBottom + labelAreaHeight * 0.65f
        for (i in entries.indices) {
            val cx = leftOffset + gapWidth * i + gapWidth / 2f
            val paint = if (i == selectedIndex) selectedLabelPaint else labelPaint
            canvas.drawText(entries[i].label, cx, labelY, paint)
        }

    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (zoomEnabled) scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                hadMultiplePointers = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                hadMultiplePointers = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                val moved = kotlin.math.abs(event.x - downX) > touchSlop ||
                    kotlin.math.abs(event.y - downY) > touchSlop
                if (!hadMultiplePointers && !moved && !awaitingZoomData) {
                    selectBarAt(event.x)
                    performClick()
                }
            }

            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun selectBarAt(touchX: Float) {
        if (entries.isEmpty()) return
        val availableWidth = width.toFloat() - paddingStart - paddingEnd
        val barAreaWidth = availableWidth * 0.85f
        val leftOffset = (availableWidth - barAreaWidth) / 2f + paddingStart
        val gapWidth = barAreaWidth / entries.size

        for (i in entries.indices) {
            val centerX = leftOffset + gapWidth * i + gapWidth / 2f
            if (touchX in centerX - gapWidth / 2f..centerX + gapWidth / 2f) {
                selectedIndex = i
                invalidate()
                onBarSelected?.invoke(entries[i])
                return
            }
        }
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private companion object {
        const val ZOOM_OUT_THRESHOLD = 0.8f
        const val ZOOM_IN_THRESHOLD = 1.2f
    }
}
