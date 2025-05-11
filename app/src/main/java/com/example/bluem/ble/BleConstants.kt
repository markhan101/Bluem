package com.example.bluem.ble

import android.content.Context
import android.os.ParcelUuid
import com.example.bluem.R
import java.util.UUID


object BleConstants
{
	val SERVICE_UUID: ParcelUuid = ParcelUuid.fromString("a6cb59ee-1ef5-4c5e-bc36-dda3ec7d53d5")
	const val BLUEM_MANUFACTURER_ID = 0x06E0
	const val BLUEM_PROTOCOL_ID_PROFILE_PING: Byte = 0x01

}

data class PingData(
	val deviceAddress: String,
	var bleDeviceName: String?,      // Name from BLE advertisement/scan
	var customName: String? = null, // User-defined name
	var timestamp: Long,            // Last time ping was received or data updated
	var rssi: Int,                  // Received Signal Strength Indicator
	var isActive: Boolean = true,   // True if recently received, false if timed out

	// Parsed profile data from the BLE ping payload
	var parsedBloodGroupIndex: Int? = null, // Index from your blood_groups_array
	var parsedHasPhoneNumberFlag: Boolean? = null, // Flag indicating if phone suffix is present in payload
	var parsedLatitude: Double? = null,
	var parsedLongitude: Double? = null,
	var parsedPhoneSuffixValue: Long? = null, // The numeric phone suffix
	var parsedSequenceOrTimeValue: Byte? = null // Sequence number or time part from payload
) {
	// Helper to get the best display name
	fun getDisplayName(): String {
		return customName ?: bleDeviceName ?: deviceAddress
	}

	// Helper to convert blood group index to a displayable string
	// Note: This requires access to string resources, ideally done in Adapter or Fragment
	// For simplicity here, we can hardcode or pass the resources array.
	// A better way is to have the adapter do this conversion using its context.
	fun getParsedBloodGroupString(context: Context? = null): String? {
		return parsedBloodGroupIndex?.let { index ->
			if (context != null) {
				try {
					val bloodGroups = context.resources.getStringArray(R.array.blood_groups_array)
					if (index >= 0 && index < bloodGroups.size) bloodGroups[index] else "Idx $index"
				} catch (e: Exception) { "Idx $index" } // Fallback if resources not found
			} else {
				// Fallback if no context - less ideal
				when(index) {
					0 -> "A+" 1 -> "A-" 2 -> "B+" 3 -> "B-" 4 -> "AB+" 5 -> "AB-" 6 -> "O+" 7 -> "O-"
					8 -> "Unknown"
					else -> "Idx $index"
				}
			}
		}
	}
}