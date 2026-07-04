package com.example.pisurveillance.webrtc

import android.content.Context
import com.example.pisurveillance.api.SurveillanceService
import com.example.pisurveillance.api.WebRtcSdp
import com.example.pisurveillance.models.FaceAiData
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.*
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import timber.log.Timber

/**
 * Manages WebRTC connection for video, audio, and data channels
 */
class WebRtcManager(
    private val context: Context,
    private val apiService: SurveillanceService
) {
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var faceDataChannel: DataChannel? = null
    private var localAudioTrack: AudioTrack? = null
    
    private val _faceData = MutableStateFlow<FaceAiData?>(null)
    val faceData: StateFlow<FaceAiData?> = _faceData

    private val _currentFrameSeq = MutableStateFlow<Long>(0)
    val currentFrameSeq: StateFlow<Long> = _currentFrameSeq

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack

    private val _remoteAudioTrack = MutableStateFlow<AudioTrack?>(null)
    val remoteAudioTrack: StateFlow<AudioTrack?> = _remoteAudioTrack

    private val _isTalkbackActive = MutableStateFlow(false)
    val isTalkbackActive: StateFlow<Boolean> = _isTalkbackActive

    val eglBase: EglBase = EglBase.create()
    private var audioDeviceModule: AudioDeviceModule? = null

    init {
        initWebRtc()
    }

    private fun initWebRtc() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        val options = PeerConnectionFactory.Options()
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
    }

    fun connect() {
        scope.launch {
            try {
                // For local network, we don't strictly need STUN. 
                // Using empty list to prioritize host candidates.
                val iceServers = emptyList<PeerConnection.IceServer>()
                
                val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
                rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                
                peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                    override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                        Timber.d("IceConnectionChange: $state")
                        if (state == PeerConnection.IceConnectionState.CONNECTED) {
                            _isConnected.value = true
                        } else if (state == PeerConnection.IceConnectionState.DISCONNECTED || state == PeerConnection.IceConnectionState.FAILED) {
                            _isConnected.value = false
                        }
                    }
                    override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState?) {}
                    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                        Timber.d("PeerConnectionState: $newState")
                        if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                            _isConnected.value = true
                        } else if (newState == PeerConnection.PeerConnectionState.DISCONNECTED || newState == PeerConnection.PeerConnectionState.FAILED || newState == PeerConnection.PeerConnectionState.CLOSED) {
                            _isConnected.value = false
                        }
                    }
                    override fun onIceConnectionReceivingChange(p0: Boolean) {}
                    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                        Timber.d("IceGatheringChange: $state")
                    }
                    override fun onIceCandidate(candidate: IceCandidate?) {
                        Timber.d("IceCandidate: $candidate")
                    }
                    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                    override fun onAddStream(stream: MediaStream?) {}
                    override fun onRemoveStream(stream: MediaStream?) {}
                    override fun onDataChannel(channel: DataChannel?) {
                        Timber.d("New Remote DataChannel: ${channel?.label()}")
                    }
                    override fun onRenegotiationNeeded() {}
                    override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                        val track = receiver?.track()
                        if (track is VideoTrack) {
                            Timber.d("Received remote video track")
                            _remoteVideoTrack.value = track
                        } else if (track is AudioTrack) {
                            Timber.d("Received remote audio track")
                            _remoteAudioTrack.value = track
                        }
                    }
                    override fun onTrack(transceiver: RtpTransceiver?) {}
                })

                // Add transceivers
                peerConnection?.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
                // Use SEND_RECV for audio to allow talkback
                peerConnection?.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV))

                // Create local audio track (but don't attach it to peer connection yet, 
                // PeerConnection.addTransceiver already created a sender)
                val audioSource = peerConnectionFactory?.createAudioSource(MediaConstraints())
                localAudioTrack = peerConnectionFactory?.createAudioTrack("local_audio", audioSource)
                localAudioTrack?.setEnabled(false) // Start muted

                // Find the audio sender and set the track
                peerConnection?.senders?.forEach { sender ->
                    if (sender.track()?.kind() == MediaStreamTrack.AUDIO_TRACK_KIND || (sender.track() == null && sender.parameters.encodings.isNotEmpty())) {
                        // This logic is a bit brittle, usually we'd keep the transceiver reference
                    }
                }
                
                // Refined: set track on the correct sender
                peerConnection?.transceivers?.forEach { transceiver ->
                    if (transceiver.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO) {
                        transceiver.sender.setTrack(localAudioTrack, true)
                    }
                }

                // Create DataChannels
                val faceInit = DataChannel.Init()
                faceDataChannel = peerConnection?.createDataChannel("face-data", faceInit)
                faceDataChannel?.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(p0: Long) {}
                    override fun onStateChange() {}
                    override fun onMessage(buffer: DataChannel.Buffer?) {
                        if (buffer == null) return
                        val data = ByteArray(buffer.data.remaining())
                        buffer.data.get(data)
                        val text = String(data)
                        try {
                            val json = gson.fromJson(text, com.google.gson.JsonObject::class.java)
                            val type = json.get("type")?.asString
                            
                            if (type == "face_data") {
                                val msg = gson.fromJson(text, FaceAiData::class.java)
                                _faceData.value = msg
                                // If AI is disabled, clear it
                                if (!msg.enabled) {
                                    _faceData.value = null
                                } 
                            } else if (type == "frame_meta") {
                                val seq = json.get("broadcast_frame_seq")?.asLong ?: 0
                                _currentFrameSeq.value = seq
                            }
                        } catch (e: Exception) {
                            Timber.w("Failed to parse DataChannel message: $text")
                        }
                    }
                })

                // Create offer
                val constraints = MediaConstraints()
                peerConnection?.createOffer(object : SdpObserver {
                    override fun onCreateSuccess(description: SessionDescription?) {
                        if (description == null) return
                        scope.launch {
                            peerConnection?.setLocalDescription(object : SdpObserver {
                                override fun onCreateSuccess(p0: SessionDescription?) {}
                                override fun onSetSuccess() {
                                    scope.launch {
                                        // Wait for ICE gathering completion with timeout
                                        var timeoutCount = 0
                                        while (peerConnection?.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE && timeoutCount < 100) {
                                            delay(50)
                                            timeoutCount++
                                        }
                                        
                                        val localDescription = peerConnection?.localDescription
                                        if (localDescription != null) {
                                            try {
                                                val offerSdp = WebRtcSdp(localDescription.description, localDescription.type.canonicalForm())
                                                val response = apiService.connectWebRtc(offerSdp)
                                                if (response.isSuccessful) {
                                                    val answer = response.body()
                                                    if (answer != null) {
                                                        val answerSdp = SessionDescription(
                                                            SessionDescription.Type.fromCanonicalForm(answer.type),
                                                            answer.sdp
                                                        )
                                                        peerConnection?.setRemoteDescription(object : SdpObserver {
                                                            override fun onCreateSuccess(p0: SessionDescription?) {}
                                                            override fun onSetSuccess() {
                                                                Timber.d("Remote description set successfully")
                                                            }
                                                            override fun onCreateFailure(p0: String?) {}
                                                            override fun onSetFailure(p0: String?) {
                                                                Timber.e("Set Remote Description Failure: $p0")
                                                            }
                                                        }, answerSdp)
                                                    }
                                                } else {
                                                    Timber.e("WebRTC connect API failed: ${response.code()}")
                                                }
                                            } catch (e: Exception) {
                                                Timber.e(e, "Error in WebRTC signaling exchange")
                                            }
                                        }
                                    }
                                }
                                override fun onCreateFailure(p0: String?) {}
                                override fun onSetFailure(p0: String?) {
                                    Timber.e("Set Local Description Failure: $p0")
                                }
                            }, description)
                        }
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(p0: String?) {
                        Timber.e("Create Offer Failure: $p0")
                    }
                    override fun onSetFailure(p0: String?) {}
                }, constraints)

            } catch (e: Exception) {
                Timber.e(e, "WebRTC connection failed")
            }
        }
    }

    fun startTalkback() {
        Timber.d("Starting WebRTC talkback via AudioTrack")
        localAudioTrack?.setEnabled(true)
        _isTalkbackActive.value = true
    }

    fun stopTalkback() {
        Timber.d("Stopping WebRTC talkback via AudioTrack")
        localAudioTrack?.setEnabled(false)
        _isTalkbackActive.value = false
    }

    fun disconnect() {
        faceDataChannel?.dispose()
        localAudioTrack?.dispose()
        peerConnection?.close()
        peerConnection = null
        _isConnected.value = false
        _remoteVideoTrack.value = null
        _remoteAudioTrack.value = null
    }
}
