package com.example.pisurveillance.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.example.pisurveillance.models.FaceDetectionResult

/**
 * Custom view for drawing face detection boxes and labels over the video feed
 */
class FaceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var faceResult: FaceDetectionResult? = null
    
    private val boxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    
    private val labelPaint = Paint().apply {
        color = Color.GREEN
        textSize = 40f
        style = Paint.Style.FILL
    }
    
    private val labelBgPaint = Paint().apply {
        color = Color.BLACK
        alpha = 160
        style = Paint.Style.FILL
    }

    /**
     * Update the detections to draw
     */
    fun setFaceData(result: FaceDetectionResult?) {
        faceResult = result
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val result = faceResult ?: return
        if (result.faces.isEmpty()) return

        val scaleX = width.toFloat() / result.imageWidth
        val scaleY = height.toFloat() / result.imageHeight

        for (face in result.faces) {
            val left = face.left * scaleX
            val top = face.top * scaleY
            val right = face.right * scaleX
            val bottom = face.bottom * scaleY

            val rect = RectF(left, top, right, bottom)
            
            // Known vs Unknown colors
            val isKnown = face.name != null && face.name != "Unknown"
            val color = if (isKnown) Color.GREEN else Color.YELLOW
            boxPaint.color = color
            labelPaint.color = color

            // Draw box
            canvas.drawRect(rect, boxPaint)

            // Draw label
            val confidence = if (face.confidence != null) " (${(face.confidence * 100).toInt()}%)" else ""
            val label = "${face.name ?: "Unknown"}$confidence"
            
            val textWidth = labelPaint.measureText(label)
            val textHeight = labelPaint.fontMetrics.bottom - labelPaint.fontMetrics.top
            
            val labelRect = RectF(
                left,
                top - textHeight,
                left + textWidth + 20f,
                top
            )
            
            canvas.drawRect(labelRect, labelBgPaint)
            canvas.drawText(label, left + 10f, top - 10f, labelPaint)
        }
    }
}
