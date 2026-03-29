package com.surendramaran.yolov8tflite

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.surendramaran.yolov8tflite.MetaData.extractNamesFromLabelFile
import com.surendramaran.yolov8tflite.MetaData.extractNamesFromMetadata
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer

class Detector(
    private val context: Context,
    private val modelPath: String,
    private val labelPath: String?,
    private val detectorListener: DetectorListener,
    private val message: (String) -> Unit
) {
    private var interpreter: Interpreter? = null
    private var isClosed = false
    private var labels = mutableListOf<String>()
    private var tensorWidth = 0
    private var tensorHeight = 0
    private var numRows = 0
    private var numCols = 0
    private var isTransposed = false

    private val imageProcessor = ImageProcessor.Builder()
        .add(NormalizeOp(INPUT_MEAN, INPUT_STANDARD_DEVIATION))
        .add(CastOp(INPUT_IMAGE_TYPE))
        .build()

    init {
        val options = Interpreter.Options().apply {
            val compatList = CompatibilityList()
            if (compatList.isDelegateSupportedOnThisDevice) {
                this.addDelegate(GpuDelegate(compatList.bestOptionsForThisDevice))
            } else {
                this.setNumThreads(4)
            }
        }

        try {
            val model = FileUtil.loadMappedFile(context, modelPath)
            interpreter = Interpreter(model, options)
            interpreter?.let { tflite ->
                val inputShape = tflite.getInputTensor(0)?.shape()
                val outputShape = tflite.getOutputTensor(0)?.shape()

                // Resolve labels: Metadata -> Label File -> Hardcoded Fallback
                val metadataLabels = extractNamesFromMetadata(model)
                if (metadataLabels.isNotEmpty()) {
                    labels.addAll(metadataLabels)
                } else if (labelPath != null) {
                    val fileLabels = extractNamesFromLabelFile(context, labelPath)
                    if (fileLabels.isNotEmpty()) {
                        labels.addAll(fileLabels)
                    }
                }

                if (labels.isEmpty()) {
                    labels.addAll(MetaData.TEMP_CLASSES)
                }

                if (inputShape != null) {
                    tensorWidth = inputShape[1]
                    tensorHeight = inputShape[2]
                    if (inputShape[1] == 3) {
                        tensorWidth = inputShape[2]
                        tensorHeight = inputShape[3]
                    }
                }
                if (outputShape != null) {
                    if (outputShape[1] > outputShape[2]) {
                        // Newer YOLOv8 shape: [1, 8400, 84]
                        numRows = outputShape[1]
                        numCols = outputShape[2]
                        isTransposed = false
                    } else {
                        // Older YOLOv8 shape: [1, 84, 8400]
                        numCols = outputShape[1]
                        numRows = outputShape[2]
                        isTransposed = true
                    }
                }
            }
        } catch (e: Exception) {
            message("Init Error: ${e.message}")
        }
    }

    fun close() {
        isClosed = true
        interpreter?.close()
        interpreter = null
    }

    fun detect(frame: Bitmap) {
        if (isClosed || interpreter == null || tensorWidth == 0) return
        var inferenceTime = SystemClock.uptimeMillis()
        try {
            val resizedBitmap = Bitmap.createScaledBitmap(frame, tensorWidth, tensorHeight, false)
            val tensorImage = TensorImage(INPUT_IMAGE_TYPE)
            tensorImage.load(resizedBitmap)
            val processedImage = imageProcessor.process(tensorImage)
            // Ensure the Java buffer shape exactly matches the model's expected tensor shape
            val expectedShape = if (isTransposed) intArrayOf(1, numCols, numRows) else intArrayOf(1, numRows, numCols)
            val output = TensorBuffer.createFixedSize(expectedShape, OUTPUT_IMAGE_TYPE)
            interpreter?.run(processedImage.buffer, output.buffer) ?: return

            val bestBoxes = bestBox(output.floatArray)
            inferenceTime = SystemClock.uptimeMillis() - inferenceTime

            if (bestBoxes == null) {
                detectorListener.onEmptyDetect()
            } else {
                // Pass the frame used for detection back to listener to avoid re-capturing
                detectorListener.onDetect(bestBoxes, inferenceTime, frame)
            }
        } catch (e: Exception) {
            Log.e("Detector", "Detect Error: ${e.message}")
        }
    }

    private fun bestBox(array: FloatArray): List<BoundingBox>? {
        val boundingBoxes = mutableListOf<BoundingBox>()

        for (r in 0 until numRows) {
            val cx: Float
            val cy: Float
            val w: Float
            val h: Float

            // 1. Handle dynamic tensor shapes automatically
            if (isTransposed) {
                cx = array[0 * numRows + r]
                cy = array[1 * numRows + r]
                w = array[2 * numRows + r]
                h = array[3 * numRows + r]
            } else {
                cx = array[r * numCols + 0]
                cy = array[r * numCols + 1]
                w = array[r * numCols + 2]
                h = array[r * numCols + 3]
            }

            var maxConf = -1.0f
            var maxIdx = -1

            for (j in 4 until numCols) {
                val conf = if (isTransposed) array[j * numRows + r] else array[r * numCols + j]
                if (conf > maxConf) {
                    maxConf = conf
                    maxIdx = j - 4
                }
            }

            // FIX 1: Convert to decimal BEFORE checking the threshold
            val normalizedConf = if (maxConf > 1.0f) maxConf / 100.0f else maxConf

            // Check against the threshold using the proper 0.0 to 1.0 decimal
            if (normalizedConf > CONFIDENCE_THRESHOLD) {
                val clsName = labels.getOrElse(maxIdx) { "Class #$maxIdx" }

                val scaleX = if (cx > 1.5f || w > 1.5f) tensorWidth.toFloat() else 1f
                val scaleY = if (cy > 1.5f || h > 1.5f) tensorHeight.toFloat() else 1f

                val x1 = (cx - (w / 2F)) / scaleX
                val y1 = (cy - (h / 2F)) / scaleY
                val x2 = (cx + (w / 2F)) / scaleX
                val y2 = (cy + (h / 2F)) / scaleY

                // FIX 2: Check for 5% of the screen (0.05f), NOT 50% (0.5f)
                if (w * h >= 0.05f) {
                    // Note: We are passing 'normalizedConf' here now instead of 'maxConf'
                    boundingBoxes.add(BoundingBox(x1, y1, x2, y2, cx, cy, w, h, normalizedConf, maxIdx, clsName))
                }
            }
        }
        return if (boundingBoxes.isEmpty()) null else applyNMS(boundingBoxes)
    }

    private fun applyNMS(boxes: List<BoundingBox>) : MutableList<BoundingBox> {
        val sortedBoxes = boxes.sortedByDescending { it.cnf }.toMutableList()
        val selectedBoxes = mutableListOf<BoundingBox>()
        while(sortedBoxes.isNotEmpty()) {
            val first = sortedBoxes.first()
            selectedBoxes.add(first)
            sortedBoxes.remove(first)
            val iterator = sortedBoxes.iterator()
            while (iterator.hasNext()) {
                val nextBox = iterator.next()
                if (calculateIoU(first, nextBox) >= IOU_THRESHOLD) iterator.remove()
            }
        }
        return selectedBoxes
    }

    private fun calculateIoU(box1: BoundingBox, box2: BoundingBox): Float {
        val x1 = maxOf(box1.x1, box2.x1)
        val y1 = maxOf(box1.y1, box2.y1)
        val x2 = minOf(box1.x2, box2.x2)
        val y2 = minOf(box1.y2, box2.y2)
        val intersectionArea = maxOf(0F, x2 - x1) * maxOf(0F, y2 - y1)
        val box1Area = (box1.x2 - box1.x1) * (box1.y2 - box1.y1)
        val box2Area = (box2.x2 - box2.x1) * (box2.y2 - box2.y1)
        val union = box1Area + box2Area - intersectionArea
        return if (union <= 0) 0f else intersectionArea / union
    }

    interface DetectorListener {
        fun onEmptyDetect()
        fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long, frame: Bitmap)
    }

    companion object {
        private const val INPUT_MEAN = 0f
        private const val INPUT_STANDARD_DEVIATION = 255f
        private val INPUT_IMAGE_TYPE = DataType.FLOAT32
        private val OUTPUT_IMAGE_TYPE = DataType.FLOAT32

        // Change from 0.8F to 0.5F (Only show boxes 50% or more confident)
        private const val CONFIDENCE_THRESHOLD = 0.75F

        // Change from 0.5F to 0.35F (Delete overlapping boxes more aggressively)
        private const val IOU_THRESHOLD = 0.5F
    }
}
