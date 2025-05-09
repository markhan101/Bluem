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
import android.widget.Toast // Added Toast
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
	private var bluetoothAdapter: BluetoothAdapter? = null // Made nullable for safety
	private var advertiser: BluetoothLeAdvertiser? = null
	private var scanner: BluetoothLeScanner? = null

	private lateinit var localBroadcastManager: LocalBroadcastManager
	private val mainHandler = Handler(Looper.getMainLooper()) // For Toasts from service

	companion object {
		const val ACTION_START_PINGING = "com.example.bluem.action.START_PINGING"
		const val ACTION_STOP_PINGING = "com.example.bluem.action.STOP_PINGING"
		const val ACTION_STOP_SERVICE = "com.example.bluem.action.STOP_SERVICE"
		const val ACTION_STATE_CHANGED = "com.example.bluem.action.STATE_CHANGED"
		const val EXTRA_IS_ADVERTISING = "com.example.bluem.extra.IS_ADVERTISING"
		const val EXTRA_IS_SCANNING = "com.example.bluem.extra.IS_SCANNING"
	}

	inner class LocalBinder : Binder() {
		fun getService(): BluemEmergencyService = this@BluemEmergencyService
		fun isCurrentlyAdvertising(): Boolean = isAdvertisingNow
		fun isCurrentlyScanning(): Boolean = isScanningNow
	}

	override fun onBind(intent: Intent?): IBinder? {
		Log.d(TAG, "Service onBind")
		broadcastState() // Send current state to newly bound client
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

		if (!initBluetoothAndContinue()) { // Renamed and modified flow
			// Error handled and notification updated within initBluetoothAndContinue
			return // Don't proceed if critical BT setup failed
		}
		// If initBluetoothAndContinue was successful, scanning should be started within it.
	}

	// Combined init and initial actions
	private fun initBluetoothAndContinue(): Boolean {
		if (!setupBluetoothComponents()) {
			updateNotification("Bluetooth Error: Not available")
			// Potentially stopSelf if BT is critical and unrecoverable
			return false
		}
		createNotificationChannel() // Create channel AFTER confirming BT might work
		startForeground(NOTIFICATION_ID, createNotification("Initializing..."))
		startScanning() // Start scanning by default
		return true
	}


	@SuppressLint("MissingPermission")
	private fun setupBluetoothComponents(): Boolean {
		// Check for BT permissions first - crucial
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
				ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
				ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
				Log.e(TAG, "Missing core Bluetooth permissions (SDK 31+). Cannot initialize.")
				showToast("Error: Missing Bluetooth Permissions")
				return false
			}
		} else {
			// Pre-SDK31, BLUETOOTH and BLUETOOTH_ADMIN are manifest only, ACCESS_FINE_LOCATION for scan
			if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
				Log.e(TAG, "Missing Fine Location permission for BLE Scan (Pre-SDK 31). Cannot initialize.")
				showToast("Error: Missing Location Permission for Scan")
				return false
			}
		}


		bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
		bluetoothAdapter = bluetoothManager.adapter

		if (bluetoothAdapter == null) {
			Log.e(TAG, "Bluetooth is not supported on this device.")
			showToast("Bluetooth not supported")
			return false
		}
		if (bluetoothAdapter?.isEnabled == false) {
			Log.e(TAG, "Bluetooth is not enabled.")
			showToast("Bluetooth not enabled")
			return false // Indicate failure, prompt user from Activity
		}

		advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
		if (advertiser == null && bluetoothAdapter?.isMultipleAdvertisementSupported == true) { // Check if adv is supported
			Log.w(TAG, "BLE Advertising not supported, but adapter says it might be? Odd.")
			// Proceed cautiously or treat as not supported
		} else if (advertiser == null) {
			Log.e(TAG, "BLE Advertising not supported.")
			showToast("BLE Advertising not supported")
			// For this app, advertising is key. If not available, it's a critical failure.
			// return false; // Decide if this is a fatal error for the service
		}


		scanner = bluetoothAdapter?.bluetoothLeScanner
		if (scanner == null) {
			Log.e(TAG, "BLE Scanning not supported.")
			showToast("BLE Scanning not supported")
			return false // Scanning is also key
		}

		Log.d(TAG, "Bluetooth Components Initialized Successfully.")
		return true
	}


	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		val action = intent?.action
		Log.d(TAG, "Service onStartCommand, Action: $action")

		// Re-check BT status, especially if it might have been turned off externally
		if (bluetoothAdapter == null || bluetoothAdapter?.isEnabled == false) {
			Log.w(TAG, "Bluetooth unavailable when processing command: $action. Attempting re-init.")
			if (!setupBluetoothComponents()) { // Try to re-initialize
				Log.e(TAG, "Re-initialization of Bluetooth failed. Operations cannot proceed.")
				if (isAdvertisingNow) stopAdvertising(true) // Force stop if somehow running
				if (isScanningNow) stopScanning(true)
				updateNotification("Bluetooth Error")
				broadcastState()
				return START_STICKY // Hope BT comes back
			}
			// If re-init successful, proceed with command
		}


		when (action) {
			ACTION_START_PINGING -> startAdvertising()
			ACTION_STOP_PINGING -> stopAdvertising()
			ACTION_STOP_SERVICE -> {
				Log.i(TAG, "Received Stop Service command")
				stopService()
				return START_NOT_STICKY
			}
			null -> { // Service starting or restarting
				Log.d(TAG, "Service starting/restarting. Current scan: $isScanningNow, adv: $isAdvertisingNow")
				if (!isScanningNow) { startScanning() }
				// If advertising was on and service restarted, it's likely stopped.
				// UI will re-sync via binding/broadcast from Activity.
				// For now, just ensure scanning.
				broadcastState() // Send current state on restart
			}
		}
		return START_STICKY
	}

	@SuppressLint("MissingPermission")
	private fun startScanning() {
		if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			Log.e(TAG, "Cannot start scan: Missing BLUETOOTH_SCAN permission.")
			showToast("Error: Missing Scan Permission")
			return
		}
		if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
			Log.e(TAG, "Cannot start scan: Missing ACCESS_FINE_LOCATION permission.")
			showToast("Error: Missing Location Permission for Scan")
			return
		}

		if (isScanningNow) {
			Log.d(TAG, "Scanning already active.")
			// broadcastState() // Already active, state should be known
			return
		}
		if (scanner == null) {
			Log.e(TAG, "Scanner not available to start scanning.")
			isScanningNow = false // Ensure state
			updateNotificationBasedOnState()
			broadcastState()
			return
		}

		val scanFilters = listOf(ScanFilter.Builder().setServiceUuid(BleConstants.SERVICE_UUID).build())
		val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

		try {
			Log.i(TAG, "Attempting to start BLE Scan...")
			showToast("Starting Scan...")
			scanner?.startScan(scanFilters, scanSettings, leScanCallback)
			// isScanningNow will be set to true in leScanCallback on success (or false on failure)
			// For now, assume it might take time, don't set isScanningNow = true here yet.
			// The callback will handle the state.
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
		if (!isScanningNow && !force) { // Only stop if active, unless forced
			Log.d(TAG, "Scanning not active or not forced.")
			return
		}
		if (scanner == null) {
			Log.w(TAG, "Scanner unavailable, cannot stop scan.")
			isScanningNow = false // Correct state
			updateNotificationBasedOnState()
			broadcastState()
			return
		}

		try {
			Log.i(TAG, "Attempting to stop BLE Scan...")
			showToast("Stopping Scan...")
			scanner?.stopScan(leScanCallback)
			isScanningNow = false // Assume stop is synchronous for state purposes
		} catch (e: Exception) {
			Log.e(TAG, "Exception stopping BLE scan: ${e.message}", e)
			isScanningNow = false // Assume stopped on error for safety
			showToast("Scan Stop Error: ${e.localizedMessage}")
		} finally {
			updateNotificationBasedOnState()
			broadcastState()
		}
	}

	private val leScanCallback = object : ScanCallback() {
		override fun onScanResult(callbackType: Int, result: ScanResult?) {
			super.onScanResult(callbackType, result)
			// Set scanning to true here if it's the first successful result
			if (!isScanningNow) {
				isScanningNow = true
				Log.i(TAG,"Scan successfully started (first result).")
				showToast("Scan Started: Receiving signals")
				updateNotificationBasedOnState()
				broadcastState()
			}

			result?.device?.let { device ->
				val deviceName = if (ContextCompat.checkSelfPermission(this@BluemEmergencyService, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
					device.name ?: "Unknown"
				} else { "Unknown (No Connect Perm)" }
				Log.d(TAG, "Ping received from: Name: $deviceName, Address: ${device.address}, RSSI: ${result.rssi}")
				// Display a toast for *every* ping for debugging, can be noisy
				showToast("Ping from: ${device.address}")
				// TODO: Add logic to parse ScanRecord for specific data from your service UUID
				// result.scanRecord?.getServiceData(BleConstants.SERVICE_UUID)
			}
		}

		override fun onBatchScanResults(results: MutableList<ScanResult>?) {
			super.onBatchScanResults(results)
			// Similar to onScanResult, update isScanningNow if it's the first batch
			if (!isScanningNow && results?.isNotEmpty() == true) {
				isScanningNow = true
				Log.i(TAG,"Scan successfully started (first batch result).")
				showToast("Scan Started: Receiving batch signals")
				updateNotificationBasedOnState()
				broadcastState()
			}
			Log.d(TAG, "Batch scan results: ${results?.size ?: 0}")
			results?.forEach { result ->
				result.device?.let { device ->
					Log.d(TAG, "Batch Ping from: ${device.address}")
				}
			}
		}

		override fun onScanFailed(errorCode: Int) {
			super.onScanFailed(errorCode)
			Log.e(TAG, "BLE Scan Failed! Error code: $errorCode")
			isScanningNow = false
			val errorReason = when (errorCode) {
				SCAN_FAILED_ALREADY_STARTED -> "Already Started"
				SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App Registration Failed"
				SCAN_FAILED_INTERNAL_ERROR -> "Internal Error"
				SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature Unsupported"
				else -> "Unknown Error"
			}
			showToast("Scan Failed: $errorReason ($errorCode)")
			updateNotificationBasedOnState()
			broadcastState()
		}
	}

	@SuppressLint("MissingPermission")
	private fun startAdvertising() {
		if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			Log.e(TAG, "Cannot start advertising: Missing BLUETOOTH_ADVERTISE permission.")
			showToast("Error: Missing Advertise Permission")
			return
		}

		if (isAdvertisingNow) {
			Log.d(TAG, "Advertising already active.")
			// broadcastState() // Already active
			return
		}
		if (advertiser == null) {
			Log.e(TAG, "Advertiser not available to start advertising.")
			isAdvertisingNow = false // Ensure state
			updateNotificationBasedOnState()
			broadcastState()
			return
		}

		val advertiseSettings = AdvertiseSettings.Builder()
			.setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
			.setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
			.setConnectable(false).build()

		val advertiseData = AdvertiseData.Builder()
			.setIncludeDeviceName(false)
			.addServiceUuid(BleConstants.SERVICE_UUID).build()

		try {
			Log.i(TAG, "Attempting to start BLE Advertising...")
			showToast("Starting Pinging...")
			// isAdvertisingNow will be set to true in advertiseCallback onStartSuccess
			advertiser?.startAdvertising(advertiseSettings, advertiseData, advertiseCallback)
		} catch (e: Exception) {
			Log.e(TAG, "Exception starting BLE advertising: ${e.message}", e)
			isAdvertisingNow = false
			showToast("Pinging Start Error: ${e.localizedMessage}")
			updateNotificationBasedOnState()
			broadcastState()
		}
	}

	@SuppressLint("MissingPermission")
	private fun stopAdvertising(force: Boolean = false) { // Added force parameter
		if (!isAdvertisingNow && !force) { // Only stop if active unless forced
			Log.d(TAG, "Not currently advertising or not forced to stop.")
			// If state is true but shouldn't be, correct it
			if (isAdvertisingNow) {
				isAdvertisingNow = false
				updateNotificationBasedOnState()
				broadcastState()
			}
			return
		}
		if (advertiser == null) {
			Log.w(TAG, "Advertiser unavailable, cannot stop advertising.")
			isAdvertisingNow = false // Correct state
			updateNotificationBasedOnState()
			broadcastState()
			return
		}

		try {
			Log.i(TAG, "Attempting to stop BLE Advertising...")
			showToast("Stopping Pinging...")
			advertiser?.stopAdvertising(advertiseCallback)
			// Crucially, set the state to false *immediately* after initiating the stop.
			// The callback for stop is not as reliable/existent for success.
			isAdvertisingNow = false
		} catch (e: Exception) {
			Log.e(TAG, "Exception stopping BLE advertising: ${e.message}", e)
			isAdvertisingNow = false // Assume stopped on error for safety
			showToast("Pinging Stop Error: ${e.localizedMessage}")
		} finally {
			// This block will always execute, ensuring state and UI are updated.
			updateNotificationBasedOnState()
			broadcastState()
		}
	}

	private val advertiseCallback = object : AdvertiseCallback() {
		override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
			super.onStartSuccess(settingsInEffect)
			Log.i(TAG, "BLE Advertising started successfully (Callback).")
			if (!isAdvertisingNow) { // If not already set by optimistic update
				isAdvertisingNow = true
				showToast("Pinging Started (Confirmed)")
				updateNotificationBasedOnState()
				broadcastState()
			}
		}

		override fun onStartFailure(errorCode: Int) {
			super.onStartFailure(errorCode)
			Log.e(TAG, "BLE Advertising failed to start (Callback)! Error code: $errorCode")
			isAdvertisingNow = false
			val errorReason = when (errorCode) {
				ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data Too Large"
				ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too Many Advertisers"
				ADVERTISE_FAILED_ALREADY_STARTED -> "Already Started (odd)"
				ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal Error"
				ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature Unsupported"
				else -> "Unknown Error"
			}
			showToast("Pinging Failed: $errorReason ($errorCode)")
			updateNotificationBasedOnState()
			broadcastState()
		}
	}

	// --- Notification Helpers ---
	private fun createNotificationChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val name = "Bluem Beacon Service"
			val descriptionText = "Keeps Bluem SOS beacon active"
			val importance = NotificationManager.IMPORTANCE_LOW // Low to be less intrusive
			val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
				description = descriptionText
				setSound(null, null) // No sound
				enableVibration(false) // No vibration
			}
			(getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
			Log.d(TAG, "Notification channel created/updated.")
		}
	}

	private fun createNotification(contentText: String): Notification {
		// IMPORTANT: Make sure 'R.drawable.ic_notification_bluem' exists in your drawable folders!
		// It should be a small, single-color icon suitable for the status bar.
		val icon = R.drawable.ic_launcher_foreground // Replace with your actual notification icon if different

		// TODO: Create PendingIntent to open MainActivity when notification is clicked
		// val mainActivityIntent = Intent(this, MainActivity::class.java).apply {
		//     flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
		// }
		// val pendingIntent: PendingIntent = PendingIntent.getActivity(this, 0, mainActivityIntent, PendingIntent.FLAG_IMMUTABLE)

		return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
			.setContentTitle("Bluem SOS Beacon")
			.setContentText(contentText)
			.setSmallIcon(icon)
			// .setContentIntent(pendingIntent) // Add when ready
			.setOngoing(true)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Or SECRET/PRIVATE as needed
			.build()
	}

	private fun updateNotificationBasedOnState() {
		val text = when {
			isAdvertisingNow && isScanningNow -> "Pinging & Listening"
			isScanningNow -> "Listening for Pings"
			isAdvertisingNow -> "Pinging (Scan may be off)" // Edge case, normally scan is always on
			else -> "Service Idle or Bluetooth Error" // More generic for unhandled states
		}
		updateNotification(text)
	}

	private fun updateNotification(contentText: String) {
		try {
			val notification = createNotification(contentText)
			(getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
		} catch (e: Exception) {
			Log.e(TAG, "Error updating notification: ${e.message}", e)
			// This can happen if context is bad or channel not set up, esp during service shutdown
		}
	}

	// --- State Broadcasting ---
	private fun broadcastState() {
		val intent = Intent(ACTION_STATE_CHANGED).apply {
			putExtra(EXTRA_IS_ADVERTISING, isAdvertisingNow)
			putExtra(EXTRA_IS_SCANNING, isScanningNow)
		}
		localBroadcastManager.sendBroadcast(intent)
		Log.d(TAG, "Broadcast sent: Adv=$isAdvertisingNow, Scan=$isScanningNow")
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
		Log.d(TAG, "Service onDestroy: Cleaning up...")
		stopAdvertising(true) // Force stop advertising
		stopScanning(true)    // Force stop scanning
		// stopForeground(true) // This will be called by stopService if it's the path
		Log.i(TAG,"BluemEmergencyService destroyed.")
	}

	private fun stopService() {
		Log.i(TAG,"Stopping BluemEmergencyService now.")
		stopAdvertising(true) // Ensure operations are stopped before service dies
		stopScanning(true)
		stopForeground(true) // Remove notification
		stopSelf() // Stop the service instance
	}
}