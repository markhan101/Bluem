package com.example.bluem.ui // Or your preferred package for activities

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.ScaleAnimation
import android.view.animation.TranslateAnimation
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.bluem.R

@SuppressLint("CustomSplashScreen") // Suppress if using this as a custom splash and not Android 12+ API
class SplashActivity : AppCompatActivity() {

	private val SPLASH_DISPLAY_LENGTH_MS: Long = 3000 // Total splash duration
	private val ANIMATION_DURATION_MS: Long = 1200 // Duration for each part of the animation

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.splash_screen) // Your splash layout file name


		supportActionBar?.hide()

		val logoImage: ImageView = findViewById(R.id.splash_logo_image)
		val appNameText: TextView = findViewById(R.id.splash_app_name_text)
		val taglineText: TextView = findViewById(R.id.splash_tagline_text)

		val fadeInLogo = AlphaAnimation(0f, 1f)
		fadeInLogo.interpolator = AccelerateDecelerateInterpolator()
		fadeInLogo.duration = ANIMATION_DURATION_MS

		val scaleUpLogo = ScaleAnimation(
			0.8f, 1f, // From 80% to 100% size X
			0.8f, 1f, // From 80% to 100% size Y
			Animation.RELATIVE_TO_SELF, 0.5f, // Pivot X
			Animation.RELATIVE_TO_SELF, 0.5f  // Pivot Y
		)
		scaleUpLogo.interpolator = AccelerateDecelerateInterpolator()
		scaleUpLogo.duration = ANIMATION_DURATION_MS

		val logoAnimationSet = AnimationSet(false) // false = don't share interpolator
		logoAnimationSet.addAnimation(fadeInLogo)
		logoAnimationSet.addAnimation(scaleUpLogo)
		logoImage.startAnimation(logoAnimationSet)


		val fadeInAppName = AlphaAnimation(0f, 1f)
		fadeInAppName.duration = ANIMATION_DURATION_MS
		fadeInAppName.startOffset = ANIMATION_DURATION_MS / 3

		val slideUpAppName = TranslateAnimation(
			Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 0f,
			Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0f
		)
		slideUpAppName.duration = ANIMATION_DURATION_MS
		slideUpAppName.interpolator = AccelerateDecelerateInterpolator()
		slideUpAppName.startOffset = ANIMATION_DURATION_MS / 3

		val appNameAnimationSet = AnimationSet(false)
		appNameAnimationSet.addAnimation(fadeInAppName)
		appNameAnimationSet.addAnimation(slideUpAppName)
		appNameText.startAnimation(appNameAnimationSet)



		val fadeInTagline = AlphaAnimation(0f, 1f)
		fadeInTagline.duration = ANIMATION_DURATION_MS / 2
		fadeInTagline.startOffset = ANIMATION_DURATION_MS * 2 / 3
		fadeInTagline.interpolator = AccelerateDecelerateInterpolator()
		taglineText.startAnimation(fadeInTagline)



		Handler(Looper.getMainLooper()).postDelayed({
			val mainIntent = Intent(this@SplashActivity, MainActivity::class.java)
			startActivity(mainIntent)
			finish()
		}, SPLASH_DISPLAY_LENGTH_MS)
	}
}