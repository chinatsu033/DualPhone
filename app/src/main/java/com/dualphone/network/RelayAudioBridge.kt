/**
 * 中继模式下的 PCM 采集/播放：通过回调把帧交给 WsRelayChannel，
 * 收到的二进制帧在此播放。格式与 UDP 通道相同（16kHz mono PCM16）。
 */
package com.dualphone.network

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import com.dualphone.common.AppBus
import com.dualphone.common.AudioFormat as Af
import com.dualphone.common.ProtocolCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class RelayAudioBridge(
    private val scope: CoroutineScope,
    private val sendPcm: (ByteArray) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var captureJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        startPlayback()
        startCapture()
        AppBus.log("中继音频桥已开")
    }

    fun stop() {
        running.set(false)
        captureJob?.cancel()
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioRecord = null
        audioTrack = null
    }

    fun onRemotePacket(data: ByteArray) {
        val unpacked = ProtocolCodec.unpackAudio(data) ?: return
        val track = audioTrack ?: return
        val pcm = unpacked.second
        try {
            track.write(pcm, 0, pcm.size)
        } catch (_: Exception) {
        }
    }

    private fun startCapture() {
        captureJob = scope.launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(
                Af.SAMPLE_RATE,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
            )
            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    Af.SAMPLE_RATE,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                    minBuf.coerceAtLeast(Af.FRAME_BYTES * 4),
                )
            } catch (e: SecurityException) {
                AppBus.log("无录音权限: ${e.message}", "E")
                return@launch
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                AppBus.log("AudioRecord 初始化失败", "W")
                recorder.release()
                return@launch
            }
            audioRecord = recorder
            val frame = ByteArray(Af.FRAME_BYTES)
            recorder.startRecording()
            while (isActive && running.get()) {
                val n = recorder.read(frame, 0, frame.size)
                if (n > 0) {
                    val payload = if (n == frame.size) frame else frame.copyOf(n)
                    sendPcm(payload)
                }
            }
        }
    }

    private fun startPlayback() {
        val minBuf = AudioTrack.getMinBufferSize(
            Af.SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_OUT_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
        )
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(Af.SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf.coerceAtLeast(Af.FRAME_BYTES * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                android.media.AudioManager.STREAM_VOICE_CALL,
                Af.SAMPLE_RATE,
                android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                minBuf.coerceAtLeast(Af.FRAME_BYTES * 4),
                AudioTrack.MODE_STREAM,
            )
        }
        audioTrack = track
        track.play()
    }
}
