package com.surendramaran.yolov8tflite

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.surendramaran.yolov8tflite.MetaData.extractNamesFromLabelFile
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
    private var numChannel = 0
    private var numElements = 0

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

                labels.clear()
                labels.addAll(MetaData.TEMP_CLASSES)

                if (inputShape != null) {
                    tensorWidth = inputShape[1]
                    tensorHeight = inputShape[2]
                    if (inputShape[1] == 3) {
                        tensorWidth = inputShape[2]
                        tensorHeight = inputShape[3]
                    }
                }
                if (outputShape != null) {
                    numChannel = outputShape[1]
                    numElements = outputShape[2]
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
            val output = TensorBuffer.createFixedSize(intArrayOf(1, numChannel, numElements), OUTPUT_IMAGE_TYPE)
            interpreter?.run(processedImage.buffer, output.buffer) ?: return
            val bestBoxes = bestBox(output.floatArray)
            inferenceTime = SystemClock.uptimeMillis() - inferenceTime
            if (bestBoxes == null) detectorListener.onEmptyDetect()
            else detectorListener.onDetect(bestBoxes, inferenceTime)
        } catch (e: Exception) {
            Log.e("Detector", "Detect Error: ${e.message}")
        }
    }

    private fun bestBox(array: FloatArray) : List<BoundingBox>? {
        val boundingBoxes = mutableListOf<BoundingBox>()
        for (c in 0 until numElements) {
            var maxConf = -1.0f
            var maxIdx = -1
            var j = 4
            while (j < numChannel) {
                val arrayIdx = c + numElements * j
                if (arrayIdx < array.size && array[arrayIdx] > maxConf) {
                    maxConf = array[arrayIdx]
                    maxIdx = j - 4
                }
                j++
            }

            if (maxConf > CONFIDENCE_THRESHOLD) {
                val clsName = labels.getOrElse(maxIdx) { "Class #$maxIdx" }
                val cx = array[c]
                val cy = array[c + numElements]
                val w = array[c + numElements * 2]
                val h = array[c + numElements * 3]
                
                // NORMALIZE coordinates (0.0 to 1.0)
                val x1 = (cx - (w / 2F)) / tensorWidth
                val y1 = (cy - (h / 2F)) / tensorHeight
                val x2 = (cx + (w / 2F)) / tensorWidth
                val y2 = (cy + (h / 2F)) / tensorHeight
                
                boundingBoxes.add(BoundingBox(x1, y1, x2, y2, cx, cy, w, h, maxConf, maxIdx, clsName))
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
        
        // Calculate areas using normalized coordinates
        val box1Area = (box1.x2 - box1.x1) * (box1.y2 - box1.y1)
        val box2Area = (box2.x2 - box2.x1) * (box2.y2 - box2.y1)
        
        return if (box1Area + box2Area - intersectionArea <= 0) 0f 
               else intersectionArea / (box1Area + box2Area - intersectionArea)
    }

    interface DetectorListener {
        fun onEmptyDetect()
        fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long)
    }

    companion object {
        private const val INPUT_MEAN = 0f
        private const val INPUT_STANDARD_DEVIATION = 255f
        private val INPUT_IMAGE_TYPE = DataType.FLOAT32
        private val OUTPUT_IMAGE_TYPE = DataType.FLOAT32
        private const val CONFIDENCE_THRESHOLD = 0.35F 
        private const val IOU_THRESHOLD = 0.5F
    }
}
