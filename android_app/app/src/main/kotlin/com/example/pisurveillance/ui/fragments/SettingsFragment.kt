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
        // Backend options with descriptive names matching templates/index.html
        val backendMap = mapOf(
            "auto" to "Auto (opencv → dlib)",
            "opencv" to "OpenCV (YuNet + SFace)",
            "dlib" to "dlib (HOG + ResNet)"
        )
        val displayNames = backendMap.values.toList()
        
        // Use a non-filtering adapter to ensure all 3 options are ALWAYS visible
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

        viewModel?.faceStatus?.observe(viewLifecycleOwner) { status ->
            if (status != null) {
                // IMPORTANT: Disable listeners before updating UI to avoid infinite loops
                binding.faceRecognitionSwitch.setOnCheckedChangeListener(null)
                binding.faceBackendSpinner.setOnItemClickListener(null)

                binding.faceRecognitionSwitch.isChecked = status.enabled
                
                // Update dropdown text using the descriptive name
                val displayName = backendMap[status.backend] ?: backendMap["auto"]
                // Only update text if it's different and user isn't currently interacting
                if (!binding.faceBackendSpinner.isFocused && binding.faceBackendSpinner.text.toString() != displayName) {
                    binding.faceBackendSpinner.setText(displayName, false)
                }
                
                val statusText = if (status.enabled) {
                    if (status.available) "Active (${status.backend})" else "Unavailable: ${status.message ?: "not ready"}"
                } else {
                    "Disabled"
                }
                binding.faceSettingsStatus.text = "Status: $statusText"

                // Re-enable listeners after UI update
                setupFaceListeners(backendMap, adapter)
            }
        }

        setupFaceListeners(backendMap, adapter)
        
        binding.faceBackendSpinner.setOnClickListener {
            binding.faceBackendSpinner.showDropDown()
        }
    }

    private fun setupFaceListeners(backendMap: Map<String, String>, adapter: ArrayAdapter<String>) {
        binding.faceRecognitionSwitch.setOnCheckedChangeListener { _, isChecked ->
            val currentStatus = viewModel?.faceStatus?.value
            val currentDisplayName = binding.faceBackendSpinner.text.toString()
            
            // CRITICAL: Find backend key by display name. 
            // If the field is empty (rare but possible during UI updates), don't send anything.
            val backendValue = backendMap.entries.find { it.value == currentDisplayName }?.key
            
            if (backendValue != null) {
                if (currentStatus == null || isChecked != currentStatus.enabled || backendValue != currentStatus.backend) {
                    viewModel?.updateFaceSettings(isChecked, backendValue)
                }
            }
        }

        binding.faceBackendSpinner.setOnItemClickListener { _, _, position, _ ->
            val selectedDisplayName = adapter.getItem(position)
            val backendValue = backendMap.entries.find { it.value == selectedDisplayName }?.key ?: "auto"
            val enabled = binding.faceRecognitionSwitch.isChecked
            
            val currentStatus = viewModel?.faceStatus?.value
            if (currentStatus == null || backendValue != currentStatus.backend) {
                viewModel?.updateFaceSettings(enabled, backendValue)
            }
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
