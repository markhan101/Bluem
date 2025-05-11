package com.example.bluem.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.bluem.ui.PingsFragment // We will create this
import com.example.bluem.ui.ProfileFragment // We will create this

class ViewPagerAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {

	private val fragments = listOf(
		PingsFragment(),
		ProfileFragment()
	)

	override fun getItemCount(): Int = fragments.size

	override fun createFragment(position: Int): Fragment = fragments[position]

	fun getPageTitle(position: Int): String {
		return when (position) {
			0 -> "Nearby Pings"
			1 -> "My Profile"
			else -> "Unknown"
		}
	}
}