package com.example.pisurveillance.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.example.pisurveillance.MainActivity
import com.example.pisurveillance.R
import com.example.pisurveillance.databinding.FragmentStatusBinding
import kotlinx.coroutines.launch

/**
 * Fragment for displaying system status
 */
class StatusFragment : Fragment() {

    private var _binding: FragmentStatusBinding? = null
    private val binding get() = _binding!!

    private val viewModel by lazy {
        (activity as? MainActivity)?.getViewModel()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatusBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        observeConnectionStatus()
        observeSystemStatus()
        observeCameraInfo()

        binding.refreshStatusButton.setOnClickListener {
            it.animate().rotationBy(360f).setDuration(500).start()
            lifecycleScope.launch {
                viewModel?.fetchServerStatus()
            }
        }

        // Refresh status periodically
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(5000)  // Refresh every 5 seconds
                viewModel?.fetchServerStatus()
            }
        }
    }

    /**
     * Observe connection status
     */
    private fun observeConnectionStatus() {
        viewModel?.isConnected?.observe(viewLifecycleOwner) { isConnected ->
            binding.connectionText.text = if (isConnected) {
                binding.connectionIndicator.setColorFilter(
                    ContextCompat.getColor(requireContext(), R.color.success)
                )
                getString(R.string.connected)
            } else {
                binding.connectionIndicator.setColorFilter(
                    ContextCompat.getColor(requireContext(), R.color.error)
                )
                getString(R.string.disconnected)
            }
        }
    }

    /**
     * Observe system status (camera and audio)
     */
    private fun observeSystemStatus() {
        viewModel?.serverStatus?.observe(viewLifecycleOwner) { status ->
            if (status != null) {
                // Camera status
                binding.cameraStatusText.text = if (status.cameraRunning) {
                    binding.cameraStatusIcon.setColorFilter(
                        ContextCompat.getColor(requireContext(), R.color.success)
                    )
                    getString(R.string.running)
                } else {
                    binding.cameraStatusIcon.setColorFilter(
                        ContextCompat.getColor(requireContext(), R.color.error)
                    )
                    getString(R.string.stopped)
                }

                // Audio status
                binding.audioStatusText.text = if (status.audioRunning) {
                    binding.audioStatusIcon.setColorFilter(
                        ContextCompat.getColor(requireContext(), R.color.success)
                    )
                    getString(R.string.running)
                } else {
                    binding.audioStatusIcon.setColorFilter(
                        ContextCompat.getColor(requireContext(), R.color.error)
                    )
                    getString(R.string.stopped)
                }
            }
        }
    }

    /**
     * Observe and display camera information
     */
    private fun observeCameraInfo() {
        viewModel?.serverStatus?.observe(viewLifecycleOwner) { status ->
            if (status != null) {
                binding.cameraDeviceInfo.text = status.cameraDevice
                binding.cameraResolutionInfo.text = "${status.cameraWidth}x${status.cameraHeight}"
                binding.cameraFpsInfo.text = "${status.cameraFps} fps"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
