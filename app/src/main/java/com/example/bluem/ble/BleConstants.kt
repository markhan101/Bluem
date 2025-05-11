package com.example.bluem.ble

import android.os.ParcelUuid
import java.util.UUID


object BleConstants
{
	val SERVICE_UUID: ParcelUuid = ParcelUuid.fromString("a6cb59ee-1ef5-4c5e-bc36-dda3ec7d53d5")
}

data class PingData(
	val deviceAddress: String,
	var timestamp: Long,
	var rssi: Int,
	var isActive: Boolean = true
	// Add other relevant data from the ping if needed
)