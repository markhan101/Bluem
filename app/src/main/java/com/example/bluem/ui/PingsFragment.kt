package com.example.bluem.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.bluem.R
import com.example.bluem.ui.adapter.PingAdapter
import com.example.bluem.ble.PingData // Ensure correct import
import java.util.concurrent.CopyOnWriteArrayList // For thread-safe iteration if needed

class PingsFragment : Fragment() {

	private val TAG = "PingsFragment"
	private lateinit var recyclerView: RecyclerView
	private lateinit var pingAdapter: PingAdapter
	// Use CopyOnWriteArrayList if modifying list while iterating from another thread,
	// but adapter updates should be on main thread. For simplicity, MutableList is fine if all
	// modifications and adapter notifications are on the UI thread.
	private val pingList = mutableListOf<PingData>()

	private val handler = Handler(Looper.getMainLooper())
	private lateinit var pingTimeoutRunnable: Runnable
	private val PING_TIMEOUT_MS = 30000L // 30 seconds timeout, adjust as needed
	private val CHECK_INTERVAL_MS = 5000L // Check every 5 seconds


	override fun onCreateView(
		inflater: LayoutInflater, container: ViewGroup?,
		savedInstanceState: Bundle?
	): View? {
		val view = inflater.inflate(R.layout.fragment_pings, container, false)
		recyclerView = view.findViewById(R.id.pingsRecyclerView)
		return view
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		setupRecyclerView()
		setupPingTimeoutChecker()
	}

	private fun setupRecyclerView() {
		pingAdapter = PingAdapter(pingList) // Pass the mutable list
		recyclerView.layoutManager = LinearLayoutManager(requireContext())
		recyclerView.adapter = pingAdapter
		// Consider adding ItemAnimator or ItemDecoration if needed
		// recyclerView.itemAnimator = DefaultItemAnimator()
		// recyclerView.addItemDecoration(DividerItemDecoration(context, LinearLayoutManager.VERTICAL))
	}

	private fun setupPingTimeoutChecker() {
		pingTimeoutRunnable = Runnable {
			checkAndMarkInactivePings()
			handler.postDelayed(pingTimeoutRunnable, CHECK_INTERVAL_MS) // Schedule next check
		}
	}

	override fun onResume() {
		super.onResume()
		handler.postDelayed(pingTimeoutRunnable, CHECK_INTERVAL_MS) // Start checker
		Log.d(TAG, "Ping timeout checker started")
	}

	override fun onPause() {
		super.onPause()
		handler.removeCallbacks(pingTimeoutRunnable) // Stop checker
		Log.d(TAG, "Ping timeout checker stopped")
	}

	fun updatePings(newPingData: PingData) {
		activity?.runOnUiThread {
			pingAdapter.addOrUpdatePing(newPingData)
			// Optional: Scroll to top if a new item was added (not just updated)
			// val existingPingIndex = pingList.indexOfFirst { it.deviceAddress == newPingData.deviceAddress }
			// if (existingPingIndex == -1 || pingList.indexOf(newPingData) == 0) { // Check if it's new or moved to top
			//    recyclerView.scrollToPosition(0)
			// }
		}
	}

	private fun checkAndMarkInactivePings() {
		Log.d(TAG, "Checking for inactive pings...")
		val currentTime = System.currentTimeMillis()
		val pingsSnapshot = ArrayList(pingAdapter.getPingList()) // Iterate over a snapshot

		var changed = false
		for (ping in pingsSnapshot) {
			if (ping.isActive && (currentTime - ping.timestamp > PING_TIMEOUT_MS)) {
				Log.i(TAG, "Device ${ping.deviceAddress} timed out. Last seen at ${ping.timestamp}")
				pingAdapter.markAsInactive(ping.deviceAddress)
				changed = true
			}
		}
		if (changed) {
			Log.d(TAG, "Inactive pings updated in adapter.")
		}
	}
}