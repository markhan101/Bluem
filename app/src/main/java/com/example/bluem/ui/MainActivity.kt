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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.viewpager2.widget.ViewPager2
import com.example.bluem.R
import com.example.bluem.ble.PingData // Assuming PingData is in this package
import com.example.bluem.service.BluemEmergencyService
import com.example.bluem.ui.PingsFragment // Ensure this import is correct
// import com.example.bluem.ui.adapter.ViewPagerAdapter // Import your ViewPagerAdapter
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.example.bluem.ui.ViewPagerAdapter



class MainActivity : AppCompatActivity() {

	private val TAG = "MainActivity"
	private lateinit var viewPager: ViewPager2
	private lateinit var tabLayout: TabLayout
	private lateinit var viewPagerAdapter: ViewPagerAdapter
	private lateinit var pingFab: FloatingActionButton

	@Volatile private var isServicePinging = false
	@Volatile private var isServiceScanning = false // To know if service is generally active
	private var requiredPermissionsGranted = false

	private var bleService: BluemEmergencyService? = null
	@Volatile private var isBound = false

	private lateinit var localBroadcastManager: LocalBroadcastManager

	// Combined receiver for service state and ping data
	private val appEventsReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			when (intent.action) {
				BluemEmergencyService.ACTION_STATE_CHANGED -> {
					isServicePinging = intent.getBooleanExtra(BluemEmergencyService.EXTRA_IS_ADVERTISING, false)
					isServiceScanning = intent.getBooleanExtra(BluemEmergencyService.EXTRA_IS_SCANNING, false)
					Log.d(TAG, "Broadcast Received (State): Adv=$isServicePinging, Scan=$isServiceScanning")
					updateFabState() // Update FAB based on new state
				}
				BluemEmergencyService.ACTION_PING_RECEIVED -> { // Listen for ping data
					val deviceAddress = intent.getStringExtra(BluemEmergencyService.EXTRA_DEVICE_ADDRESS)
					val rssi = intent.getIntExtra(BluemEmergencyService.EXTRA_RSSI, -127)
					if (deviceAddress != null) {
						Log.d(TAG, "Broadcast Received (Ping Data): Addr=$deviceAddress, RSSI=$rssi")
						deliverPingToFragment(deviceAddress, rssi)
					}
				}
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
			requiredPermissionsGranted = allGranted
			if (allGranted) {
				Log.d(TAG, "All required permissions granted.")
				tryStartServiceAndBind()
			} else {
				Log.e(TAG, "One or more permissions were denied.")
				Toast.makeText(this, "Permissions are required for BLE features.", Toast.LENGTH_LONG).show()
			}
			updateFabState() // Update UI based on permission status
		}

	private val requestEnableBluetooth =
		registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
			if (result.resultCode == RESULT_OK) {
				Log.d(TAG, "Bluetooth enabled by user.")
				tryStartServiceAndBind()
			} else {
				Log.e(TAG, "Bluetooth enabling was denied or failed.")
				Toast.makeText(this, "Bluetooth must be enabled.", Toast.LENGTH_SHORT).show()
			}
			updateFabState() // Update UI based on BT status
		}

	private val serviceConnection = object : ServiceConnection {
		override fun onServiceConnected(className: ComponentName, service: IBinder) {
			Log.d(TAG, "Service Bound")
			val binder = service as BluemEmergencyService.LocalBinder
			bleService = binder.getService()
			isBound = true
			// Query initial state from service when first bound
			isServicePinging = binder.isCurrentlyAdvertising()
			isServiceScanning = binder.isCurrentlyScanning()
			Log.d(TAG, "Initial Service State from Binder: Adv=$isServicePinging, Scan=$isServiceScanning")
			updateFabState()
		}

		override fun onServiceDisconnected(arg0: ComponentName) {
			Log.w(TAG, "Service Unbound/Disconnected")
			isBound = false
			bleService = null
			isServicePinging = false
			isServiceScanning = false
			updateFabState()
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		// Ensure this layout is your activity_main.xml with ViewPager2, TabLayout, and FAB
		setContentView(R.layout.activity_main)

		localBroadcastManager = LocalBroadcastManager.getInstance(this)

		viewPager = findViewById(R.id.viewPager)
		tabLayout = findViewById(R.id.tabLayout)
		pingFab = findViewById(R.id.pingFab) // Make sure this ID matches your activity_main.xml

		viewPagerAdapter = ViewPagerAdapter(this) // Make sure ViewPagerAdapter is created
		viewPager.adapter = viewPagerAdapter

		TabLayoutMediator(tabLayout, viewPager) { tab, position ->
			tab.text = viewPagerAdapter.getPageTitle(position)
		}.attach()

		pingFab.setOnClickListener {
			if (!isBound || bleService == null) {
				Toast.makeText(this, "Service not ready. Trying to connect...", Toast.LENGTH_SHORT).show()
				tryStartServiceAndBind()
				return@setOnClickListener
			}
			if (!requiredPermissionsGranted) {
				checkAndRequestPermissions() // Let checkAndRequestPermissions handle toast
				return@setOnClickListener
			}
			if (!isBluetoothEnabled()) {
				promptEnableBluetooth() // Let promptEnableBluetooth handle toast
				return@setOnClickListener
			}

			if (isServicePinging) {
				sendCommandToService(BluemEmergencyService.ACTION_STOP_PINGING)
			} else {
				sendCommandToService(BluemEmergencyService.ACTION_START_PINGING)
			}
			// UI (FAB) will update when broadcast is received
		}

		updateFabState() // Set initial FAB state
		checkAndRequestPermissions()
	}

	override fun onStart() {
		super.onStart()
		val intentFilter = IntentFilter().apply {
			addAction(BluemEmergencyService.ACTION_STATE_CHANGED)
			addAction(BluemEmergencyService.ACTION_PING_RECEIVED) // Listen for ping data
		}
		localBroadcastManager.registerReceiver(appEventsReceiver, intentFilter)

		if (requiredPermissionsGranted && isBluetoothEnabled()) {
			tryBindToService()
		} else {
			Log.w(TAG, "Not binding in onStart: Perms=$requiredPermissionsGranted, BT=${isBluetoothEnabled()}")
		}
	}

	override fun onStop() {
		super.onStop()
		localBroadcastManager.unregisterReceiver(appEventsReceiver)
		if (isBound) {
			Log.d(TAG, "Unbinding from service in onStop")
			unbindService(serviceConnection)
			isBound = false
			// Don't nullify bleService here, might be needed if activity is just paused/stopped temporarily
		}
	}

	// Updated to control FAB state
	private fun updateFabState() {
		runOnUiThread {
			if (!requiredPermissionsGranted) {
				pingFab.isEnabled = true // Allow clicking to grant permissions
				pingFab.setImageResource(R.drawable.ic_warning) // Example: warning icon
				// Consider changing FAB's role or hiding if perms are critical prerequisite
			} else if (!isBluetoothEnabled()) {
				pingFab.isEnabled = true // Allow clicking to enable BT
				pingFab.setImageResource(R.drawable.ic_launcher_background) // Example: BT disabled icon
			} else if (!isBound) {
				pingFab.isEnabled = false // Disabled while service is connecting
				pingFab.setImageResource(R.drawable.ic_warning) // Example: connecting icon
			} else {
				// Service is bound, permissions OK, BT OK
				pingFab.isEnabled = true
				if (isServicePinging) {
					pingFab.setImageResource(R.drawable.ic_launcher_background) // Icon for "Stop Pinging"
				} else {
					pingFab.setImageResource(R.drawable.ic_launcher_background) // Icon for "Start Pinging"
				}
			}
		}
	}

	private fun checkAndRequestPermissions() {
		val permissionsToRequest = mutableListOf<String>()
		// Add SDK_INT checks for specific permissions
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
			permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
			permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADVERTISE)
			permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
		}
		permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION) // Still good for consistency

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
			permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
		}

		val yetToGrant = permissionsToRequest.filter {
			ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
		}.toTypedArray()

		if (yetToGrant.isEmpty()) {
			Log.d(TAG, "All required permissions already granted.")
			requiredPermissionsGranted = true
			tryStartServiceAndBind() // Proceed if permissions are good
		} else {
			Log.d(TAG, "Requesting permissions: ${yetToGrant.joinToString()}")
			requiredPermissionsGranted = false // Set to false until granted
			requestMultiplePermissions.launch(yetToGrant)
		}
		updateFabState() // Update UI after checking
	}

	private fun isBluetoothEnabled(): Boolean {
		val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
		return bluetoothManager.adapter?.isEnabled == true
	}

	private fun promptEnableBluetooth() {
		if (!isBluetoothEnabled()) { // Check again to avoid redundant prompts
			val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
			// BLUETOOTH_CONNECT permission is needed for this on SDK 31+
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
				ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
				requestEnableBluetooth.launch(enableBtIntent)
			} else {
				Log.e(TAG, "Missing BLUETOOTH_CONNECT to prompt enable BT.")
				Toast.makeText(this, "Bluetooth Connect permission needed to enable Bluetooth.", Toast.LENGTH_LONG).show()
				// Consider directing user to settings or re-requesting BLUETOOTH_CONNECT
			}
		}
	}

	private fun tryStartServiceAndBind() {
		if (requiredPermissionsGranted && isBluetoothEnabled()) {
			Log.d(TAG, "Prerequisites met. Ensuring service is started and attempting to bind.")
			startBleServiceIfNotRunning() // Start if not already running
			tryBindToService()
		} else {
			Log.w(TAG, "Cannot start/bind service yet. Perms=$requiredPermissionsGranted, BT=${isBluetoothEnabled()}")
			updateFabState() // Reflect that prerequisites are not met
		}
	}

	// Renamed for clarity
	private fun startBleServiceIfNotRunning() {
		// Check if service is running could be added here, but startService is safe to call multiple times
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
			// BIND_AUTO_CREATE will also start the service if it's not already running
			// and if it has been declared in the manifest.
			bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)
		} else if (isBound) {
			Log.d(TAG,"Already bound. Service should send its state.")
			// Service sends state onBind and onRebind, so this should be covered.
			// If needed, could explicitly request state update via a command.
		} else {
			Log.w(TAG, "Not binding: Bound=$isBound, Perms=$requiredPermissionsGranted, BT=${isBluetoothEnabled()}")
		}
	}

	private fun sendCommandToService(action: String) {
		Log.d(TAG, "Sending command to service: $action")
		val serviceIntent = Intent(this, BluemEmergencyService::class.java).apply {
			this.action = action
		}
		startService(serviceIntent) // Ensures service processes command
	}

	// Method to deliver pings to the PingsFragment
	fun deliverPingToFragment(deviceAddress: String, rssi: Int) {
		try {
			// Try to find PingsFragment by its tag (ViewPager2 default for position 0 is "f0")
			// Or iterate through supportFragmentManager.fragments
			val fragment = supportFragmentManager.findFragmentByTag("f0") // Default tag for first fragment
			if (fragment is PingsFragment) {
				fragment.updatePings(PingData(deviceAddress, System.currentTimeMillis(), rssi, isActive = true))
			} else {
				Log.w(TAG, "PingsFragment (tag f0) not found or not the correct type. Ping for $deviceAddress not delivered to UI.")
				// If not found, try iterating (less efficient but more robust if tags change)
				supportFragmentManager.fragments.find { it is PingsFragment }?.let { foundFragment ->
					(foundFragment as PingsFragment).updatePings(PingData(deviceAddress, System.currentTimeMillis(), rssi, isActive = true))
					Log.d(TAG, "Delivered ping to PingsFragment found by type.")
				} ?: Log.e(TAG, "PingsFragment completely not found.")
			}
		} catch (e: Exception) {
			Log.e(TAG, "Error delivering ping to fragment: ${e.message}", e)
		}
	}
}