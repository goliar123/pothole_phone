package com.surendramaran.yolov8tflite

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import java.util.LinkedList

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var results = LinkedList<BoundingBox>()
    private var boxPaint = Paint()
    private var fillPaint = Paint()
    private var textBackgroundPaint = Paint()
    private var textPaint = Paint()
    private var bounds = Rect()

    init {
        initPaints()
    }

    fun clear() {
        results.clear()
        invalidate()
    }

    private fun initPaints() {
        val mainColor = Color.parseColor("#FF5722")

        boxPaint.color = mainColor
        boxPaint.style = Paint.Style.STROKE
        boxPaint.strokeWidth = 8f
        boxPaint.strokeJoin = Paint.Join.ROUND
        boxPaint.strokeCap = Paint.Cap.ROUND
        boxPaint.isAntiAlias = true

        fillPaint.color = Color.parseColor("#33FF5722")
        fillPaint.style = Paint.Style.FILL
        fillPaint.isAntiAlias = true

        textBackgroundPaint.color = mainColor
        textBackgroundPaint.style = Paint.Style.FILL
        textBackgroundPaint.isAntiAlias = true

        textPaint.color = Color.WHITE
        textPaint.style = Paint.Style.FILL
        textPaint.textSize = 42f
        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (result in results) {
            // Coordinate transformation logic
            // Assuming model output is 0.0 to 1.0 based on a square 640x640 input
            // We scale to the View's dimensions
            val left = result.x1 * width
            val top = result.y1 * height
            val right = result.x2 * width
            val bottom = result.y2 * height

            val rect = RectF(left, top, right, bottom)
            val cornerRadius = 16f

            // 1. Draw Highlight
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)

            // 2. Draw Outline
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, boxPaint)

            // 3. Draw Label Pill
            val drawableText = "${result.clsName} ${(result.cnf * 100).toInt()}%"
            textPaint.getTextBounds(drawableText, 0, drawableText.length, bounds)
            val textWidth = bounds.width()
            val textHeight = bounds.height()

            val paddingX = 20f
            val paddingY = 12f

            val pillTop = if (top - textHeight - (paddingY * 2) > 0) {
                top - textHeight - (paddingY * 2) - 4f
            } else {
                top + 4f
            }

            val textRect = RectF(
                left,
                pillTop,
                left + textWidth + (paddingX * 2),
                pillTop + textHeight + (paddingY * 2)
            )

            canvas.drawRoundRect(textRect, 8f, 12f, textBackgroundPaint)

            canvas.drawText(
                drawableText,
                left + paddingX,
                pillTop + textHeight + paddingY,
                textPaint
            )
        }
    }

    fun setResults(boundingBoxes: List<BoundingBox>) {
        results = LinkedList(boundingBoxes)
        postInvalidate()
    }
}
