package com.example.bluem.service

import android.Manifest // For @SuppressLint
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
// Remove Parcelable and kotlinx.parcelize.Parcelize if not using Parcelable ParsedProfileData
// import android.os.Parcelable
// import kotlinx.parcelize.Parcelize
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.bluem.R
import com.example.bluem.ble.BleConstants
import com.example.bluem.ui.ProfileFragment // For SharedPreferences keys
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

class BluemEmergencyService : Service() {

	private val TAG = "BleService"
	private val NOTIFICATION_CHANNEL_ID = "BleServiceChannel"
	private val NOTIFICATION_ID = 1
	private val binder = LocalBinder()

	@Volatile private var isAdvertisingNow = false
	@Volatile private var isScanningNow = false

	private lateinit var bluetoothManager: BluetoothManager
	private var bluetoothAdapter: BluetoothAdapter? = null
	private var advertiser: BluetoothLeAdvertiser? = null
	private var scanner: BluetoothLeScanner? = null

	private lateinit var localBroadcastManager: LocalBroadcastManager
	private val mainHandler = Handler(Looper.getMainLooper())

	// Data class for parsed profile information from pings
	// Not making it Parcelable for this version to keep it simpler with individual extras
	data class ParsedProfileData(
		val bloodGroupIndex: Int,
		val hasPhoneNumberFlag: Boolean,
		val latitude: Double,
		val longitude: Double,
		val phoneSuffixValue: Long,
		val sequenceOrTimeValue: Byte
	)

	companion object {
		const val ACTION_START_PINGING = "com.example.bluem.action.START_PINGING"
		const val ACTION_STOP_PINGING = "com.example.bluem.action.STOP_PINGING"
		const val ACTION_STOP_SERVICE = "com.example.bluem.action.STOP_SERVICE"
		const val ACTION_STATE_CHANGED = "com.example.bluem.action.STATE_CHANGED"
		const val EXTRA_IS_ADVERTISING = "com.example.bluem.extra.IS_ADVERTISING"
		const val EXTRA_IS_SCANNING = "com.example.bluem.extra.IS_SCANNING"

		const val ACTION_PING_RECEIVED = "com.example.bluem.action.PING_RECEIVED"
		const val EXTRA_DEVICE_ADDRESS = "com.example.bluem.extra.DEVICE_ADDRESS"
		const val EXTRA_BLE_DEVICE_NAME = "com.example.bluem.extra.BLE_DEVICE_NAME"
		const val EXTRA_RSSI = "com.example.bluem.extra.RSSI"
		// Keys for individual parsed fields
		const val EXTRA_PROFILE_BLOOD_GROUP_IDX = "com.example.bluem.extra.PROFILE_BLOOD_GROUP_IDX"
		const val EXTRA_PROFILE_HAS_PHONE = "com.example.bluem.extra.PROFILE_HAS_PHONE"
		const val EXTRA_PROFILE_LATITUDE = "com.example.bluem.extra.PROFILE_LATITUDE"
		const val EXTRA_PROFILE_LONGITUDE = "com.example.bluem.extra.PROFILE_LONGITUDE"
		const val EXTRA_PROFILE_PHONE_SUFFIX = "com.example.bluem.extra.PROFILE_PHONE_SUFFIX"
		const val EXTRA_PROFILE_SEQ_TIME = "com.example.bluem.extra.PROFILE_SEQ_TIME"
	}

	inner class LocalBinder : Binder() {
		fun getService(): BluemEmergencyService = this@BluemEmergencyService
		fun isCurrentlyAdvertising(): Boolean = isAdvertisingNow
		fun isCurrentlyScanning(): Boolean = isScanningNow
	}

	override fun onBind(intent: Intent?): IBinder? {
		Log.d(TAG, "Service onBind")
		broadcastState()
		return binder
	}

	override fun onRebind(intent: Intent?) {
		super.onRebind(intent)
		Log.d(TAG, "Service onRebind")
		broadcastState()
	}

	override fun onUnbind(intent: Intent?): Boolean {
		Log.d(TAG, "Service onUnbind")
		return true // Allow rebind
	}

	override fun onCreate() {
		super.onCreate()
		Log.d(TAG, "Service onCreate")
		localBroadcastManager = LocalBroadcastManager.getInstance(this)
		if (!initBluetoothAndContinue()) {
			return
		}
	}

	private fun initBluetoothAndContinue(): Boolean {
		if (!setupBluetoothComponents()) {
			createNotificationChannel()
			updateNotification("Bluetooth Error: Setup Failed")
			return false
		}
		createNotificationChannel()
		startForeground(NOTIFICATION_ID, createNotification("Service Initializing..."))
		startScanning()
		return true
	}

	@SuppressLint("MissingPermission")
	private fun setupBluetoothComponents(): Boolean {
		val hasBtConnect = hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
		val hasBtScan = hasPermission(Manifest.permission.BLUETOOTH_SCAN)
		val hasBtAdvertise = hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
		val hasFineLocation = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			if (!hasBtConnect || !hasBtScan || !hasBtAdvertise) {
				Log.e(TAG, "Missing core Bluetooth permissions (SDK 31+). Connect:$hasBtConnect, Scan:$hasBtScan, Adv:$hasBtAdvertise")
				showToast("Error: Bluetooth Permissions Missing"); return false
			}
		} else {
			if (!hasFineLocation) {
				Log.e(TAG, "Missing Fine Location permission for BLE Scan (Pre-SDK 31).")
				showToast("Error: Location Permission Missing for Scan"); return false
			}
		}
		try {
			bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
			bluetoothAdapter = bluetoothManager.adapter
		} catch (e: Exception) {
			Log.e(TAG, "Failed to get BluetoothManager or Adapter: ${e.message}", e); return false
		}
		if (bluetoothAdapter == null) { Log.e(TAG, "Bluetooth not supported."); showToast("Bluetooth not supported"); return false }
		if (bluetoothAdapter?.isEnabled == false) { Log.e(TAG, "Bluetooth not enabled."); showToast("Please enable Bluetooth"); return false }

		advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
		if (advertiser == null) { Log.w(TAG, "BLE Advertiser is null.") }

		scanner = bluetoothAdapter?.bluetoothLeScanner
		if (scanner == null) { Log.e(TAG, "BLE Scanner is null."); showToast("BLE Scanning Not Supported"); return false }

		Log.d(TAG, "Bluetooth Components Initialized.")
		return true
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		val action = intent?.action
		Log.d(TAG, "Service onStartCommand, Action: $action")
		if (bluetoothAdapter == null || bluetoothAdapter?.isEnabled == false) {
			if (!setupBluetoothComponents()) {
				if (isAdvertisingNow) stopAdvertising(true); if (isScanningNow) stopScanning(true)
				updateNotification("Bluetooth Error"); broadcastState(); return START_STICKY
			}
		}
		when (action) {
			ACTION_START_PINGING -> startAdvertising()
			ACTION_STOP_PINGING -> stopAdvertising()
			ACTION_STOP_SERVICE -> { stopService(); return START_NOT_STICKY }
			null -> { if (!isScanningNow) startScanning(); broadcastState() }
		}
		return START_STICKY
	}

	@SuppressLint("MissingPermission")
	private fun startScanning() {
		// ... (permission checks) ...
		if (isScanningNow) { Log.d(TAG, "Scan already active."); return }
		if (scanner == null) { /* ... handle error ... */ return }

		val scanFilters = mutableListOf<ScanFilter>()

		// *** THIS IS THE PRIMARY FILTER ***
		// Create a filter for your specific Manufacturer ID.
		// The second argument (manufacturerDataMask) can be null if you want to match any data
		// for this manufacturer ID, or you can provide a mask if you only care about certain bits
		// in the manufacturer data matching. For now, matching the ID is enough.
		val manufacturerData = ByteArray(0) // Empty data, just match ID
		val manufacturerDataMask = ByteArray(0) // Empty mask

		scanFilters.add(
			ScanFilter.Builder()
				.setManufacturerData(BleConstants.BLUEM_MANUFACTURER_ID, manufacturerData, manufacturerDataMask)
				.build()
		)
		Log.i(TAG, "Scan filter set for Manufacturer ID: ${BleConstants.BLUEM_MANUFACTURER_ID.toString(16)}")


		// You could also add a filter for your Service UUID if devices might advertise that too,
		// but for custom data, ManufacturerData is usually the primary way.
		// scanFilters.add(ScanFilter.Builder().setServiceUuid(BleConstants.SERVICE_UUID).build())

		val scanSettings = ScanSettings.Builder()
			.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // For responsiveness
			.setReportDelay(0) // Report results immediately (no batching for this strategy)
			.build()

		try {
			Log.i(TAG, "Attempting to start BLE Scan with aggressive filter...")
			scanner?.startScan(scanFilters, scanSettings, leScanCallback)
			// isScanningNow state will be set in the callback
		} catch (e: Exception) {
			Log.e(TAG, "Exception starting BLE scan: ${e.message}", e); isScanningNow = false
			showToast("Scan Start Error"); updateNotificationBasedOnState(); broadcastState()
		}
	}

	@SuppressLint("MissingPermission")
	private fun stopScanning(force: Boolean = false) {
		if (!isScanningNow && !force) { Log.d(TAG, "Scan not active or not forced."); return }
		if (scanner == null) { Log.w(TAG, "Scanner null, cannot stop."); if(isScanningNow){isScanningNow=false;updateNotificationBasedOnState();broadcastState()}; return }
		try {
			Log.i(TAG, "Attempting to stop BLE Scan...")
			scanner?.stopScan(leScanCallback); isScanningNow = false // Assume stopped
		} catch (e: Exception) {
			Log.e(TAG, "Exception stopping scan: ${e.message}", e); isScanningNow = false
		} finally {
			// showToast("Scan stopped."); // Can be noisy if called frequently
			updateNotificationBasedOnState(); broadcastState()
		}
	}

	private val leScanCallback = object : ScanCallback() {
		@SuppressLint("MissingPermission")
		override fun onScanResult(callbackType: Int, result: ScanResult?) {
			super.onScanResult(callbackType, result)
			if (!isScanningNow) { isScanningNow = true; Log.i(TAG,"Scan active (first result)."); updateNotificationBasedOnState(); broadcastState() }

			result?.device?.let { device ->
				val deviceAddress = device.address; val rssi = result.rssi
				var bleName: String? = result.scanRecord?.deviceName
				if (bleName == null && hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
					try { bleName = device.name } catch (se: SecurityException) { Log.w(TAG, "No BT Connect for name") }
				}

				var parsedProfile: ParsedProfileData? = null
				val manufacturerDataBytes = result.scanRecord?.getManufacturerSpecificData(BleConstants.BLUEM_MANUFACTURER_ID)

				if (manufacturerDataBytes != null && manufacturerDataBytes.isNotEmpty()) {
					Log.d(TAG, "Raw ManuData from $deviceAddress for ID ${BleConstants.BLUEM_MANUFACTURER_ID.toString(16)}: ${manufacturerDataBytes.toHex()}")
					if (manufacturerDataBytes[0] == BleConstants.BLUEM_PROTOCOL_ID_PROFILE_PING) {
						// Pass the payload *after* the protocol ID byte for parsing
						parsedProfile = parseProfilePingPayload(manufacturerDataBytes.drop(1).toByteArray())
						if (parsedProfile != null) {
							Log.i(TAG, "Parsed Profile Ping from ${bleName ?: deviceAddress}: BG Idx ${parsedProfile.bloodGroupIndex}, Loc ${parsedProfile.latitude.format(3)},${parsedProfile.longitude.format(3)}")
						} else { Log.w(TAG, "Failed to parse Bluem profile payload from $deviceAddress") }
					} else { Log.w(TAG, "Unknown Bluem protocol ID ${manufacturerDataBytes[0]} from $deviceAddress") }
				}

				val pingIntent = Intent(ACTION_PING_RECEIVED).apply {
					putExtra(EXTRA_DEVICE_ADDRESS, deviceAddress)
					putExtra(EXTRA_BLE_DEVICE_NAME, bleName)
					putExtra(EXTRA_RSSI, rssi)
					parsedProfile?.let { // Add individual fields to intent
						putExtra(EXTRA_PROFILE_BLOOD_GROUP_IDX, it.bloodGroupIndex)
						putExtra(EXTRA_PROFILE_HAS_PHONE, it.hasPhoneNumberFlag)
						putExtra(EXTRA_PROFILE_LATITUDE, it.latitude)
						putExtra(EXTRA_PROFILE_LONGITUDE, it.longitude)
						putExtra(EXTRA_PROFILE_PHONE_SUFFIX, it.phoneSuffixValue)
						putExtra(EXTRA_PROFILE_SEQ_TIME, it.sequenceOrTimeValue)
					}
				}
				localBroadcastManager.sendBroadcast(pingIntent)
			}
		}
		override fun onBatchScanResults(results: MutableList<ScanResult>?) { /* Implement if using batching */ }
		override fun onScanFailed(errorCode: Int) { Log.e(TAG, "Scan Failed! Code: $errorCode"); isScanningNow = false; showToast("Scan Failed: $errorCode"); updateNotificationBasedOnState(); broadcastState() }
	}

	@SuppressLint("MissingPermission")
	private fun startAdvertising() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
			showToast("Error: Missing Advertise Permission"); return
		}
		if (isAdvertisingNow) { Log.d(TAG, "Advertising already active."); return }
		if (advertiser == null) { Log.e(TAG, "Advertiser null."); if(isAdvertisingNow){isAdvertisingNow=false;updateNotificationBasedOnState();broadcastState()}; return }

		val manufacturerPayloadWithProtocolId = buildProfilePingPayload() // Includes protocol ID
		if (manufacturerPayloadWithProtocolId == null) {
			Log.e(TAG, "Failed to build adv payload."); showToast("Error: Ping data incomplete")
			if(isAdvertisingNow){isAdvertisingNow=false;updateNotificationBasedOnState();broadcastState()}
			return
		}

		val settings = AdvertiseSettings.Builder()
			.setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
			.setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).setConnectable(false).build()
		val advertiseData = AdvertiseData.Builder()
			.setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
			.addManufacturerData(BleConstants.BLUEM_MANUFACTURER_ID, manufacturerPayloadWithProtocolId)
			.build()

		try {
			Log.i(TAG, "Starting Adv with ManuID ${BleConstants.BLUEM_MANUFACTURER_ID.toString(16)} and Full Payload: ${manufacturerPayloadWithProtocolId.toHex()}")
			advertiser?.startAdvertising(settings, advertiseData, advertiseCallback)
		} catch (e: Exception) {
			Log.e(TAG, "Exception starting adv: ${e.message}", e); isAdvertisingNow = false
			showToast("Pinging Start Error"); updateNotificationBasedOnState(); broadcastState()
		}
	}

	// Payload built here INCLUDES our Bluem Protocol ID as the FIRST byte
	private fun buildProfilePingPayload(): ByteArray? {
		val profilePrefs = getSharedPreferences(ProfileFragment.SHARED_PREFS_PROFILE, Context.MODE_PRIVATE)
		val phoneNumberFull = profilePrefs.getString(ProfileFragment.KEY_PHONE_NUMBER, "") ?: ""
		val phoneSuffixStr = phoneNumberFull.takeLast(7).filter { it.isDigit() } // Last 7 digits
		val phoneSuffixLong = phoneSuffixStr.toLongOrNull() ?: 0L
		val bloodGroupPosition = profilePrefs.getInt(ProfileFragment.KEY_BLOOD_GROUP_POSITION, 8) // 8 = Unknown
		val latString = profilePrefs.getString(ProfileFragment.KEY_LATITUDE, null)
		val lonString = profilePrefs.getString(ProfileFragment.KEY_LONGITUDE, null)

		val latitude = latString?.toDoubleOrNull() ?: 91.0 // Invalid placeholder for latitude
		val longitude = lonString?.toDoubleOrNull() ?: 181.0 // Invalid placeholder for longitude

		// Payload structure:
		// Byte 0: Protocol ID (ours, Bluem)
		// Byte 1: Blood Group (4 bits), HasPhone Flag (1 bit), Reserved (3 bits)
		// Byte 2-3: Latitude (Short, scaled * 100)
		// Byte 4-5: Longitude (Short, scaled * 100)
		// Byte 6-9: Phone Suffix (Int, last 7 digits)
		// Byte 10: Sequence/Time (Byte)
		// Total = 11 bytes.
		try {
			val buffer = ByteBuffer.allocate(11) // Size of our custom data section
			buffer.order(ByteOrder.LITTLE_ENDIAN)

			buffer.put(BleConstants.BLUEM_PROTOCOL_ID_PROFILE_PING)

			var bloodAndFlags: Int = bloodGroupPosition and 0x0F
			if (phoneSuffixStr.isNotEmpty()) {
				bloodAndFlags = bloodAndFlags or 0x10 // Set 5th bit (HasPhone flag)
			}
			buffer.put(bloodAndFlags.toByte())

			buffer.putShort((latitude * 100.0).roundToInt().toShort())
			buffer.putShort((longitude * 100.0).roundToInt().toShort())
			buffer.putInt(phoneSuffixLong.toInt()) // Max 7-digit number fits in Int

			val sequenceByte = (System.currentTimeMillis() / 1000L % 256L).toByte() // Simple rolling sequence
			buffer.put(sequenceByte)

			return buffer.array()
		} catch (e: Exception) {
			Log.e(TAG, "Error encoding payload: ${e.message}", e); return null
		}
	}

	// payloadAfterProtocolId is the byte array *after* the Bluem Protocol ID byte
	private fun parseProfilePingPayload(payloadAfterProtocolId: ByteArray): ParsedProfileData? {
		// Expected size for this part: Blood/Flags(1) + Lat(2) + Lon(2) + Phone(4) + Seq(1) = 10 bytes
		if (payloadAfterProtocolId.size < 10) {
			Log.w(TAG, "Profile payload section too short: ${payloadAfterProtocolId.size}, expected 10.")
			return null
		}
		try {
			val buffer = ByteBuffer.wrap(payloadAfterProtocolId).order(ByteOrder.LITTLE_ENDIAN)
			val bloodAndFlags = buffer.get()
			val bloodGroupIdx = bloodAndFlags.toInt() and 0x0F
			val hasPhoneFlag = (bloodAndFlags.toInt() and 0x10) != 0
			val lat = buffer.short.toDouble() / 100.0
			val lon = buffer.short.toDouble() / 100.0
			val phoneSfx = buffer.int.toLong() // Read as int, then to long for consistency
			val seqTime = buffer.get()
			return ParsedProfileData(bloodGroupIdx, hasPhoneFlag, lat, lon, phoneSfx, seqTime)
		} catch (e: Exception) {
			Log.e(TAG, "Error parsing profile payload section: ${e.message}", e); return null
		}
	}

	@SuppressLint("MissingPermission")
	private fun stopAdvertising(force: Boolean = false) {
		if (!isAdvertisingNow && !force) { Log.d(TAG, "Not advertising or not forced."); return }
		if (advertiser == null) { Log.w(TAG, "Advertiser null."); if(isAdvertisingNow){isAdvertisingNow=false;updateNotificationBasedOnState();broadcastState()}; return }
		try {
			Log.i(TAG, "Attempting to stop BLE Advertising...")
			advertiser?.stopAdvertising(advertiseCallback); isAdvertisingNow = false
		} catch (e: Exception) {
			Log.e(TAG, "Exception stopping adv: ${e.message}", e); isAdvertisingNow = false
		} finally {
			showToast("Pinging stopped."); updateNotificationBasedOnState(); broadcastState()
		}
	}

	private val advertiseCallback = object : AdvertiseCallback() {
		override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
			super.onStartSuccess(settingsInEffect); Log.i(TAG, "Adv started (Callback).")
			if (!isAdvertisingNow) { isAdvertisingNow = true; /*showToast("Pinging Active")*/; updateNotificationBasedOnState(); broadcastState() }
		}
		override fun onStartFailure(errorCode: Int) {
			super.onStartFailure(errorCode); Log.e(TAG, "Adv failed! Code: $errorCode")
			isAdvertisingNow = false; showToast("Pinging Failed: $errorCode"); updateNotificationBasedOnState(); broadcastState()
		}
	}

	private fun createNotificationChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val name = "Bluem Beacon"; val desc = "Keeps Bluem SOS beacon active"
			val importance = NotificationManager.IMPORTANCE_LOW
			val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply { description = desc; setSound(null,null); enableVibration(false)}
			(getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
		}
	}

	private fun createNotification(contentText: String): Notification {
		val icon = R.drawable.ic_launcher_foreground // Ensure this exists!
		return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
			.setContentTitle("Bluem SOS Beacon").setContentText(contentText)
			.setSmallIcon(icon).setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW)
			.setVisibility(NotificationCompat.VISIBILITY_PUBLIC).build()
	}

	private fun updateNotificationBasedOnState() {
		val text = when {
			isAdvertisingNow && isScanningNow -> "Pinging & Listening"
			isScanningNow -> "Listening for Pings"
			isAdvertisingNow -> "Pinging (Scan Off?)" else -> "Service Idle / BT Error"
		}
		updateNotification(text)
	}

	private fun updateNotification(contentText: String) {
		try {
			(getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, createNotification(contentText))
		} catch (e: Exception) { Log.e(TAG, "Error updating notification: ${e.message}", e) }
	}

	private fun broadcastState() {
		val intent = Intent(ACTION_STATE_CHANGED).apply {
			putExtra(EXTRA_IS_ADVERTISING, isAdvertisingNow); putExtra(EXTRA_IS_SCANNING, isScanningNow)
		}
		localBroadcastManager.sendBroadcast(intent)
		// Log.d(TAG, "Broadcast: Adv=$isAdvertisingNow, Scan=$isScanningNow") // Can be noisy
	}

	private fun showToast(message: String) { mainHandler.post { Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show() } }
	private fun hasPermission(permission: String): Boolean = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
	private fun Double.format(digits: Int) = "%.${digits}f".format(this) // Helper for logging doubles

	override fun onDestroy() { super.onDestroy(); Log.i(TAG, "Service onDestroy"); stopAdvertising(true); stopScanning(true); Log.i(TAG,"Service destroyed.") }
	private fun stopService() { Log.i(TAG,"Stopping Service"); stopAdvertising(true); stopScanning(true); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }

	// Helper for logging byte arrays
	fun ByteArray.toHex(): String = joinToString(separator = "") { eachByte -> "%02X".format(eachByte) }
}