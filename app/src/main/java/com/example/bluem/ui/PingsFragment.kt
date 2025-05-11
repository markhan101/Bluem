package com.example.bluem.ui

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.bluem.R
import com.example.bluem.ui.adapter.PingAdapter
import com.example.bluem.ble.PingData // Make sure this is the correct import for your PingData model
import com.example.bluem.ui.PingInteractionListener // Import the listener interface
import java.util.concurrent.ConcurrentHashMap

class PingsFragment : Fragment() {

	private val TAG = "PingsFragment"
	private lateinit var recyclerView: RecyclerView
	private lateinit var pingAdapter: PingAdapter
	private val pingList = mutableListOf<PingData>() // Internal list for the adapter

	private val deviceLastUiUpdateTime = ConcurrentHashMap<String, Long>()
	private val UI_UPDATE_DEBOUNCE_MS = 2000L // Update UI for an existing device at most every 2 seconds


	private var interactionListener: PingInteractionListener? = null

	// Handler for timeout checks
	private val handler = Handler(Looper.getMainLooper())
	private lateinit var pingTimeoutRunnable: Runnable
	private val PING_TIMEOUT_MS = 30000L // 30 seconds, devices not heard from in this time are marked inactive
	private val CHECK_INTERVAL_MS = 5000L // Check for timeouts every 5 seconds

	override fun onAttach(context: Context) {
		super.onAttach(context)
		// Ensure the hosting activity implements the callback interface
		if (context is PingInteractionListener) {
			interactionListener = context
		} else {
			throw RuntimeException("$context must implement PingInteractionListener")
		}
	}

	override fun onCreateView(
		inflater: LayoutInflater, container: ViewGroup?,
		savedInstanceState: Bundle?
	): View? {
		// Inflate the layout for this fragment
		val view = inflater.inflate(R.layout.fragment_pings, container, false)
		recyclerView = view.findViewById(R.id.pingsRecyclerView)
		return view
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		setupRecyclerView()
		setupPingTimeoutChecker()
		Log.d(TAG, "PingsFragment onViewCreated")
		// If there are any initially known pings (e.g. from a ViewModel), load them here.
		// For now, it starts empty and relies on live updates.
	}

	private fun setupRecyclerView() {
		// Make sure listener is not null, it should be set in onAttach
		val listener = interactionListener ?: run {
			Log.e(TAG, "PingInteractionListener is null during setupRecyclerView!")
			// Fallback or throw exception. For now, let's proceed but log error.
			// This indicates a lifecycle issue if it happens.
			// A safer approach might be to delay adapter creation until listener is confirmed.
			// However, onAttach is called before onCreateView/onViewCreated.
			object : PingInteractionListener { // Dummy listener to prevent crash, but logs error
				override fun onPingLongClicked(pingData: PingData) {
					Log.e(TAG, "Dummy listener: onPingLongClicked for ${pingData.deviceAddress}")
				}
				override fun getCustomNameForDevice(deviceAddress: String): String? {
					Log.e(TAG, "Dummy listener: getCustomNameForDevice for $deviceAddress")
					return null
				}
			}
		}

		pingAdapter = PingAdapter(pingList, listener)
		recyclerView.apply {
			layoutManager = LinearLayoutManager(requireContext())
			adapter = pingAdapter
			itemAnimator = DefaultItemAnimator() // Adds default animations for item changes
			// Optional: Add a divider
			// addItemDecoration(DividerItemDecoration(requireContext(), LinearLayoutManager.VERTICAL))
		}
		Log.d(TAG, "RecyclerView setup complete.")
	}

	private fun setupPingTimeoutChecker() {
		pingTimeoutRunnable = Runnable {
			checkAndMarkInactivePings()
			// Schedule the next check only if the fragment is still in a resumed state
			if (isResumed) {
				handler.postDelayed(pingTimeoutRunnable, CHECK_INTERVAL_MS)
			}
		}
	}

	override fun onResume() {
		super.onResume()
		// Start the timeout checker when the fragment is resumed
		handler.removeCallbacks(pingTimeoutRunnable) // Remove any existing callbacks first
		handler.postDelayed(pingTimeoutRunnable, CHECK_INTERVAL_MS)
		Log.d(TAG, "Ping timeout checker started/resumed.")
	}

	override fun onPause() {
		super.onPause()
		// Stop the timeout checker when the fragment is paused
		handler.removeCallbacks(pingTimeoutRunnable)
		Log.d(TAG, "Ping timeout checker paused.")
	}

	/**
	 * Called by MainActivity to update or add a new ping.
	 * This method should handle getting custom names via the listener.
	 */
	fun updatePings(incomingPingData: PingData) {
		activity?.runOnUiThread {
			Log.d(TAG, "Received ping for processing: ${incomingPingData.deviceAddress}, RSSI: ${incomingPingData.rssi}")

			val currentTime = System.currentTimeMillis()
			val deviceAddress = incomingPingData.deviceAddress

			val existingPingIndex = pingList.indexOfFirst { it.deviceAddress == deviceAddress }

			if (existingPingIndex != -1) { // Device already in the list
				// It's an existing device, check debounce timer
				val lastUpdate = deviceLastUiUpdateTime[deviceAddress] ?: 0L
				if (currentTime - lastUpdate > UI_UPDATE_DEBOUNCE_MS || !pingList[existingPingIndex].isActive) {
					// Enough time has passed OR it was marked inactive, so update it
					Log.d(TAG, "Debounce pass for existing device: $deviceAddress. Updating UI.")

					val customNameFromHost = interactionListener?.getCustomNameForDevice(deviceAddress)
					val finalPingData = incomingPingData.copy(
						customName = customNameFromHost ?: pingList[existingPingIndex].customName // Preserve existing custom name if host returns null
					)
					pingAdapter.addOrUpdatePing(finalPingData) // This will update the item
					deviceLastUiUpdateTime[deviceAddress] = currentTime
				} else {
					// Log.v(TAG, "Debounced UI update for existing device: $deviceAddress. Data updated silently.")
					// Silently update the underlying data model if needed, but don't call notifyItemChanged yet.
					// For now, we only update if debounce passes. This means RSSI/timestamp in UI won't be real-time.
					// A more complex approach would update the PingData in pingList but only call notifyItemChanged
					// when the debounce timer allows. For simplicity, we just update fully on debounce pass.
					pingList[existingPingIndex].timestamp = incomingPingData.timestamp // Keep latest timestamp
					pingList[existingPingIndex].rssi = incomingPingData.rssi           // Keep latest RSSI
					if (!pingList[existingPingIndex].isActive) { // If it was inactive, force an update
						pingList[existingPingIndex].isActive = true
						// If we are here due to debounce, we could force an update if it just became active
						// But addOrUpdatePing in adapter already handles setting isActive to true.
						// The main `if` condition `|| !pingList[existingPingIndex].isActive` handles this.
					}

				}
			} else { // New device
				Log.d(TAG, "New device found: $deviceAddress. Adding to UI.")
				val customNameFromHost = interactionListener?.getCustomNameForDevice(deviceAddress)
				val finalPingData = incomingPingData.copy(customName = customNameFromHost)

				pingAdapter.addOrUpdatePing(finalPingData) // This will add the new item
				deviceLastUiUpdateTime[deviceAddress] = currentTime
				if (recyclerView.layoutManager?.canScrollVertically() == true && pingList.size > 0) {
					recyclerView.scrollToPosition(0) // Scroll to top for new item
				}
			}
		}
	}

	/**
	 * Called by MainActivity after a custom name is saved/cleared.
	 */
	fun refreshPingItem(deviceAddress: String) {
		activity?.runOnUiThread {
			Log.d(TAG, "Attempting to refresh ping item via refreshPingItem: $deviceAddress")
			val index = pingList.indexOfFirst { it.deviceAddress == deviceAddress }
			if (index != -1) {
				val updatedCustomName = interactionListener?.getCustomNameForDevice(deviceAddress)
				if (pingList[index].customName != updatedCustomName) {
					pingList[index].customName = updatedCustomName
					pingAdapter.notifyItemChanged(index) // Directly notify since it's an explicit refresh
					Log.d(TAG, "Refreshed item $deviceAddress custom name: $updatedCustomName")
					deviceLastUiUpdateTime[deviceAddress] = System.currentTimeMillis() // Reset debounce timer
				}
			} else {
				Log.w(TAG, "Could not find item $deviceAddress to refresh.")
			}
		}
	}

	private fun checkAndMarkInactivePings() {
		val currentTime = System.currentTimeMillis()
		var itemsChangedCount = 0

		// Iterate over a copy if concerned about concurrent modification,
		// but adapter operations should be on UI thread.
		val pingsToEvaluate = ArrayList(pingList) // Iterate on a snapshot

		for (ping in pingsToEvaluate) {
			if (ping.isActive && (currentTime - ping.timestamp > PING_TIMEOUT_MS)) {
				Log.i(TAG, "Device ${ping.deviceAddress} timed out. Marking as inactive.")
				// The markAsInactive method in adapter will find the item in the original list
				pingAdapter.markAsInactive(ping.deviceAddress)
				deviceLastUiUpdateTime.remove(ping.deviceAddress) // Remove from debounce map
				itemsChangedCount++
			}
		}
		if (itemsChangedCount > 0) {
			Log.d(TAG, "$itemsChangedCount pings marked as inactive.")
		}
	}

	override fun onDetach() {
		super.onDetach()
		interactionListener = null // Clean up the listener to avoid memory leaks
		Log.d(TAG, "PingsFragment detached.")
	}
}