package com.surendramaran.yolov8tflite

import com.google.firebase.database.IgnoreExtraProperties

@IgnoreExtraProperties
data class PotholeReport(
    var id: String? = null,
    val lat: Double? = 0.0,
    val lng: Double? = 0.0,
    val cost: String? = "",
    val time: Long? = 0L,
    val status: String? = "Pending",
    val localImagePath: String? = null,
    val isDuplicate: Boolean = false
)
