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
        setupFaceAiSettings()
        setupAudioSettings()
    }

    /**
     * Setup Face AI settings controls
     */
    private fun setupFaceAiSettings() {
        viewModel?.faceStatus?.observe(viewLifecycleOwner) { status ->
            if (status != null) {
                // Populate backends from server's supported list
                val supportedBackends = status.supportedBackends ?: emptyList()
                if (supportedBackends.isNotEmpty()) {
                    val displayNames = supportedBackends.map { it.label }
                    val currentAdapter = binding.faceBackendSpinner.adapter as? ArrayAdapter<String>
                    
                    if (currentAdapter == null || currentAdapter.count != displayNames.size) {
                        val adapter = object : ArrayAdapter<String>(requireContext(), R.layout.list_item_dropdown, displayNames) {
                            override fun getFilter() = object : android.widget.Filter() {
                                override fun performFiltering(constraint: CharSequence?) = FilterResults().apply {
                                    values = displayNames
                                    count = displayNames.size
                                }
                                override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                                    notifyDataSetChanged()
                                }
                            }
                        }
                        binding.faceBackendSpinner.setAdapter(adapter)
                    }
                }

                // Update switch state safely
                if (binding.faceRecognitionSwitch.isChecked != status.enabled) {
                    binding.faceRecognitionSwitch.isChecked = status.enabled
                }
                
                val currentBackend = status.requestedBackend ?: status.backend
                val displayName = supportedBackends.find { it.id == currentBackend }?.label 
                    ?: currentBackend ?: ""
                
                if (!binding.faceBackendSpinner.isFocused && binding.faceBackendSpinner.text.toString() != displayName) {
                    binding.faceBackendSpinner.setText(displayName, false)
                }
                
                val statusText = if (status.enabled) {
                    if (status.available) "Active (${status.backend})" else "Unavailable: ${status.message ?: "not ready"}"
                } else {
                    "Disabled"
                }
                binding.faceSettingsStatus.text = "Status: $statusText"
            }
        }

        binding.faceRecognitionSwitch.setOnClickListener {
            val isEnabled = binding.faceRecognitionSwitch.isChecked
            val currentDisplayName = binding.faceBackendSpinner.text.toString()
            val currentStatus = viewModel?.faceStatus?.value
            val supportedBackends = currentStatus?.supportedBackends ?: emptyList()
            
            val backendValue = supportedBackends.find { it.label == currentDisplayName }?.id 
                ?: currentStatus?.requestedBackend
                ?: currentStatus?.backend
                ?: "auto"
            
            viewModel?.updateFaceSettings(isEnabled, backendValue)
        }

        binding.faceBackendSpinner.setOnItemClickListener { _, _, position, _ ->
            val adapter = binding.faceBackendSpinner.adapter as? ArrayAdapter<String>
            val selectedDisplayName = adapter?.getItem(position)
            val currentStatus = viewModel?.faceStatus?.value
            val supportedBackends = currentStatus?.supportedBackends ?: emptyList()
            
            val backendValue = supportedBackends.find { it.label == selectedDisplayName }?.id 
                ?: currentStatus?.requestedBackend
                ?: currentStatus?.backend
                ?: "auto"
            
            val enabled = binding.faceRecognitionSwitch.isChecked
            viewModel?.updateFaceSettings(enabled, backendValue)
        }

        binding.faceBackendSpinner.setOnClickListener {
            binding.faceBackendSpinner.showDropDown()
        }
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

        viewModel?.recentServers?.observe(viewLifecycleOwner) { servers ->
            val adapter = ArrayAdapter(requireContext(), R.layout.list_item_dropdown, servers)
            binding.serverAddressInput.setAdapter(adapter)
        }

        binding.serverAddressInput.setOnClickListener {
            binding.serverAddressInput.showDropDown()
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
