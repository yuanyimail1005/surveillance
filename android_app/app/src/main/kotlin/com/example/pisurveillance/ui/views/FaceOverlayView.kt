package com.example.pisurveillance.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.example.pisurveillance.models.FaceDetectionResult
import kotlin.math.min

/**
 * Custom view for drawing face detection boxes and labels over the video feed
 */
class FaceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var faceResult: FaceDetectionResult? = null
    private val pendingFaceResults = mutableMapOf<Long, FaceDetectionResult>()
    
    private var lastDetectionTimestamp: Long = 0
    private var lastVideoFrameTimestamp: Long = 0
    private var currentFrameSeq: Long = 0
    
    // Threshold to clear overlay if no updates received (matching index.html)
    private val STALE_THRESHOLD_MS = 2000L
    
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
     * Buffer face data by its sequence number for synchronization
     */
    fun setFaceData(result: FaceDetectionResult?) {
        if (result == null) {
            faceResult = null
            pendingFaceResults.clear()
            invalidate()
            return
        }
        
        val seq = result.broadcastFrameSeq ?: 0
        if (seq <= 0) {
            // Fallback for non-sequenced data
            faceResult = result
            lastDetectionTimestamp = System.currentTimeMillis()
        } else {
            pendingFaceResults[seq] = result
            maybeApplyFaceResult()
        }
        invalidate()
    }

    /**
     * Notify that a new video frame has been displayed
     */
    fun notifyVideoFrameReceived(seq: Long) {
        lastVideoFrameTimestamp = System.currentTimeMillis()
        currentFrameSeq = seq
        maybeApplyFaceResult()
        invalidate()
    }

    private fun maybeApplyFaceResult() {
        if (currentFrameSeq <= 0) return

        // Find result matching current frame or the most recent one that isn't from the future
        val matchingSeq = pendingFaceResults.keys
            .filter { it <= currentFrameSeq }
            .maxOrNull()

        if (matchingSeq != null) {
            faceResult = pendingFaceResults[matchingSeq]
            lastDetectionTimestamp = System.currentTimeMillis()
            
            // Cleanup older results
            val toRemove = pendingFaceResults.keys.filter { it < matchingSeq }
            toRemove.forEach { pendingFaceResults.remove(it) }
        } else {
            // If no results for current or past sequences, check if the current one is too old
            val resultSeq = faceResult?.broadcastFrameSeq ?: 0
            // If we are more than 5 frames ahead of the last box, clear it (no "ghost" boxes)
            if (resultSeq > 0 && currentFrameSeq - resultSeq > 5) {
                faceResult = null
            }
        }
    }

    /**
     * Get the current face detection result being displayed
     */
    fun getCurrentFaceResult(): FaceDetectionResult? {
        val now = System.currentTimeMillis()
        if (now - lastDetectionTimestamp > STALE_THRESHOLD_MS) return null
        if (lastVideoFrameTimestamp > 0 && now - lastVideoFrameTimestamp > STALE_THRESHOLD_MS) return null
        return faceResult
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val result = getCurrentFaceResult() ?: return
        drawDetections(canvas, width, height, result, isSnapshot = false)
    }

    /**
     * Shared logic to draw detections onto any canvas (View or Snapshot)
     */
    fun drawDetections(
        canvas: Canvas, 
        targetWidth: Int, 
        targetHeight: Int, 
        result: FaceDetectionResult,
        isSnapshot: Boolean = true
    ) {
        if (result.faces.isEmpty()) return

        var scaleX: Float
        var scaleY: Float
        var offsetX = 0f
        var offsetY = 0f

        if (isSnapshot) {
            // On snapshot, coordinates are 1:1 with the bitmap
            scaleX = targetWidth.toFloat() / result.imageWidth
            scaleY = targetHeight.toFloat() / result.imageHeight
        } else {
            // On UI Overlay (View), we must account for 'fitCenter' scaling of the ImageView
            val viewAspect = targetWidth.toFloat() / targetHeight
            val imageAspect = result.imageWidth.toFloat() / result.imageHeight

            if (imageAspect > viewAspect) {
                // Image is wider than view (Pillarboxing)
                scaleX = targetWidth.toFloat() / result.imageWidth
                scaleY = scaleX
                offsetY = (targetHeight - (result.imageHeight * scaleY)) / 2f
            } else {
                // Image is taller than view (Letterboxing)
                scaleY = targetHeight.toFloat() / result.imageHeight
                scaleX = scaleY
                offsetX = (targetWidth - (result.imageWidth * scaleX)) / 2f
            }
        }
        
        // Adjust text size based on scale to keep it readable on high-res snapshots
        val originalTextSize = labelPaint.textSize
        if (targetWidth > width) {
            labelPaint.textSize = originalTextSize * (targetWidth.toFloat() / width.coerceAtLeast(1))
        }

        for (face in result.faces) {
            val left = offsetX + (face.left * scaleX)
            val top = offsetY + (face.top * scaleY)
            val right = offsetX + (face.right * scaleX)
            val bottom = offsetY + (face.bottom * scaleY)

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
            val fontMetrics = labelPaint.fontMetrics
            val textHeight = fontMetrics.bottom - fontMetrics.top
            
            val labelRect = RectF(
                left,
                top - textHeight,
                left + textWidth + 20f,
                top
            )
            
            canvas.drawRect(labelRect, labelBgPaint)
            canvas.drawText(label, left + 10f, top - fontMetrics.bottom, labelPaint)
        }
        
        // Restore text size
        labelPaint.textSize = originalTextSize
    }
}
