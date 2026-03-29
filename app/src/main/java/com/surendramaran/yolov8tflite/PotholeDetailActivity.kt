package com.surendramaran.yolov8tflite

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.surendramaran.yolov8tflite.databinding.ActivityPotholeDetailBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class PotholeDetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPotholeDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPotholeDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val cost = intent.getStringExtra("cost") ?: ""
        val time = intent.getLongExtra("time", 0L)
        val status = intent.getStringExtra("status") ?: "Pending"
        val lat = intent.getDoubleExtra("lat", 0.0)
        val lng = intent.getDoubleExtra("lng", 0.0)
        val localImagePath = intent.getStringExtra("localImagePath")

        binding.detailCost.text = cost
        binding.detailStatus.text = status
        binding.detailLocation.text = String.format("%.4f° N, %.4f° E", lat, lng)

        val sdf = SimpleDateFormat("MMMM dd, yyyy • HH:mm", Locale.getDefault())
        binding.detailDate.text = sdf.format(Date(time))

        if (localImagePath != null) {
            val file = File(localImagePath)
            if (file.exists()) {
                Glide.with(this)
                    .load(file)
                    .placeholder(android.R.drawable.ic_menu_gallery)
                    .into(binding.detailImage)
            }
        }
    }
}
