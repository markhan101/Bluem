package com.example.bluem.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.bluem.R
import com.google.android.gms.location.*

class ProfileFragment : Fragment() {

	private val TAG = "ProfileFragment"

	private lateinit var editTextPhoneNumber: EditText
	private lateinit var spinnerBloodGroup: Spinner
	private lateinit var textViewLatitude: TextView
	private lateinit var textViewLongitude: TextView
	private lateinit var buttonSaveProfile: Button
	private lateinit var buttonGetLocation: Button

	private lateinit var fusedLocationClient: FusedLocationProviderClient
	private lateinit var locationCallback: LocationCallback
	private var requestingLocationUpdates = false

	companion object {
		const val SHARED_PREFS_PROFILE = "BluemUserProfile"
		const val KEY_PHONE_NUMBER = "phoneNumber"
		const val KEY_BLOOD_GROUP_POSITION = "bloodGroupPosition"
		const val KEY_LATITUDE = "latitude"
		const val KEY_LONGITUDE = "longitude"
	}

	private val requestLocationPermissionLauncher =
		registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
			if (isGranted) {
				Log.d(TAG, "ACCESS_FINE_LOCATION permission granted by user.")
				startLocationUpdates()
			} else {
				Log.e(TAG, "ACCESS_FINE_LOCATION permission denied by user.")
				Toast.makeText(requireContext(), "Location permission is required to get coordinates.", Toast.LENGTH_LONG).show()
				updateLocationDisplayUI(null, null) // <-- CORRECTED HERE
			}
		}

	override fun onCreateView(
		inflater: LayoutInflater, container: ViewGroup?,
		savedInstanceState: Bundle?
	): View? {
		val view = inflater.inflate(R.layout.fragment_profile, container, false)

		editTextPhoneNumber = view.findViewById(R.id.editTextPhoneNumber)
		spinnerBloodGroup = view.findViewById(R.id.spinnerBloodGroup)
		textViewLatitude = view.findViewById(R.id.textViewLatitude)
		textViewLongitude = view.findViewById(R.id.textViewLongitude)
		buttonSaveProfile = view.findViewById(R.id.buttonSaveProfile)
		buttonGetLocation = view.findViewById(R.id.buttonGetLocation)

		setupBloodGroupSpinner()
		// Initialize FusedLocationProviderClient here, after context is available
		fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
		createLocationCallback()

		buttonSaveProfile.setOnClickListener {
			saveProfileData()
		}

		buttonGetLocation.setOnClickListener {
			if (requestingLocationUpdates) {
				stopLocationUpdates()
			} else {
				checkLocationPermissionAndFetch()
			}
		}
		return view
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		loadProfileData()
	}

	private fun setupBloodGroupSpinner() {
		ArrayAdapter.createFromResource(
			requireContext(), R.array.blood_groups_array, android.R.layout.simple_spinner_item
		).also { adapter ->
			adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
			spinnerBloodGroup.adapter = adapter
		}
	}

	private fun saveProfileData() {
		val sharedPrefs = activity?.getSharedPreferences(SHARED_PREFS_PROFILE, Context.MODE_PRIVATE) ?: return
		with(sharedPrefs.edit()) {
			putString(KEY_PHONE_NUMBER, editTextPhoneNumber.text.toString().trim())
			putInt(KEY_BLOOD_GROUP_POSITION, spinnerBloodGroup.selectedItemPosition)
			// Latitude and Longitude are saved via newLocationReceived when location is fetched
			apply()
		}
		Toast.makeText(requireContext(), "Profile Saved!", Toast.LENGTH_SHORT).show()
		Log.d(TAG, "Profile data saved.")
	}

	private fun loadProfileData() {
		val sharedPrefs = activity?.getSharedPreferences(SHARED_PREFS_PROFILE, Context.MODE_PRIVATE) ?: return
		editTextPhoneNumber.setText(sharedPrefs.getString(KEY_PHONE_NUMBER, ""))
		// Default to "Unknown" which is typically the last item if index 8
		spinnerBloodGroup.setSelection(sharedPrefs.getInt(KEY_BLOOD_GROUP_POSITION, spinnerBloodGroup.adapter.count - 1))


		val latString = sharedPrefs.getString(KEY_LATITUDE, null)
		val lonString = sharedPrefs.getString(KEY_LONGITUDE, null)
		if (latString != null && lonString != null) {
			try {
				updateLocationDisplayUI(latString.toDouble(), lonString.toDouble())
			} catch (e: NumberFormatException) {
				updateLocationDisplayUI(null, null)
			}
		} else {
			updateLocationDisplayUI(null, null)
		}
		Log.d(TAG, "Profile data loaded.")
	}

	// This method only updates the UI TextViews
	private fun updateLocationDisplayUI(latitude: Double?, longitude: Double?) {
		if (latitude != null && longitude != null &&
			latitude <= 90.0 && latitude >= -90.0 &&   // Basic validation
			longitude <= 180.0 && longitude >= -180.0) {
			textViewLatitude.text = "Lat: %.6f".format(latitude)
			textViewLongitude.text = "Lon: %.6f".format(longitude)
		} else {
			textViewLatitude.text = "Lat: N/A"
			textViewLongitude.text = "Lon: N/A"
		}
	}

	// This method is called when a new location is actually fetched.
	// It updates the UI AND saves the new location to SharedPreferences.
	private fun newLocationReceived(latitude: Double, longitude: Double) {
		updateLocationDisplayUI(latitude, longitude) // Update UI elements

		val sharedPrefs = activity?.getSharedPreferences(SHARED_PREFS_PROFILE, Context.MODE_PRIVATE) ?: return
		with(sharedPrefs.edit()) {
			putString(KEY_LATITUDE, latitude.toString())
			putString(KEY_LONGITUDE, longitude.toString())
			apply()
		}
		Log.d(TAG, "New location saved: Lat=$latitude, Lon=$longitude")
	}


	private fun checkLocationPermissionAndFetch() {
		when {
			ContextCompat.checkSelfPermission(
				requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
			) == PackageManager.PERMISSION_GRANTED -> {
				Log.d(TAG, "ACCESS_FINE_LOCATION already granted. Starting location updates.")
				startLocationUpdates()
			}
			shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) -> {
				Log.w(TAG, "Showing rationale for ACCESS_FINE_LOCATION.")
				Toast.makeText(requireContext(), "Location permission helps share your coordinates in an emergency.", Toast.LENGTH_LONG).show()
				requestLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
			}
			else -> {
				Log.d(TAG, "Requesting ACCESS_FINE_LOCATION permission.")
				requestLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
			}
		}
	}

	@SuppressLint("MissingPermission")
	private fun startLocationUpdates() {
		if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
			Log.e(TAG, "Cannot start location updates: Permission not granted (should not happen if check is done).")
			return
		}

		val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000L)
			.setMinUpdateIntervalMillis(5000L)
			.build()

		try {
			fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
			requestingLocationUpdates = true
			buttonGetLocation.text = getString(R.string.stop_location_updates_button_text) // Use string resource
			Log.i(TAG, "Location updates requested.")
			Toast.makeText(requireContext(), "Fetching location...", Toast.LENGTH_SHORT).show()
		} catch (e: SecurityException) {
			Log.e(TAG, "SecurityException starting location updates: ${e.message}", e)
			Toast.makeText(requireContext(), "Failed to start location updates (Security).", Toast.LENGTH_SHORT).show()
		}
	}

	private fun createLocationCallback() {
		locationCallback = object : LocationCallback() {
			override fun onLocationResult(locationResult: LocationResult) {
				locationResult.lastLocation?.let { location ->
					Log.d(TAG, "New location: Lat=${location.latitude}, Lon=${location.longitude}")
					newLocationReceived(location.latitude, location.longitude)

					// If you only want one update per button click:
					// stopLocationUpdates() // Uncomment if "Refresh" means get once then stop
				} ?: Log.w(TAG, "LocationResult received, but lastLocation is null.")
			}

			override fun onLocationAvailability(locationAvailability: LocationAvailability) {
				super.onLocationAvailability(locationAvailability)
				Log.d(TAG, "Location Availability: ${locationAvailability.isLocationAvailable}")
				if (!locationAvailability.isLocationAvailable) {
					Toast.makeText(requireContext(), "Location not available. Check GPS/settings.", Toast.LENGTH_SHORT).show()
					// stopLocationUpdates() // Optionally stop if location becomes unavailable
				}
			}
		}
	}

	private fun stopLocationUpdates() {
		if (requestingLocationUpdates) {
			fusedLocationClient.removeLocationUpdates(locationCallback)
			requestingLocationUpdates = false
			buttonGetLocation.text = getString(R.string.refresh_location_button_text) // Use string resource
			Log.i(TAG, "Location updates stopped.")
		}
	}

	override fun onPause() {
		super.onPause()
		stopLocationUpdates()
	}
}