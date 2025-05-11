package com.example.bluem.ui

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.viewpager2.widget.ViewPager2
import com.example.bluem.R
import com.example.bluem.ble.PingData
import com.example.bluem.service.BluemEmergencyService
import com.example.bluem.ui.ViewPagerAdapter
import com.example.bluem.ui.PingInteractionListener
import com.example.bluem.ui.PingsFragment
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity(), PingInteractionListener {

	private val TAG = "MainActivity"
	private lateinit var viewPager: ViewPager2
	private lateinit var tabLayout: TabLayout
	private lateinit var viewPagerAdapter: ViewPagerAdapter
	private lateinit var pingFab: FloatingActionButton
	private val SHARED_PREFS_CUSTOM_NAMES = "BluemCustomDeviceNames" // From ProfileFragment

	@Volatile private var isServicePinging = false
	@Volatile private var isServiceScanning = false
	private var requiredPermissionsGranted = false

	private var bleService: BluemEmergencyService? = null
	@Volatile private var isBound = false

	private lateinit var localBroadcastManager: LocalBroadcastManager

	private val appEventsReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			when (intent.action) {
				BluemEmergencyService.ACTION_STATE_CHANGED -> {
					isServicePinging = intent.getBooleanExtra(BluemEmergencyService.EXTRA_IS_ADVERTISING, false)
					isServiceScanning = intent.getBooleanExtra(BluemEmergencyService.EXTRA_IS_SCANNING, false)
					Log.d(TAG, "State Broadcast: Adv=$isServicePinging, Scan=$isServiceScanning")
					updateFabState()
				}
				BluemEmergencyService.ACTION_PING_RECEIVED -> {
					val deviceAddress = intent.getStringExtra(BluemEmergencyService.EXTRA_DEVICE_ADDRESS)
					val bleDeviceName = intent.getStringExtra(BluemEmergencyService.EXTRA_BLE_DEVICE_NAME)
					val rssi = intent.getIntExtra(BluemEmergencyService.EXTRA_RSSI, -127)

					val bloodGroupIndex = intent.getIntExtra(BluemEmergencyService.EXTRA_PROFILE_BLOOD_GROUP_IDX, -1)
					val hasPhone = intent.getBooleanExtra(BluemEmergencyService.EXTRA_PROFILE_HAS_PHONE, false)
					val latitude = intent.getDoubleExtra(BluemEmergencyService.EXTRA_PROFILE_LATITUDE, 91.0) // Invalid default
					val longitude = intent.getDoubleExtra(BluemEmergencyService.EXTRA_PROFILE_LONGITUDE, 181.0) // Invalid default
					val phoneSuffix = intent.getLongExtra(BluemEmergencyService.EXTRA_PROFILE_PHONE_SUFFIX, 0L)
					val seqTime = intent.getByteExtra(BluemEmergencyService.EXTRA_PROFILE_SEQ_TIME, 0.toByte())

					if (deviceAddress != null) {
						Log.d(TAG, "Ping Data Received: Addr=$deviceAddress, Name=$bleDeviceName, RSSI=$rssi, BGidx=$bloodGroupIndex")
						deliverPingToFragment(deviceAddress, bleDeviceName, rssi,
							bloodGroupIndex.takeIf { it != -1 }, // Pass null if default was received
							hasPhone,
							latitude.takeIf { it <= 90.0 }, // Pass null if invalid default
							longitude.takeIf { it <= 180.0 },
							phoneSuffix,
							seqTime
						)
					}
				}
			}
		}
	}

	private val requestMultiplePermissions =
		registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
			requiredPermissionsGranted = perms.all { it.value }
			if (requiredPermissionsGranted) tryStartServiceAndBind()
			else Toast.makeText(this, "Permissions required for BLE.", Toast.LENGTH_LONG).show()
			updateFabState()
		}

	private val requestEnableBluetooth =
		registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
			if (result.resultCode == RESULT_OK) tryStartServiceAndBind()
			else Toast.makeText(this, "Bluetooth must be enabled.", Toast.LENGTH_SHORT).show()
			updateFabState()
		}

	private val serviceConnection = object : ServiceConnection {
		override fun onServiceConnected(name: ComponentName, service: IBinder) {
			Log.d(TAG, "Service Bound"); val binder = service as BluemEmergencyService.LocalBinder
			bleService = binder.getService(); isBound = true
			isServicePinging = binder.isCurrentlyAdvertising(); isServiceScanning = binder.isCurrentlyScanning()
			Log.d(TAG, "Initial Service State: Adv=$isServicePinging, Scan=$isServiceScanning"); updateFabState()
		}
		override fun onServiceDisconnected(name: ComponentName) {
			Log.w(TAG, "Service Unbound"); isBound = false; bleService = null
			isServicePinging = false; isServiceScanning = false; updateFabState()
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)
		localBroadcastManager = LocalBroadcastManager.getInstance(this)

		viewPager = findViewById(R.id.viewPager); tabLayout = findViewById(R.id.tabLayout)
		pingFab = findViewById(R.id.pingFab)
		viewPagerAdapter = ViewPagerAdapter(this); viewPager.adapter = viewPagerAdapter
		TabLayoutMediator(tabLayout, viewPager) { tab, pos -> tab.text = viewPagerAdapter.getPageTitle(pos) }.attach()

		pingFab.setOnClickListener {
			if (!isBound || bleService == null) { Toast.makeText(this, "Service connecting...", Toast.LENGTH_SHORT).show(); tryStartServiceAndBind(); return@setOnClickListener }
			if (!requiredPermissionsGranted) { checkAndRequestPermissions(); return@setOnClickListener }
			if (!isBluetoothEnabled()) { promptEnableBluetooth(); return@setOnClickListener }
			sendCommandToService(if (isServicePinging) BluemEmergencyService.ACTION_STOP_PINGING else BluemEmergencyService.ACTION_START_PINGING)
		}
		updateFabState(); checkAndRequestPermissions()
	}

	override fun onStart() {
		super.onStart()
		val filter = IntentFilter().apply { addAction(BluemEmergencyService.ACTION_STATE_CHANGED); addAction(BluemEmergencyService.ACTION_PING_RECEIVED) }
		localBroadcastManager.registerReceiver(appEventsReceiver, filter)
		if (requiredPermissionsGranted && isBluetoothEnabled()) tryBindToService()
	}

	override fun onStop() {
		super.onStop(); localBroadcastManager.unregisterReceiver(appEventsReceiver)
		if (isBound) { Log.d(TAG, "Unbinding from service"); unbindService(serviceConnection); isBound = false }
	}

	private fun updateFabState() {
		runOnUiThread {
			// Ensure you have these drawables: ic_warning, ic_bt_disabled, ic_sync_problem, ic_pause_ping, ic_start_ping
			val iconRes = when {
				!requiredPermissionsGranted -> R.drawable.ic_warning
				!isBluetoothEnabled() -> R.drawable.ic_warning
				!isBound -> R.drawable.ic_warning
				isServicePinging -> R.drawable.ic_warning
				else -> R.drawable.ic_warning
			}
			pingFab.setImageResource(iconRes)
			pingFab.isEnabled = requiredPermissionsGranted && isBluetoothEnabled() && isBound
			if(!requiredPermissionsGranted || !isBluetoothEnabled()) pingFab.isEnabled = true // Allow clicks to fix
		}
	}

	private fun checkAndRequestPermissions() {
		val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			perms.addAll(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT))
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
		val toGrant = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
		if (toGrant.isEmpty()) { requiredPermissionsGranted = true; tryStartServiceAndBind() }
		else { requiredPermissionsGranted = false; requestMultiplePermissions.launch(toGrant) }
		// updateFabState() // Called by callbacks or at end of checkAndRequestPermissions
	}

	private fun isBluetoothEnabled(): Boolean = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled == true

	private fun promptEnableBluetooth() {
		if (!isBluetoothEnabled()) {
			val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
				requestEnableBluetooth.launch(intent)
			} else { Toast.makeText(this, "BT Connect permission needed.", Toast.LENGTH_LONG).show() }
		}
	}
	private fun hasPermission(permission: String): Boolean = ContextCompat.checkSelfPermission(this,permission) == PackageManager.PERMISSION_GRANTED


	private fun tryStartServiceAndBind() {
		if (requiredPermissionsGranted && isBluetoothEnabled()) {
			startBleServiceIfNotRunning(); tryBindToService()
		} else { updateFabState() }
	}

	private fun startBleServiceIfNotRunning() {
		val intent = Intent(this, BluemEmergencyService::class.java)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
	}

	private fun tryBindToService() {
		if (!isBound && requiredPermissionsGranted && isBluetoothEnabled()) {
			bindService(Intent(this, BluemEmergencyService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
		}
	}

	private fun sendCommandToService(action: String) {
		startService(Intent(this, BluemEmergencyService::class.java).apply { this.action = action })
	}

	override fun onPingLongClicked(pingData: PingData) { showSaveNameDialog(pingData) }
	override fun getCustomNameForDevice(deviceAddress: String): String? =
		getSharedPreferences(SHARED_PREFS_CUSTOM_NAMES, Context.MODE_PRIVATE).getString(deviceAddress, null)

	fun showSaveNameDialog(pingData: PingData) {
		val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_save_name, null)
		val nameEditText: EditText = dialogView.findViewById(R.id.editTextDeviceName)
		nameEditText.setText(pingData.customName ?: pingData.bleDeviceName ?: "")
		AlertDialog.Builder(this).setTitle("Set Name for ${pingData.getDisplayName()}")
			.setView(dialogView)
			.setPositiveButton("Save") { d, _ ->
				val name = nameEditText.text.toString().trim()
				if (name.isNotEmpty()) saveCustomDeviceName(pingData.deviceAddress, name)
				else clearCustomDeviceName(pingData.deviceAddress)
				d.dismiss()
			}
			.setNegativeButton("Cancel") { d, _ -> d.cancel() }
			.setNeutralButton("Clear Custom") { d, _ -> clearCustomDeviceName(pingData.deviceAddress); d.dismiss() }
			.show()
	}

	private fun saveCustomDeviceName(addr: String, name: String) {
		getSharedPreferences(SHARED_PREFS_CUSTOM_NAMES, Context.MODE_PRIVATE).edit().putString(addr, name).apply()
		refreshPingInFragment(addr)
	}
	private fun clearCustomDeviceName(addr: String) {
		getSharedPreferences(SHARED_PREFS_CUSTOM_NAMES, Context.MODE_PRIVATE).edit().remove(addr).apply()
		refreshPingInFragment(addr)
	}

	fun deliverPingToFragment(
		deviceAddress: String, bleDeviceName: String?, rssi: Int,
		bloodGroupIndex: Int?, hasPhone: Boolean?, latitude: Double?,
		longitude: Double?, phoneSuffix: Long?, seqTime: Byte?
	) {
		try {
			val fragment = supportFragmentManager.findFragmentByTag("f0") as? PingsFragment // ViewPager2 default
			if (fragment != null) {
				val customName = getCustomNameForDevice(deviceAddress)
				fragment.updatePings(
					PingData(deviceAddress, bleDeviceName, customName, System.currentTimeMillis(), rssi, true,
						bloodGroupIndex, hasPhone, latitude, longitude, phoneSuffix, seqTime
					)
				)
			} else { Log.w(TAG, "PingsFragment (f0) not found for ping delivery.") }
		} catch (e: Exception) { Log.e(TAG, "Error delivering ping: ${e.message}", e) }
	}

	private fun refreshPingInFragment(deviceAddress: String) {
		(supportFragmentManager.findFragmentByTag("f0") as? PingsFragment)?.refreshPingItem(deviceAddress)
	}
}