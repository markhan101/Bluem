package com.example.bluem.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.bluem.R
import com.example.bluem.ble.PingData // Ensure correct import for PingData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow

// Change List<PingData> to MutableList<PingData>
class PingAdapter(private val pings: MutableList<PingData>) : RecyclerView.Adapter<PingAdapter.PingViewHolder>() {

	// Constants for distance calculation (these are typical, adjust if you have calibration data)
	private val MEASURED_POWER_AT_1_METER = -69 // Example: Calibrated RSSI at 1 meter
	private val ENVIRONMENTAL_FACTOR_N = 2.0 // For free space, 2-4 for indoor/obstructed

	class PingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
		val addressTextView: TextView = itemView.findViewById(R.id.itemAddressTextView)
		val timeTextView: TextView = itemView.findViewById(R.id.itemTimeTextView)
		val rssiTextView: TextView = itemView.findViewById(R.id.itemRssiTextView)
		val distanceTextView: TextView = itemView.findViewById(R.id.itemDistanceTextView)
		val statusTextView: TextView = itemView.findViewById(R.id.itemStatusTextView)
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PingViewHolder {
		val view = LayoutInflater.from(parent.context).inflate(R.layout.item_ping, parent, false)
		return PingViewHolder(view)
	}

	override fun onBindViewHolder(holder: PingViewHolder, position: Int) {
		val ping = pings[position]
		holder.addressTextView.text = "Device: ${ping.deviceAddress}"
		holder.rssiTextView.text = "RSSI: ${ping.rssi} dBm"

		if (ping.isActive) {
			holder.statusTextView.text = "Status: Actively Pinging"
			holder.statusTextView.setTextColor(Color.parseColor("#FF4CAF50")) // Green
			holder.timeTextView.text = "Last Ping: ${formatTimestamp(ping.timestamp)}"
			val distance = calculateDistance(ping.rssi)
			holder.distanceTextView.text = "Approx. Dist: ${"%.2f".format(distance)} m"
			holder.distanceTextView.visibility = View.VISIBLE
		} else {
			holder.statusTextView.text = "Status: No Recent Pings"
			holder.statusTextView.setTextColor(Color.parseColor("#FFF44336")) // Red
			holder.timeTextView.text = "Last Seen: ${formatTimestamp(ping.timestamp)}"
			holder.distanceTextView.visibility = View.GONE
		}
	}

	override fun getItemCount(): Int = pings.size

	private fun formatTimestamp(timestamp: Long): String {
		val sdf = SimpleDateFormat("HH:mm:ss dd MMM", Locale.getDefault())
		return sdf.format(Date(timestamp))
	}

	private fun calculateDistance(rssi: Int): Double {
		if (rssi == 0 || rssi < -100 ) { // Also filter out extremely low (likely invalid) RSSI
			return -1.0 // Or Double.NaN
		}
		// Using a common simplified formula. Results will vary greatly.
		val distance = 10.0.pow(((MEASURED_POWER_AT_1_METER.toDouble() - rssi.toDouble()) / (10 * ENVIRONMENTAL_FACTOR_N)))
		return distance
	}

	fun addOrUpdatePing(newPing: PingData) {
		val existingPingIndex = pings.indexOfFirst { it.deviceAddress == newPing.deviceAddress }
		if (existingPingIndex != -1) {
			// Update existing
			pings[existingPingIndex].timestamp = newPing.timestamp
			pings[existingPingIndex].rssi = newPing.rssi
			pings[existingPingIndex].isActive = true // Mark as active on new ping
			notifyItemChanged(existingPingIndex)
		} else {
			// Add new
			pings.add(0, newPing) // Add to top
			notifyItemInserted(0)
		}
	}

	fun markAsInactive(deviceAddress: String) {
		val existingPingIndex = pings.indexOfFirst { it.deviceAddress == deviceAddress && it.isActive }
		if (existingPingIndex != -1) {
			pings[existingPingIndex].isActive = false
			notifyItemChanged(existingPingIndex)
		}
	}

	fun getPingList(): List<PingData> { // Return an immutable List copy if external modification is not desired
		return ArrayList(pings) // Or just pings if read-only access is okay
	}
}