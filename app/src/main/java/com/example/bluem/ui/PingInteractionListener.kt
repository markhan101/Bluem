package com.example.bluem.ui

import com.example.bluem.ble.PingData

interface PingInteractionListener {
	fun onPingClickedForDetails(pingData: PingData)
	fun onPingLongClicked(pingData: PingData) // Existing method for setting custom name
	fun getCustomNameForDevice(deviceAddress: String): String?

}