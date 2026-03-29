package com.surendramaran.yolov8tflite

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.surendramaran.yolov8tflite.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Check if already logged in
        val sharedPref = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        if (sharedPref.getBoolean("isLoggedIn", false)) {
            startActivity(Intent(this, HistoryActivity::class.java))
            finish()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initial state for animation
        binding.headerLayout.alpha = 0f
        binding.headerLayout.translationY = -50f
        binding.loginCard.alpha = 0f
        binding.loginCard.translationY = 100f
        binding.footerText.alpha = 0f

        // Start entrance animations
        binding.headerLayout.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(800)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        binding.loginCard.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(1000)
            .setStartDelay(200)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        binding.footerText.animate()
            .alpha(1f)
            .setDuration(600)
            .setStartDelay(800)
            .start()

        binding.loginBtn.setOnClickListener {
            val email = binding.emailInput.text.toString()
            val password = binding.passwordInput.text.toString()

            if (email.isNotEmpty() && password.isNotEmpty()) {
                // Save login state
                sharedPref.edit().putBoolean("isLoggedIn", true).apply()
                
                // Button click animation
                binding.loginBtn.animate()
                    .scaleX(0.95f)
                    .scaleY(0.95f)
                    .setDuration(100)
                    .withEndAction {
                        binding.loginBtn.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(100)
                            .withEndAction {
                                startActivity(Intent(this, HistoryActivity::class.java))
                                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                                finish()
                            }
                    }
            } else {
                Toast.makeText(this, "Please enter email and password", Toast.LENGTH_SHORT).show()
                binding.loginCard.animate()
                    .translationX(20f)
                    .setDuration(50)
                    .withEndAction {
                        binding.loginCard.animate()
                            .translationX(-20f)
                            .setDuration(50)
                            .withEndAction {
                                binding.loginCard.animate()
                                    .translationX(0f)
                                    .setDuration(50)
                            }
                    }
            }
        }
    }
}
