package com.example.bluem.ui

import com.example.bluem.ble.PingData

interface PingInteractionListener {
	fun onPingLongClicked(pingData: PingData)
	fun getCustomNameForDevice(deviceAddress: String): String? // For Fragment to get name
	// Add other interactions if needed
}