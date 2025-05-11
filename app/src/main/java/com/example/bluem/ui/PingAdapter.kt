package com.example.bluem.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.bluem.R
import com.example.bluem.ble.PingData
import com.example.bluem.ui.PingInteractionListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow

class PingAdapter(
	private val pings: MutableList<PingData>,
	private val interactionListener: PingInteractionListener // Listener for item click and long click (now for name setting)
) : RecyclerView.Adapter<PingAdapter.PingViewHolder>() {

	// Constants for distance calculation (MEASURED_POWER_AT_1_METER, ENVIRONMENTAL_FACTOR_N - same)

	class PingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
		// Summary Views
		val displayNameTextView: TextView = itemView.findViewById(R.id.itemDisplayNameTextView)
		val statusSummaryTextView: TextView = itemView.findViewById(R.id.itemStatusSummaryTextView)
		val lastSeenSummaryTextView: TextView = itemView.findViewById(R.id.itemLastSeenSummaryTextView)
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PingViewHolder {
		val view = LayoutInflater.from(parent.context).inflate(R.layout.item_ping, parent, false)
		return PingViewHolder(view)
	}

	override fun onBindViewHolder(holder: PingViewHolder, position: Int) {
		val ping = pings[position]
		holder.displayNameTextView.text = ping.getDisplayName()

		if (ping.isActive) {
			holder.statusSummaryTextView.text = "Status: Active"
			holder.statusSummaryTextView.setTextColor(Color.parseColor("#FF4CAF50")) // Green
			holder.lastSeenSummaryTextView.text = "Last Ping: ${formatSimpleTimestamp(ping.timestamp)}"
		} else {
			holder.statusSummaryTextView.text = "Status: Inactive"
			holder.statusSummaryTextView.setTextColor(Color.parseColor("#FFF44336")) // Red
			holder.lastSeenSummaryTextView.text = "Last Seen: ${formatSimpleTimestamp(ping.timestamp)}"
		}


		holder.itemView.setOnClickListener {

			interactionListener.onPingClickedForDetails(ping)
		}


		holder.itemView.setOnLongClickListener {
			interactionListener.onPingLongClicked(pingData = ping) // This is for setting name
			true
		}
	}

	override fun getItemCount(): Int = pings.size

	private fun formatSimpleTimestamp(timestamp: Long): String {
		val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault()) // Shorter format for summary
		return sdf.format(Date(timestamp))
	}


	fun formatFullTimestamp(timestamp: Long): String {
		val sdf = SimpleDateFormat("HH:mm:ss dd MMM yyyy", Locale.getDefault())
		return sdf.format(Date(timestamp))
	}

	fun calculateDistance(rssi: Int, measuredPower: Int = -69, nFactor: Double = 2.5): Double {
		if (rssi == 0 || rssi < -100) return -1.0
		return 10.0.pow(((measuredPower.toDouble() - rssi.toDouble()) / (10 * nFactor)))
	}



	fun addOrUpdatePing(newPing: PingData) {
		val existingPingIndex = pings.indexOfFirst { it.deviceAddress == newPing.deviceAddress }
		if (existingPingIndex != -1) {
			pings[existingPingIndex].apply {
				timestamp = newPing.timestamp; rssi = newPing.rssi; isActive = newPing.isActive
				bleDeviceName = newPing.bleDeviceName ?: bleDeviceName
				customName = newPing.customName ?: customName
				parsedBloodGroupIndex = newPing.parsedBloodGroupIndex
				parsedHasPhoneNumberFlag = newPing.parsedHasPhoneNumberFlag
				parsedLatitude = newPing.parsedLatitude; parsedLongitude = newPing.parsedLongitude
				parsedPhoneSuffixValue = newPing.parsedPhoneSuffixValue
				parsedSequenceOrTimeValue = newPing.parsedSequenceOrTimeValue
			}
			notifyItemChanged(existingPingIndex)
		} else {
			pings.add(0, newPing); notifyItemInserted(0)
		}
	}
	fun markAsInactive(deviceAddress: String) {
		val index = pings.indexOfFirst { it.deviceAddress == deviceAddress && it.isActive }
		if (index != -1) {
			pings[index].isActive = false
			notifyItemChanged(index)
		}
	}

	fun getPingList(): List<PingData> = ArrayList(pings) // Return a copy
}