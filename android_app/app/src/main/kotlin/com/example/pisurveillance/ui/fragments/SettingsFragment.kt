package com.example.pisurveillance.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
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
        viewModel?.serverAddress?.observe(viewLifecycleOwner) { address ->
            if (address.isNotEmpty() && binding.serverAddressInput.text.isNullOrEmpty()) {
                binding.serverAddressInput.setText(address)
            }
        }
        
        viewModel?.serverPort?.observe(viewLifecycleOwner) { port ->
            if (binding.serverPortInput.text.isNullOrEmpty()) {
                binding.serverPortInput.setText(port.toString())
            }
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
                // Populate camera device dropdown
                val availableDevices = settings.availableDevices ?: emptyList()
                val deviceNames = availableDevices.map { it.name }
                val deviceAdapter = ArrayAdapter(requireContext(), R.layout.list_item_dropdown, deviceNames)
                binding.cameraDeviceSpinner.setAdapter(deviceAdapter)

                // Set selection if current device matches
                settings.selectedDevice?.let { currentPath ->
                    val device = availableDevices.find { it.path == currentPath }
                    device?.let { binding.cameraDeviceSpinner.setText(it.name, false) }
                }

                // Populate resolution dropdown
                val resolutions = settings.allowedResolutions ?: emptyList()
                val resolutionStrings = resolutions.map { "${it.width} x ${it.height}" }
                val resAdapter = ArrayAdapter(requireContext(), R.layout.list_item_dropdown, resolutionStrings)
                binding.cameraResolutionSpinner.setAdapter(resAdapter)

                // Set selection if current resolution matches
                val currentRes = resolutions.find { it.width == settings.width && it.height == settings.height }
                currentRes?.let { binding.cameraResolutionSpinner.setText("${it.width} x ${it.height}", false) }

                // Update FPS
                binding.cameraFpsInput.setText(settings.fps.toString())
            }
        }

        binding.applyCameraSettingsButton.setOnClickListener {
            val fps = binding.cameraFpsInput.text.toString().toIntOrNull() ?: 25
            val currentSettings = viewModel?.cameraSettings?.value
            
            val availableDevices = currentSettings?.availableDevices ?: emptyList()
            val selectedDeviceName = binding.cameraDeviceSpinner.text.toString()
            val devicePath = availableDevices.find { it.name == selectedDeviceName }?.path 
                ?: currentSettings?.selectedDevice

            val resolutions = currentSettings?.allowedResolutions ?: emptyList()
            val selectedResString = binding.cameraResolutionSpinner.text.toString()
            val resMatch = resolutions.find { "${it.width} x ${it.height}" == selectedResString }
            
            val width = resMatch?.width ?: currentSettings?.width ?: 1280
            val height = resMatch?.height ?: currentSettings?.height ?: 720
            
            viewModel?.updateCameraSettings(
                width = width,
                height = height,
                fps = fps, 
                device = devicePath
            )
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
                // Populate capture device dropdown
                val captureDevices = devices.captureDevices ?: emptyList()
                val captureNames = captureDevices.map { it.name }
                val captureAdapter = ArrayAdapter(requireContext(), R.layout.list_item_dropdown, captureNames)
                binding.captureDeviceSpinner.setAdapter(captureAdapter)

                devices.selectedCaptureDevice?.let { currentId ->
                    val device = captureDevices.find { it.id == currentId }
                    device?.let { binding.captureDeviceSpinner.setText(it.name, false) }
                }

                // Populate playback device dropdown
                val playbackDevices = devices.playbackDevices ?: emptyList()
                val playbackNames = playbackDevices.map { it.name }
                val playbackAdapter = ArrayAdapter(requireContext(), R.layout.list_item_dropdown, playbackNames)
                binding.playbackDeviceSpinner.setAdapter(playbackAdapter)

                devices.selectedPlaybackDevice?.let { currentId ->
                    val device = playbackDevices.find { it.id == currentId }
                    device?.let { binding.playbackDeviceSpinner.setText(it.name, false) }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
