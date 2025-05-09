package com.example.bluem.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.bluem.R
import com.example.bluem.service.BluemEmergencyService

class MainActivity : AppCompatActivity() {

	private val TAG = "MainActivity"
	private lateinit var statusTextView: TextView
	private lateinit var pingButton: Button

	@Volatile private var isServicePinging = false // More descriptive name
	@Volatile private var isServiceScanning = false // Track scanning state from service
	private var requiredPermissionsGranted = false

	private var bleService: BluemEmergencyService? = null
	@Volatile private var isBound = false

	private lateinit var localBroadcastManager: LocalBroadcastManager
	private val stateUpdateReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			if (intent.action == BluemEmergencyService.ACTION_STATE_CHANGED) {
				val advertisingState = intent.getBooleanExtra(BluemEmergencyService.EXTRA_IS_ADVERTISING, false)
				val scanningState = intent.getBooleanExtra(BluemEmergencyService.EXTRA_IS_SCANNING, false) // Get scanning state
				Log.d(TAG, "Broadcast Received: Adv=$advertisingState, Scan=$scanningState")

				isServicePinging = advertisingState
				isServiceScanning = scanningState // Update local scanning state
				updateUiBasedOnState()
			}
		}
	}

	private val requestMultiplePermissions =
		registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
			var allGranted = true
			permissions.entries.forEach { (permission, granted) ->
				if (!granted) {
					allGranted = false
					Log.e(TAG, "Permission $permission not granted.")
				} else {
					Log.d(TAG, "Permission $permission granted.")
				}
			}

			if (allGranted) {
				Log.d(TAG, "All required permissions granted.")
				requiredPermissionsGranted = true
				tryStartServiceAndBind() // Try to start/bind now
			} else {
				Log.e(TAG, "One or more permissions were denied.")
				requiredPermissionsGranted = false
				Toast.makeText(this, "Permissions are required for BLE features.", Toast.LENGTH_LONG).show()
				updateUiBasedOnState() // Update UI to reflect missing perms
			}
		}

	private val requestEnableBluetooth =
		registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
			if (result.resultCode == RESULT_OK) {
				Log.d(TAG, "Bluetooth enabled by user.")
				tryStartServiceAndBind() // Try to start/bind now
			} else {
				Log.e(TAG, "Bluetooth enabling was denied or failed.")
				Toast.makeText(this, "Bluetooth must be enabled.", Toast.LENGTH_SHORT).show()
				updateUiBasedOnState() // Update UI to reflect BT disabled
			}
		}

	private val serviceConnection = object : ServiceConnection { // Renamed for clarity
		override fun onServiceConnected(className: ComponentName, service: IBinder) {
			Log.d(TAG, "Service Bound")
			val binder = service as BluemEmergencyService.LocalBinder
			bleService = binder.getService()
			isBound = true

			// Query initial state from service when first bound
			isServicePinging = binder.isCurrentlyAdvertising()
			isServiceScanning = binder.isCurrentlyScanning()
			Log.d(TAG, "Initial Service State: Adv=$isServicePinging, Scan=$isServiceScanning")
			updateUiBasedOnState()
		}

		override fun onServiceDisconnected(arg0: ComponentName) {
			Log.w(TAG, "Service Unbound/Disconnected")
			isBound = false
			bleService = null
			isServicePinging = false // Assume stopped
			isServiceScanning = false
			updateUiBasedOnState()
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)

		localBroadcastManager = LocalBroadcastManager.getInstance(this)

		statusTextView = findViewById(R.id.statusTextView)
		pingButton = findViewById(R.id.pingButton)

		pingButton.setOnClickListener {
			if (!isBound || bleService == null) {
				Toast.makeText(this, "Service not ready. Trying to connect...", Toast.LENGTH_SHORT).show()
				tryStartServiceAndBind() // Attempt to reconnect if not bound
				return@setOnClickListener
			}
			if (!requiredPermissionsGranted) {
				Toast.makeText(this, "Grant permissions first.", Toast.LENGTH_SHORT).show()
				checkAndRequestPermissions()
				return@setOnClickListener
			}
			if (!isBluetoothEnabled()) {
				promptEnableBluetooth()
				return@setOnClickListener
			}

			if (isServicePinging) {
				sendCommandToService(BluemEmergencyService.ACTION_STOP_PINGING)
			} else {
				sendCommandToService(BluemEmergencyService.ACTION_START_PINGING)
			}
		}
		updateUiBasedOnState() // Initial UI state
		checkAndRequestPermissions()
	}

	override fun onStart() {
		super.onStart()
		val intentFilter = IntentFilter(BluemEmergencyService.ACTION_STATE_CHANGED)
		localBroadcastManager.registerReceiver(stateUpdateReceiver, intentFilter)
		// Attempt to bind if everything is ready
		if (requiredPermissionsGranted && isBluetoothEnabled()) {
			tryBindToService()
		}
	}

	override fun onStop() {
		super.onStop()
		localBroadcastManager.unregisterReceiver(stateUpdateReceiver)
		if (isBound) {
			Log.d(TAG, "Unbinding from service in onStop")
			unbindService(serviceConnection)
			isBound = false
			// bleService = null; // Keep service ref if needed till onDestroy, but binding is gone
		}
	}

	private fun updateUiBasedOnState() {
		runOnUiThread {
			val sb = StringBuilder("Status: ")
			if (!requiredPermissionsGranted) {
				sb.append("Permissions Required")
				pingButton.text = "Grant Permissions"
				pingButton.isEnabled = true // Allow clicking to re-trigger permission request
			} else if (!isBluetoothEnabled()) {
				sb.append("Bluetooth Disabled")
				pingButton.text = "Enable Bluetooth"
				pingButton.isEnabled = true // Allow clicking to re-trigger BT enable
			} else if (!isBound) {
				sb.append("Service Connecting...")
				pingButton.text = "Wait..."
				pingButton.isEnabled = false
			} else {
				// Service is bound, permissions OK, BT OK
				if (isServicePinging && isServiceScanning) {
					sb.append("Pinging & Listening")
					pingButton.text = "Stop Pinging"
				} else if (isServiceScanning) {
					sb.append("Listening for Pings")
					pingButton.text = "Start Pinging"
				} else if (isServicePinging) { // Scanning might have failed but advertising is on
					sb.append("Pinging (Scan Error?)")
					pingButton.text = "Stop Pinging"
				}
				else {
					sb.append("Service Idle or Error")
					pingButton.text = "Start Pinging" // Default action if idle
				}
				pingButton.isEnabled = true
			}
			statusTextView.text = sb.toString()
		}
	}

	private fun checkAndRequestPermissions() {
		val permissionsToRequest = mutableListOf<String>()
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
			permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
			permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADVERTISE)
			permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
		}
		// Location is always good to have for BLE, especially pre-Android 12
		permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
			permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
		}

		val yetToGrant = permissionsToRequest.filter {
			ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
		}.toTypedArray()

		if (yetToGrant.isEmpty()) {
			Log.d(TAG, "All required permissions already granted.")
			requiredPermissionsGranted = true
			tryStartServiceAndBind()
		} else {
			Log.d(TAG, "Requesting permissions: ${yetToGrant.joinToString()}")
			requiredPermissionsGranted = false
			requestMultiplePermissions.launch(yetToGrant)
		}
	}

	private fun isBluetoothEnabled(): Boolean {
		val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
		return bluetoothManager.adapter?.isEnabled == true
	}

	private fun promptEnableBluetooth() {
		if (!isBluetoothEnabled()) {
			val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
				ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
				requestEnableBluetooth.launch(enableBtIntent)
			} else {
				Log.e(TAG, "Missing BLUETOOTH_CONNECT to prompt enable BT.")
				Toast.makeText(this, "Bluetooth Connect permission needed.", Toast.LENGTH_SHORT).show()
			}
		}
	}

	private fun tryStartServiceAndBind() {
		if (requiredPermissionsGranted && isBluetoothEnabled()) {
			Log.d(TAG, "Prerequisites met. Starting service and attempting to bind.")
			startBleService() // Start if not already running
			tryBindToService()  // Attempt to bind
		} else {
			Log.w(TAG, "Cannot start/bind service yet. Permissions: $requiredPermissionsGranted, BT: ${isBluetoothEnabled()}")
			updateUiBasedOnState() // Reflect current prerequisites
		}
	}

	private fun startBleService() {
		Log.d(TAG, "Ensuring BluemEmergencyService is started.")
		val serviceIntent = Intent(this, BluemEmergencyService::class.java)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			startForegroundService(serviceIntent)
		} else {
			startService(serviceIntent)
		}
	}

	private fun tryBindToService() {
		if (!isBound && requiredPermissionsGranted && isBluetoothEnabled()) {
			Log.d(TAG, "Attempting to bind to BluemEmergencyService.")
			val bindIntent = Intent(this, BluemEmergencyService::class.java)
			bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)
		} else if (isBound) {
			Log.d(TAG,"Already bound. Requesting current state from service.")
			// If already bound, tell service to send its current state again
			bleService?.LocalBinder()?.getService()?.let {
				// This is a bit hacky, ideally service sends state on rebind or explicit request
				// For now, rely on existing broadcastState in onBind/onRebind
				// Or add a new command to service: ACTION_REQUEST_STATE_UPDATE
			}
		} else {
			Log.d(TAG, "Not binding: Bound=$isBound, Perms=$requiredPermissionsGranted, BT=${isBluetoothEnabled()}")
		}
	}

	private fun sendCommandToService(action: String) {
		Log.d(TAG, "Sending command to service: $action")
		val serviceIntent = Intent(this, BluemEmergencyService::class.java).apply {
			this.action = action
		}
		startService(serviceIntent) // Safe to call even if service is running
	}
}