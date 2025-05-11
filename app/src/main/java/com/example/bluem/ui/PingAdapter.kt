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
	private val interactionListener: PingInteractionListener
) : RecyclerView.Adapter<PingAdapter.PingViewHolder>() {

	private val MEASURED_POWER_AT_1_METER = -69 // Example RSSI at 1 meter
	private val ENVIRONMENTAL_FACTOR_N = 2.5 // Typical indoor N factor (2.0 to 4.0)

	class PingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
		val addressTextView: TextView = itemView.findViewById(R.id.itemAddressTextView)
		val timeTextView: TextView = itemView.findViewById(R.id.itemTimeTextView)
		val rssiTextView: TextView = itemView.findViewById(R.id.itemRssiTextView)
		val distanceTextView: TextView = itemView.findViewById(R.id.itemDistanceTextView)
		val statusTextView: TextView = itemView.findViewById(R.id.itemStatusTextView)
		val profileDataLayout: LinearLayout = itemView.findViewById(R.id.itemProfileDataLayout)
		val bloodGroupTextView: TextView = itemView.findViewById(R.id.itemBloodGroupTextView)
		val locationTextView: TextView = itemView.findViewById(R.id.itemLocationTextView)
		val phoneInfoTextView: TextView = itemView.findViewById(R.id.itemPhoneInfoTextView)
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PingViewHolder {
		val view = LayoutInflater.from(parent.context).inflate(R.layout.item_ping, parent, false)
		return PingViewHolder(view)
	}

	override fun onBindViewHolder(holder: PingViewHolder, position: Int) {
		val ping = pings[position]
		holder.addressTextView.text = ping.getDisplayName() // Uses custom name if available
		holder.rssiTextView.text = "RSSI: ${ping.rssi} dBm"

		if (ping.isActive) {
			holder.statusTextView.text = "Status: Actively Pinging"
			holder.statusTextView.setTextColor(Color.parseColor("#FF4CAF50")) // Green
			holder.timeTextView.text = "Last Ping: ${formatTimestamp(ping.timestamp)}"
			val distance = calculateDistance(ping.rssi)
			if (distance >= 0) {
				holder.distanceTextView.text = "Approx. Dist: ${"%.1f".format(distance)} m"
				holder.distanceTextView.visibility = View.VISIBLE
			} else {
				holder.distanceTextView.text = "Approx. Dist: N/A" // Or hide
				holder.distanceTextView.visibility = View.VISIBLE // Or GONE
			}

			// Display parsed profile data
			var profileInfoAvailable = false
			ping.getParsedBloodGroupString(holder.itemView.context)?.let { bg ->
				holder.bloodGroupTextView.text = "Blood: $bg"
				profileInfoAvailable = true
			} ?: holder.bloodGroupTextView.setText("Blood: N/A")


			if (ping.parsedLatitude != null && ping.parsedLongitude != null &&
				ping.parsedLatitude != 91.0 && ping.parsedLongitude != 181.0) { // Check for valid placeholder
				holder.locationTextView.text = "Loc: ${"%.3f".format(ping.parsedLatitude)}, ${"%.3f".format(ping.parsedLongitude)}"
				profileInfoAvailable = true
			} else {
				holder.locationTextView.text = "Loc: N/A"
			}

			if (ping.parsedHasPhoneNumberFlag == true && ping.parsedPhoneSuffixValue != null && ping.parsedPhoneSuffixValue != 0L) {
				holder.phoneInfoTextView.text = "Phone Sfx: ...${ping.parsedPhoneSuffixValue.toString().takeLast(4)}"
				holder.phoneInfoTextView.visibility = View.VISIBLE
				profileInfoAvailable = true
			} else {
				holder.phoneInfoTextView.visibility = View.GONE
			}

			holder.profileDataLayout.visibility = if (profileInfoAvailable) View.VISIBLE else View.GONE

		} else { // Not active
			holder.statusTextView.text = "Status: No Recent Pings"
			holder.statusTextView.setTextColor(Color.parseColor("#FFF44336")) // Red
			holder.timeTextView.text = "Last Seen: ${formatTimestamp(ping.timestamp)}"
			holder.distanceTextView.visibility = View.GONE
			holder.profileDataLayout.visibility = View.GONE
		}

		holder.itemView.setOnLongClickListener {
			interactionListener.onPingLongClicked(ping)
			true
		}
	}

	override fun getItemCount(): Int = pings.size

	private fun formatTimestamp(timestamp: Long): String {
		val sdf = SimpleDateFormat("HH:mm:ss dd MMM", Locale.getDefault())
		return sdf.format(Date(timestamp))
	}

	private fun calculateDistance(rssi: Int): Double {
		if (rssi == 0 || rssi < -100) return -1.0 // Invalid RSSI
		return 10.0.pow(((MEASURED_POWER_AT_1_METER.toDouble() - rssi.toDouble()) / (10 * ENVIRONMENTAL_FACTOR_N)))
	}

	fun addOrUpdatePing(newPing: PingData) {
		val existingPingIndex = pings.indexOfFirst { it.deviceAddress == newPing.deviceAddress }
		if (existingPingIndex != -1) {
			// Update all fields of the existing ping object
			pings[existingPingIndex].apply {
				timestamp = newPing.timestamp
				rssi = newPing.rssi
				isActive = newPing.isActive // Should always be true for an incoming ping
				bleDeviceName = newPing.bleDeviceName ?: bleDeviceName // Preserve old if new is null
				customName = newPing.customName ?: customName // Preserve old if new is null

				parsedBloodGroupIndex = newPing.parsedBloodGroupIndex
				parsedHasPhoneNumberFlag = newPing.parsedHasPhoneNumberFlag
				parsedLatitude = newPing.parsedLatitude
				parsedLongitude = newPing.parsedLongitude
				parsedPhoneSuffixValue = newPing.parsedPhoneSuffixValue
				parsedSequenceOrTimeValue = newPing.parsedSequenceOrTimeValue
			}
			notifyItemChanged(existingPingIndex)
		} else {
			pings.add(0, newPing) // Add new ping to the top
			notifyItemInserted(0)
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