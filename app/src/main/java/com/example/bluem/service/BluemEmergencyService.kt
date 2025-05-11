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
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.bluem.R
import com.example.bluem.ble.BleConstants

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

	companion object {
		const val ACTION_START_PINGING = "com.example.bluem.action.START_PINGING"
		const val ACTION_STOP_PINGING = "com.example.bluem.action.STOP_PINGING"
		const val ACTION_STOP_SERVICE = "com.example.bluem.action.STOP_SERVICE"
		const val ACTION_STATE_CHANGED = "com.example.bluem.action.STATE_CHANGED"
		const val EXTRA_IS_ADVERTISING = "com.example.bluem.extra.IS_ADVERTISING"
		const val EXTRA_IS_SCANNING = "com.example.bluem.extra.IS_SCANNING"

		// Actions and Extras for sending ping data to UI
		const val ACTION_PING_RECEIVED = "com.example.bluem.action.PING_RECEIVED" // Added
		const val EXTRA_DEVICE_ADDRESS = "com.example.bluem.extra.DEVICE_ADDRESS" // Added
		const val EXTRA_RSSI = "com.example.bluem.extra.RSSI"                     // Added
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
			// Error is logged, notification might be updated. Service might stop itself.
			return
		}
	}

	private fun initBluetoothAndContinue(): Boolean {
		if (!setupBluetoothComponents()) {
			// Create channel first if not already, then update notification
			createNotificationChannel() // Ensure channel exists before showing error notification
			updateNotification("Bluetooth Error: Setup Failed")
			// Consider stopping service if BT is essential and init fails repeatedly
			// stopSelf(); // Or schedule a retry
			return false
		}
		createNotificationChannel()
		startForeground(NOTIFICATION_ID, createNotification("Initializing Service..."))
		startScanning() // Start scanning by default after successful init
		return true
	}

	@SuppressLint("MissingPermission")
	private fun setupBluetoothComponents(): Boolean {
		// Permission checks (ensure these are sufficient for your min/target SDKs)
		val hasBtConnect = hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
		val hasBtScan = hasPermission(Manifest.permission.BLUETOOTH_SCAN)
		val hasBtAdvertise = hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
		val hasFineLocation = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
			if (!hasBtConnect || !hasBtScan || !hasBtAdvertise) {
				Log.e(TAG, "Missing core Bluetooth permissions (SDK 31+). Connect:$hasBtConnect, Scan:$hasBtScan, Adv:$hasBtAdvertise")
				showToast("Error: Bluetooth Permissions Missing")
				return false
			}
		} else { // Pre-Android 12
			if (!hasFineLocation) { // ACCESS_FINE_LOCATION is key for scanning
				Log.e(TAG, "Missing Fine Location permission for BLE Scan (Pre-SDK 31).")
				showToast("Error: Location Permission Missing for Scan")
				return false
			}
			// BLUETOOTH and BLUETOOTH_ADMIN are manifest-only pre-S, no runtime check needed here for them
		}

		try {
			bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
			bluetoothAdapter = bluetoothManager.adapter
		} catch (e: Exception) {
			Log.e(TAG, "Failed to get BluetoothManager or Adapter: ${e.message}", e)
			showToast("Bluetooth system service error")
			return false
		}


		if (bluetoothAdapter == null) {
			Log.e(TAG, "Bluetooth is not supported on this device.")
			showToast("Bluetooth not supported")
			return false
		}
		if (bluetoothAdapter?.isEnabled == false) {
			Log.e(TAG, "Bluetooth is not enabled.")
			showToast("Please enable Bluetooth") // Activity should prompt, service just informs
			return false
		}

		advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
		if (advertiser == null) {
			// Check if the device supports advertising at all
			if (bluetoothAdapter?.isMultipleAdvertisementSupported == false &&
				bluetoothAdapter?.isOffloadedFilteringSupported == false && // another hint for BLE support
				bluetoothAdapter?.isOffloadedScanBatchingSupported == false) {
				Log.e(TAG, "BLE Advertising seems generally unsupported on this device.")
				showToast("BLE Advertising Not Supported")
				// return false; // For this app, this might be a fatal error
			} else {
				Log.w(TAG, "BluetoothLeAdvertiser is null. Device might not support BLE advertising or there's an issue.")
				showToast("Warning: BLE Advertising unavailable")
				// return false; // Decide if critical
			}
		}

		scanner = bluetoothAdapter?.bluetoothLeScanner
		if (scanner == null) {
			Log.e(TAG, "BLE Scanning not supported (BluetoothLeScanner is null).")
			showToast("BLE Scanning Not Supported")
			return false
		}

		Log.d(TAG, "Bluetooth Components Initialized Successfully.")
		return true
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		val action = intent?.action
		Log.d(TAG, "Service onStartCommand, Action: $action")

		if (bluetoothAdapter == null || bluetoothAdapter?.isEnabled == false) {
			Log.w(TAG, "BT unavailable for command: $action. Attempting re-init...")
			if (!setupBluetoothComponents()) {
				Log.e(TAG, "BT re-init failed. Operations halted.")
				if (isAdvertisingNow) stopAdvertising(true)
				if (isScanningNow) stopScanning(true)
				updateNotification("Bluetooth Error")
				broadcastState()
				return START_STICKY
			}
			// If re-init successful, continue processing command
		}

		when (action) {
			ACTION_START_PINGING -> startAdvertising()
			ACTION_STOP_PINGING -> stopAdvertising()
			ACTION_STOP_SERVICE -> {
				Log.i(TAG, "Received Stop Service command")
				stopService()
				return START_NOT_STICKY
			}
			null -> {
				Log.d(TAG, "Service starting/restarting. Scan: $isScanningNow, Adv: $isAdvertisingNow")
				if (!isScanningNow) startScanning()
				broadcastState() // Always send current state on start/restart
			}
		}
		return START_STICKY
	}

	@SuppressLint("MissingPermission")
	private fun startScanning() {
		// Redundant permission check since setupBluetoothComponents should cover it, but good for safety
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasPermission(Manifest.permission.BLUETOOTH_SCAN)) return
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return


		if (isScanningNow) {
			Log.d(TAG, "Scanning already active.")
			return
		}
		if (scanner == null) {
			Log.e(TAG, "Scanner null, cannot start scan.")
			isScanningNow = false; updateNotificationBasedOnState(); broadcastState()
			return
		}

		val scanFilters = listOf(ScanFilter.Builder().setServiceUuid(BleConstants.SERVICE_UUID).build())
		val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

		try {
			Log.i(TAG, "Attempting to start BLE Scan...")
			// isScanningNow state will be set by the callback (onScanResult or onScanFailed)
			scanner?.startScan(scanFilters, scanSettings, leScanCallback)
			showToast("Scan initiated...") // Indicate attempt
		} catch (e: Exception) {
			Log.e(TAG, "Exception starting BLE scan: ${e.message}", e)
			isScanningNow = false
			showToast("Scan Start Error: ${e.localizedMessage}")
			updateNotificationBasedOnState()
			broadcastState()
		}
	}

	@SuppressLint("MissingPermission")
	private fun stopScanning(force: Boolean = false) {
		if (!isScanningNow && !force) {
			Log.d(TAG, "Scanning not active or not forced to stop.")
			return
		}
		if (scanner == null) {
			Log.w(TAG, "Scanner null, cannot stop scan.")
			if(isScanningNow) {isScanningNow = false; updateNotificationBasedOnState(); broadcastState()}
			return
		}

		try {
			Log.i(TAG, "Attempting to stop BLE Scan...")
			scanner?.stopScan(leScanCallback)
			isScanningNow = false // Assume stop is successful for state management
			showToast("Scan stopped.")
		} catch (e: Exception) {
			Log.e(TAG, "Exception stopping BLE scan: ${e.message}", e)
			isScanningNow = false // Assume stopped on error
			showToast("Scan Stop Error: ${e.localizedMessage}")
		} finally {
			updateNotificationBasedOnState()
			broadcastState()
		}
	}

	private val leScanCallback = object : ScanCallback() {
		override fun onScanResult(callbackType: Int, result: ScanResult?) {
			super.onScanResult(callbackType, result)
			if (!isScanningNow) { // First successful scan result
				isScanningNow = true
				Log.i(TAG,"Scan confirmed active (first result).")
				showToast("Scan Active: Receiving signals")
				updateNotificationBasedOnState()
				broadcastState()
			}

			result?.device?.let { device ->
				val deviceAddress = device.address
				val rssi = result.rssi
				val deviceName = if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
					try { device.name ?: "N/A" } catch (se: SecurityException) { "N/A (Perm Err)" }
				} else { "N/A (No Perm)" }

				Log.d(TAG, "Ping from: $deviceName ($deviceAddress), RSSI: $rssi")
				// showToast("Ping from: $deviceAddress") // Can be very noisy

				// Send ping data to MainActivity via LocalBroadcast
				val pingIntent = Intent(ACTION_PING_RECEIVED).apply {
					putExtra(EXTRA_DEVICE_ADDRESS, deviceAddress)
					putExtra(EXTRA_RSSI, rssi)
				}
				localBroadcastManager.sendBroadcast(pingIntent)
			}
		}

		override fun onBatchScanResults(results: MutableList<ScanResult>?) {
			super.onBatchScanResults(results)
			if (!isScanningNow && results?.isNotEmpty() == true) { // First successful batch
				isScanningNow = true
				Log.i(TAG,"Scan confirmed active (first batch result).")
				showToast("Scan Active: Receiving batch signals")
				updateNotificationBasedOnState()
				broadcastState()
			}
			results?.forEach { result -> result.device?.let { /* process batch item */ } }
		}

		override fun onScanFailed(errorCode: Int) {
			super.onScanFailed(errorCode)
			Log.e(TAG, "BLE Scan Failed! Code: $errorCode")
			isScanningNow = false
			showToast("Scan Failed: Code $errorCode")
			updateNotificationBasedOnState()
			broadcastState()
		}
	}

	@SuppressLint("MissingPermission")
	private fun startAdvertising() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) return

		if (isAdvertisingNow) {
			Log.d(TAG, "Advertising already active.")
			return
		}
		if (advertiser == null) {
			Log.e(TAG, "Advertiser null, cannot start advertising.")
			if(isAdvertisingNow) {isAdvertisingNow = false; updateNotificationBasedOnState(); broadcastState()}
			return
		}

		val settings = AdvertiseSettings.Builder()
			.setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
			.setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
			.setConnectable(false).build()
		val data = AdvertiseData.Builder()
			.setIncludeDeviceName(false)
			.addServiceUuid(BleConstants.SERVICE_UUID).build()

		try {
			Log.i(TAG, "Attempting to start BLE Advertising...")
			// State will be set by callback
			advertiser?.startAdvertising(settings, data, advertiseCallback)
			showToast("Pinging initiated...")
		} catch (e: Exception) {
			Log.e(TAG, "Exception starting BLE advertising: ${e.message}", e)
			isAdvertisingNow = false
			showToast("Pinging Start Error: ${e.localizedMessage}")
			updateNotificationBasedOnState()
			broadcastState()
		}
	}

	@SuppressLint("MissingPermission")
	private fun stopAdvertising(force: Boolean = false) {
		if (!isAdvertisingNow && !force) {
			Log.d(TAG, "Not advertising or not forced to stop.")
			return
		}
		if (advertiser == null) {
			Log.w(TAG, "Advertiser null, cannot stop advertising.")
			if(isAdvertisingNow) {isAdvertisingNow = false; updateNotificationBasedOnState(); broadcastState()}
			return
		}

		try {
			Log.i(TAG, "Attempting to stop BLE Advertising...")
			advertiser?.stopAdvertising(advertiseCallback)
			isAdvertisingNow = false // Assume stop is successful for state management
			showToast("Pinging stopped.")
		} catch (e: Exception) {
			Log.e(TAG, "Exception stopping BLE advertising: ${e.message}", e)
			isAdvertisingNow = false // Assume stopped on error
			showToast("Pinging Stop Error: ${e.localizedMessage}")
		} finally {
			updateNotificationBasedOnState()
			broadcastState()
		}
	}

	private val advertiseCallback = object : AdvertiseCallback() {
		override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
			super.onStartSuccess(settingsInEffect)
			Log.i(TAG, "BLE Advertising confirmed started (Callback).")
			if (!isAdvertisingNow) { // If not already optimistically set
				isAdvertisingNow = true
				showToast("Pinging Active (Confirmed)")
				updateNotificationBasedOnState()
				broadcastState()
			}
		}

		override fun onStartFailure(errorCode: Int) {
			super.onStartFailure(errorCode)
			Log.e(TAG, "BLE Advertising failed to start (Callback)! Code: $errorCode")
			isAdvertisingNow = false
			showToast("Pinging Failed: Code $errorCode")
			updateNotificationBasedOnState()
			broadcastState()
		}
	}

	// --- Notification Helpers ---
	private fun createNotificationChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val name = "Bluem Beacon Service"
			val descriptionText = "Keeps Bluem SOS beacon active"
			val importance = NotificationManager.IMPORTANCE_LOW
			val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
				description = descriptionText
				setSound(null, null)
				enableVibration(false)
			}
			(getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
		}
	}

	private fun createNotification(contentText: String): Notification {
		// IMPORTANT: Use a real, small, monochrome icon for notifications
		val icon = R.drawable.ic_launcher_foreground // Ensure this drawable exists!

		return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
			.setContentTitle("Bluem SOS Beacon")
			.setContentText(contentText)
			.setSmallIcon(icon)
			.setOngoing(true)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
			.build()
	}

	private fun updateNotificationBasedOnState() {
		val text = when {
			isAdvertisingNow && isScanningNow -> "Pinging & Listening"
			isScanningNow -> "Listening for Pings"
			isAdvertisingNow -> "Pinging (Scan Off?)" // Should ideally not happen if scan is always on
			else -> "Service Idle / BT Error"
		}
		updateNotification(text)
	}

	private fun updateNotification(contentText: String) {
		try {
			val notification = createNotification(contentText)
			(getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
		} catch (e: Exception) {
			Log.e(TAG, "Error updating notification: ${e.message}", e)
		}
	}

	// --- State Broadcasting ---
	private fun broadcastState() {
		val intent = Intent(ACTION_STATE_CHANGED).apply {
			putExtra(EXTRA_IS_ADVERTISING, isAdvertisingNow)
			putExtra(EXTRA_IS_SCANNING, isScanningNow)
		}
		localBroadcastManager.sendBroadcast(intent)
		Log.d(TAG, "Broadcast: Adv=$isAdvertisingNow, Scan=$isScanningNow")
	}

	// --- Utility ---
	private fun showToast(message: String) {
		mainHandler.post { Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show() }
	}

	private fun hasPermission(permission: String): Boolean {
		return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
	}

	override fun onDestroy() {
		super.onDestroy()
		Log.i(TAG, "Service onDestroy: Cleaning up...")
		stopAdvertising(true)
		stopScanning(true)
		// stopForeground is handled by stopService if that's the path taken
		Log.i(TAG,"BluemEmergencyService fully destroyed.")
	}

	private fun stopService() {
		Log.i(TAG,"Stopping BluemEmergencyService now.")
		stopAdvertising(true)
		stopScanning(true)
		stopForeground(STOP_FOREGROUND_REMOVE) // Correct way to remove notification and stop foreground
		stopSelf()
	}
}