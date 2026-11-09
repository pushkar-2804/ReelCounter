package com.reelcounter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Stacked bar chart of the 24 hours of a day: Instagram at the bottom, YouTube on top. */
class HourlyChartView(context: Context) : View(context) {

    private var instagram = IntArray(24)
    private var youtube = IntArray(24)

    private val density = resources.displayMetrics.density
    private val igPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_INSTAGRAM }
    private val ytPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_YOUTUBE }
    private val gridPaint = Paint().apply {
        color = 0x22000000
        strokeWidth = density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF666666.toInt()
        textSize = 10 * density
        textAlign = Paint.Align.CENTER
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF222222.toInt()
        textSize = 9 * density
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    fun setData(hourly: Map<Source, IntArray>) {
        instagram = hourly.getValue(Source.INSTAGRAM)
        youtube = hourly.getValue(Source.YOUTUBE)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (180 * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val labelSpace = 16 * density
        val valueSpace = 12 * density
        val chartTop = valueSpace
        val chartBottom = height - labelSpace
        val chartHeight = chartBottom - chartTop
        val slot = width / 24f
        val barWidth = slot * 0.7f
        val maxValue = maxOf(1, (0 until 24).maxOf { instagram[it] + youtube[it] })

        canvas.drawLine(0f, chartBottom, width.toFloat(), chartBottom, gridPaint)

        for (h in 0 until 24) {
            val left = h * slot + (slot - barWidth) / 2
            val igH = instagram[h] * chartHeight / maxValue
            val ytH = youtube[h] * chartHeight / maxValue

            rect.set(left, chartBottom - igH, left + barWidth, chartBottom)
            if (igH > 0) canvas.drawRect(rect, igPaint)
            rect.set(left, chartBottom - igH - ytH, left + barWidth, chartBottom - igH)
            if (ytH > 0) canvas.drawRect(rect, ytPaint)

            val total = instagram[h] + youtube[h]
            if (total > 0) {
                canvas.drawText(
                    total.toString(), left + barWidth / 2, chartBottom - igH - ytH - 2 * density, valuePaint
                )
            }
            if (h % 3 == 0) {
                canvas.drawText(h.toString(), h * slot + slot / 2, height - 4 * density, labelPaint)
            }
        }
    }

    companion object {
        const val COLOR_INSTAGRAM = 0xFFE1306C.toInt()
        const val COLOR_YOUTUBE = 0xFFFF0000.toInt()
    }
}
