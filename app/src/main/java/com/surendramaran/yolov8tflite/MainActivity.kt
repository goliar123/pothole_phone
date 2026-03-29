package com.surendramaran.yolov8tflite

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.surendramaran.yolov8tflite.Constants.LABELS_PATH
import com.surendramaran.yolov8tflite.Constants.MODEL_PATH
import com.surendramaran.yolov8tflite.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.tan

class MainActivity : AppCompatActivity(), Detector.DetectorListener {
    private lateinit var binding: ActivityMainBinding

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: Detector? = null
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var database: DatabaseReference
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var isDetecting = false
    private var lastInferenceTime = 0L // <--- ADD THIS LINE
    private var currentLocation: Location? = null
    private lateinit var locationCallback: LocationCallback
    private val recentDetections = mutableListOf<PotholeReport>()
    private val DB_URL = "https://pothhole-detect-default-rtdb.firebaseio.com"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // Database setup
            val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            if (currentUser != null) {
                // Path becomes: /users/USER_ID/potholes/
                database = FirebaseDatabase.getInstance(DB_URL)
                    .getReference("users")
                    .child(currentUser.uid)
                    .child("potholes")
            } else {
                // Fallback if somehow not logged in
                Toast.makeText(this, "Error: Not logged in", Toast.LENGTH_LONG).show()
                finish()
            }
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    for (location in locationResult.locations) {
                        currentLocation = location // Constantly updates with live GPS
                    }
                }
            }
            cameraExecutor = Executors.newSingleThreadExecutor()

            cameraExecutor.execute {
                try {
                    detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this) {
                        toast(it)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Detector setup failed: ${e.message}")
                }
            }

            binding.startDetectionBtn.setOnClickListener {
                if (!isDetecting) {
                    if (allPermissionsGranted()) {
                        startDetection()
                    } else {
                        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
                    }
                } else {
                    stopDetection()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Activity creation error: ${e.message}")
            finish()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDetection() {
        isDetecting = true
        binding.cameraOverlay.visibility = View.GONE
        binding.startDetectionBtn.text = "Stop Detection"
        binding.startDetectionBtn.extend()

        // --- ADD GPS REQUEST ---
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
            .setMinUpdateIntervalMillis(1000)
            .build()
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        // -----------------------

        startCamera()
    }

    private fun stopDetection() {
        isDetecting = false
        binding.cameraOverlay.visibility = View.VISIBLE
        binding.startDetectionBtn.text = "Start Detection"
        binding.startDetectionBtn.shrink()
        cameraProvider?.unbindAll()

        // --- STOP GPS TO SAVE BATTERY ---
        fusedLocationClient.removeLocationUpdates(locationCallback)
        // --------------------------------

        binding.overlay.clear()
        binding.inferenceTime.text = ""
    }
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "CameraX setup failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return
        val viewFinder = binding.viewFinder
        val rotation = viewFinder.display?.rotation ?: android.view.Surface.ROTATION_0

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->

            // --- NEW THROTTLING LOGIC ---
            val currentTime = System.currentTimeMillis()
            // 500ms means 2 frames per second. Change to 1000 for 1 frame per second.
            if (currentTime - lastInferenceTime < 500) {
                imageProxy.close() // CRITICAL: You must close the proxy or the camera freezes!
                return@setAnalyzer
            }
            lastInferenceTime = currentTime
            // ----------------------------

            // Your existing code stays exactly the same below:
            val bitmapBuffer = Bitmap.createBitmap(
                imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
            )
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height, matrix, true
            )

            detector?.detect(rotatedBitmap)
            imageProxy.close()
        }

        try {
            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            preview?.setSurfaceProvider(viewFinder.surfaceProvider)
        } catch(exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long,frame: Bitmap) {
        if (!isDetecting) return

        for (box in boundingBoxes) {
            Log.d(TAG, "DETECTION: ${box.clsName} @ ${box.cnf}")

            // Trigger upload for ANY detected crack/pothole from your label list
            // Scale the 0.0-1.0 fractions up to the 640x640 pixel size
            val rect = RectF(box.x1 * 640, box.y1 * 640, box.x2 * 640, box.y2 * 640)
            val costLabel = calculatePotholeCost(rect, 640, 640)

            processDetection(costLabel, frame, box.clsName)
        }

        runOnUiThread {
            binding.inferenceTime.text = "${inferenceTime}ms"
            binding.overlay.setResults(boundingBoxes)
            binding.overlay.invalidate()
        }
    }

    @SuppressLint("MissingPermission")
    private fun processDetection(cost: String, bitmap: Bitmap, originalLabel: String) {
        // Fallback: If live GPS hasn't locked yet, try to use the last known cached location
        if (currentLocation == null) {
            fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                currentLocation = lastLoc
                handleLocationAndUpload(cost, bitmap, originalLabel)
            }
        } else {
            handleLocationAndUpload(cost, bitmap, originalLabel)
        }
    }

    // This handles the actual checking and uploading
    private fun handleLocationAndUpload(cost: String, bitmap: Bitmap, originalLabel: String) {
        val location = currentLocation
        val currentTime = System.currentTimeMillis()

        val isDuplicate = recentDetections.any {
            val timeDiff = (currentTime - (it.time ?: 0)) / 1000
            val results = FloatArray(1)
            if (location != null && it.lat != null && it.lng != null) {
                Location.distanceBetween(location.latitude, location.longitude, it.lat, it.lng, results)
            }
            val distance = if (results.isNotEmpty()) results[0] else Float.MAX_VALUE

            timeDiff < 60 && distance < 5.0
        }

        if (!isDuplicate) {
            saveDetectionLocally(location, cost, currentTime, bitmap, originalLabel)
        }
    }

    private fun saveDetectionLocally(location: Location?, cost: String, time: Long, bitmap: Bitmap, label: String) {
        val fileName = "detect_${UUID.randomUUID()}.jpg"
        val file = File(getExternalFilesDir(null), fileName)

        try {
            val fos = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, fos)
            fos.close()

            val address = location?.let { getAddress(it.latitude, it.longitude) } ?: "Indoor/No Location"

            val report = PotholeReport(
                lat = location?.latitude ?: 0.0,
                lng = location?.longitude ?: 0.0,
                address = address,
                cost = cost,
                time = time,
                status = "Pending ($label)", // Include original label
                localImagePath = file.absolutePath,
                isDuplicate = false
            )

            database.push().setValue(report).addOnCompleteListener { task ->
                if (task.isSuccessful) Log.d(TAG, "UPLOAD SUCCESS: $label @ $address")
                else Log.e(TAG, "UPLOAD FAILED: ${task.exception?.message}")
            }

            recentDetections.add(report)
            if (recentDetections.size > 50) recentDetections.removeAt(0)

            runOnUiThread {
                Toast.makeText(baseContext, "Reported: $label", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Save failed: ${e.message}")
        }
    }

    private fun getAddress(lat: Double, lng: Double): String {
        return try {
            val geocoder = Geocoder(this, Locale.getDefault())
            val addresses = geocoder.getFromLocation(lat, lng, 1)
            addresses?.get(0)?.getAddressLine(0) ?: "Unknown Address"
        } catch (e: Exception) {
            "Address lookup failed"
        }
    }

    private fun calculatePotholeCost(box: RectF, frameWidth: Int, frameHeight: Int): String {
        val cameraHeightMeters = 1.5
        val cameraTiltDegrees = 45.0
        val asphaltDensity = 2300.0
        val pricePerKgInr = 15.0

        val horizonY = frameHeight / 2.0
        val boxBottomY = box.bottom
        if (boxBottomY < horizonY) return "₹0"

        val screenRatio = (boxBottomY - horizonY) / (frameHeight - horizonY)
        val distanceMeters = cameraHeightMeters / (screenRatio * tan(Math.toRadians(cameraTiltDegrees)))

        val realWidth = (distanceMeters / 0.8) * (box.width() / frameWidth)
        val realLength = (distanceMeters / 0.8) * (box.height() / frameHeight) * 3.0
        val volumeM3 = (realWidth * realLength) * 0.05
        val costRupees = (volumeM3 * asphaltDensity) * pricePerKgInr

        return "₹${"%.0f".format(costRupees)}"
    }

    override fun onEmptyDetect() {
        runOnUiThread { binding.overlay.clear() }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun toast(message: String) {
        runOnUiThread { Toast.makeText(baseContext, message, Toast.LENGTH_LONG).show() }
    }

    override fun onDestroy() {
        super.onDestroy()
        detector?.close()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }
}
