package com.example.pisurveillance.ui.fragments

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.pisurveillance.MainActivity
import com.example.pisurveillance.R
import com.example.pisurveillance.databinding.FragmentVideoBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        setupCaptureButtons()
        observeStreams()
        observeRecordingState()
    }

    /**
     * Setup video display to show incoming frames
     */
    private fun setupVideoDisplay() {
        viewModel?.videoFrames?.observe(viewLifecycleOwner) { frame ->
            if (frame != null) {
                binding.videoDisplay.setImageBitmap(frame.bitmap)
                binding.faceOverlay.notifyVideoFrameReceived(frame.sequence)
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

    private fun setupCaptureButtons() {
        binding.snapshotButton.setOnClickListener {
            takeSnapshot()
        }

        binding.recordButton.setOnClickListener {
            val isRecording = viewModel?.isRecordingVideo?.value ?: false
            if (isRecording) {
                viewModel?.stopVideoRecording()
            } else {
                if (checkStoragePermission()) {
                    viewModel?.startVideoRecording()
                }
            }
        }
    }

    private fun observeRecordingState() {
        viewModel?.isRecordingVideo?.observe(viewLifecycleOwner) { isRecording ->
            if (isRecording) {
                binding.recordButton.text = "Stop"
                binding.recordButton.setIconResource(android.R.drawable.button_onoff_indicator_off)
                binding.recordingStatus.visibility = View.VISIBLE
                binding.recordingStatus.text = "Recording to Downloads..."
            } else {
                binding.recordButton.text = "Rec"
                binding.recordButton.setIconResource(android.R.drawable.button_onoff_indicator_on)
                binding.recordingStatus.visibility = View.GONE
            }
        }
    }

    private fun takeSnapshot() {
        val frame = viewModel?.videoFrames?.value ?: return
        val faceResult = binding.faceOverlay.getCurrentFaceResult()
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Create a copy of the bitmap to draw on
                val snapshot = frame.bitmap.copy(Bitmap.Config.ARGB_8888, true)
                
                // If there's an active face detection, draw it onto the bitmap copy
                if (faceResult != null) {
                    val canvas = android.graphics.Canvas(snapshot)
                    // We call the shared drawing logic from our overlay view
                    withContext(Dispatchers.Main) {
                        binding.faceOverlay.drawDetections(
                            canvas, 
                            snapshot.width, 
                            snapshot.height, 
                            faceResult
                        )
                    }
                }

                val name = "snapshot_${System.currentTimeMillis()}.jpg"
                saveBitmapToDisk(snapshot, name)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Snapshot saved with AI overlay", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to save snapshot")
            }
        }
    }

    private fun saveBitmapToDisk(bitmap: Bitmap, filename: String) {
        val context = requireContext()
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }
        }

        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            context.contentResolver.openOutputStream(uri).use { outputStream ->
                if (outputStream != null) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, outputStream)
                }
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

    private fun checkStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return true 
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), PERMISSION_REQUEST_CODE)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Timber.d("Permission granted")
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
