package com.example.bluem.ui
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.bluem.R

class ProfileFragment : Fragment() {

	override fun onCreateView(
		inflater: LayoutInflater, container: ViewGroup?,
		savedInstanceState: Bundle?
	): View? {
		// Inflate the layout for this fragment
		val view = inflater.inflate(R.layout.fragment_profile, container, false)
		val profileText: TextView = view.findViewById(R.id.profileTextView)
		profileText.text = "Profile Information (Coming Soon)"
		// TODO: Add EditTexts for name, contact info, etc.
		return view
	}
}