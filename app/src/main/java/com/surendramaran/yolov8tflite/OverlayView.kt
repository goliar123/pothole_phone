package com.surendramaran.yolov8tflite

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import java.util.LinkedList

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var results = LinkedList<BoundingBox>()

    // The upgraded paint toolkit
    private var boxPaint = Paint()
    private var fillPaint = Paint()
    private var textBackgroundPaint = Paint()
    private var textPaint = Paint()

    private var bounds = Rect()

    init {
        initPaints()
    }

    fun clear() {
        textPaint.reset()
        textBackgroundPaint.reset()
        boxPaint.reset()
        fillPaint.reset()
        invalidate()
        initPaints()
    }

    private fun initPaints() {
        val mainColor = Color.parseColor("#FF5722") // A premium Deep Orange

        // 1. The main outline
        boxPaint.color = mainColor
        boxPaint.style = Paint.Style.STROKE
        boxPaint.strokeWidth = 8f
        boxPaint.strokeJoin = Paint.Join.ROUND
        boxPaint.strokeCap = Paint.Cap.ROUND
        boxPaint.isAntiAlias = true // Smooths out the jagged edges

        // 2. The semi-transparent highlight inside the box
        fillPaint.color = Color.parseColor("#33FF5722") // 20% opacity of the main color
        fillPaint.style = Paint.Style.FILL
        fillPaint.isAntiAlias = true

        // 3. The pill background for the text
        textBackgroundPaint.color = mainColor
        textBackgroundPaint.style = Paint.Style.FILL
        textBackgroundPaint.isAntiAlias = true

        // 4. The typography
        textPaint.color = Color.WHITE
        textPaint.style = Paint.Style.FILL
        textPaint.textSize = 46f
        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.isAntiAlias = true
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        for (result in results) {
            // Convert relative coordinates (0..1) to actual screen pixels
            val left = result.x1 * width
            val top = result.y1 * height
            val right = result.x2 * width
            val bottom = result.y2 * height

            val rect = RectF(left, top, right, bottom)
            val cornerRadius = 16f

            // 1. Draw the semi-transparent highlight fill
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)

            // 2. Draw the bold outline
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, boxPaint)

            // 3. Draw the Text Pill
            val drawableText = result.clsName
            textPaint.getTextBounds(drawableText, 0, drawableText.length, bounds)
            val textWidth = bounds.width()
            val textHeight = bounds.height()

            // Calculate padding so the text isn't crammed
            val paddingX = 24f
            val paddingY = 16f

            // Smart placement: Float above the box, but if it hits the top of the screen, move it inside
            val pillTop = if (top - textHeight - (paddingY * 2) > 0) {
                top - textHeight - (paddingY * 2) - 8f
            } else {
                top + 8f
            }

            val textRect = RectF(
                left,
                pillTop,
                left + textWidth + (paddingX * 2),
                pillTop + textHeight + (paddingY * 2)
            )

            // Draw the background pill
            canvas.drawRoundRect(textRect, 12f, 12f, textBackgroundPaint)

            // 4. Draw the label inside the pill
            canvas.drawText(
                drawableText,
                left + paddingX,
                pillTop + textHeight + paddingY - 4f, // Adjust baseline slightly to perfectly center
                textPaint
            )
        }
    }

    fun setResults(boundingBoxes: List<BoundingBox>) {
        results = LinkedList(boundingBoxes)
        // Trigger a redraw of the view
        invalidate()
    }
}