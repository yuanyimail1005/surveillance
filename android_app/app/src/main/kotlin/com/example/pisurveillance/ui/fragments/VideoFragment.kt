package com.example.pisurveillance.ui.fragments

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.pisurveillance.MainActivity
import com.example.pisurveillance.R
import com.example.pisurveillance.databinding.FragmentVideoBinding
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Fragment for displaying live video feed and audio controls
 */
class VideoFragment : Fragment() {

    private var _binding: FragmentVideoBinding? = null
    private val binding get() = _binding!!

    private val viewModel by lazy {
        (activity as? MainActivity)?.getViewModel()
    }

    private var recordingAudio = false
    private val PERMISSION_REQUEST_CODE = 101

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVideoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupVideoDisplay()
        setupAudioStatusDisplay()
        setupTalkbackButton()
        observeStreams()
    }

    /**
     * Setup video display to show incoming frames
     */
    private fun setupVideoDisplay() {
        viewModel?.videoFrames?.observe(viewLifecycleOwner) { frame ->
            if (frame != null) {
                binding.videoDisplay.setImageBitmap(frame)
                binding.loadingSpinner.visibility = View.GONE
            } else {
                binding.loadingSpinner.visibility = View.VISIBLE
            }
        }
    }

    /**
     * Setup audio status indicator
     */
    private fun setupAudioStatusDisplay() {
        viewModel?.audioConnected?.observe(viewLifecycleOwner) { connected ->
            binding.audioStatusText.text = if (connected) {
                binding.audioStatusIcon.setColorFilter(
                    ContextCompat.getColor(requireContext(), R.color.success)
                )
                "Audio Connected"
            } else {
                binding.audioStatusIcon.setColorFilter(
                    ContextCompat.getColor(requireContext(), R.color.error)
                )
                "Audio Disconnected"
            }
        }
    }

    /**
     * Setup talkback button with press-to-talk functionality
     */
    private fun setupTalkbackButton() {
        binding.talkbackButton.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!recordingAudio && checkMicrophonePermission()) {
                        recordingAudio = true
                        v.isPressed = true
                        binding.talkbackButton.text = getString(R.string.talkback_recording)
                        viewModel?.startTalkback()
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (recordingAudio) {
                        recordingAudio = false
                        v.isPressed = false
                        binding.talkbackButton.text = getString(R.string.talkback_button)
                        viewModel?.stopTalkback()
                    }
                    true
                }

                else -> false
            }
        }
    }

    /**
     * Observe stream connections
     */
    private fun observeStreams() {
        viewModel?.isConnected?.observe(viewLifecycleOwner) { isConnected ->
            if (!isConnected) {
                binding.loadingSpinner.visibility = View.VISIBLE
            }
        }

        viewModel?.connectionError?.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                Timber.e("Connection error: $error")
                binding.loadingSpinner.visibility = View.VISIBLE
            }
        }

        viewModel?.faceData?.observe(viewLifecycleOwner) { faceAiData ->
            binding.faceOverlay.setFaceData(faceAiData?.result)
        }
    }

    /**
     * Check if microphone permission is granted
     */
    private fun checkMicrophonePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    requireActivity(),
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    PERMISSION_REQUEST_CODE
                )
                false
            } else {
                true
            }
        } else {
            true
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Timber.d("Microphone permission granted")
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
