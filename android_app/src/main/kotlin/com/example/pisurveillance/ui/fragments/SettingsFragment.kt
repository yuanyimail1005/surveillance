package com.example.pisurveillance.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.pisurveillance.MainActivity
import com.example.pisurveillance.R
import com.example.pisurveillance.databinding.FragmentSettingsBinding
import kotlinx.coroutines.launch

/**
 * Fragment for camera and audio settings
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel by lazy {
        (activity as? MainActivity)?.getViewModel()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupServerConnection()
        setupCameraSettings()
        setupAudioSettings()
    }

    /**
     * Setup server connection controls
     */
    private fun setupServerConnection() {
        lifecycleScope.launch {
            val address = viewModel?.serverUrl?.value ?: "192.168.1.100"
            binding.serverAddressInput.setText(address)
            binding.serverPortInput.setText("5000")
        }

        binding.connectButton.setOnClickListener {
            val address = binding.serverAddressInput.text.toString()
            val port = binding.serverPortInput.text.toString().toIntOrNull() ?: 5000

            if (address.isNotEmpty()) {
                viewModel?.updateServerConnection(address, port)
                viewModel?.connect()
            }
        }
    }

    /**
     * Setup camera settings controls
     */
    private fun setupCameraSettings() {
        viewModel?.cameraSettings?.observe(viewLifecycleOwner) { settings ->
            if (settings != null) {
                // Populate camera device spinner
                val deviceNames = settings.availableDevices.map { it.name }
                val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, deviceNames)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.cameraDeviceSpinner.adapter = adapter

                // Update resolution and FPS
                binding.cameraResolutionInput.setText("${settings.width} x ${settings.height}")
                binding.cameraFpsInput.setText(settings.fps.toString())
            }
        }

        binding.applyCameraSettingsButton.setOnClickListener {
            val fps = binding.cameraFpsInput.text.toString().toIntOrNull() ?: 25
            viewModel?.updateCameraSettings(fps = fps)
        }
    }

    /**
     * Setup audio settings controls
     */
    private fun setupAudioSettings() {
        binding.speakerVolumeSlider.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: android.widget.SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    if (fromUser) {
                        viewModel?.setSpeakerVolume(progress)
                    }
                }

                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            }
        )

        viewModel?.audioDevices?.observe(viewLifecycleOwner) { devices ->
            if (devices != null) {
                // Populate capture device spinner
                val captureNames = devices.captureDevices.map { it.name }
                val captureAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, captureNames)
                binding.captureDeviceSpinner.adapter = captureAdapter

                // Populate playback device spinner
                val playbackNames = devices.playbackDevices.map { it.name }
                val playbackAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, playbackNames)
                binding.playbackDeviceSpinner.adapter = playbackAdapter
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
