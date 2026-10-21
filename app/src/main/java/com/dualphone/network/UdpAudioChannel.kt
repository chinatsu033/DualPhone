/**
 * UDP 音频通道：PCM16LE mono 16kHz，固定头 magic+seq+len。
 * 尽力而为，目标延迟 <500ms；丢包不重传。
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
import com.dualphone.common.Ports
import com.dualphone.common.ProtocolCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class UdpAudioChannel(
    private val scope: CoroutineScope,
) {
    private val running = AtomicBoolean(false)
    private var sendJob: Job? = null
    private var recvJob: Job? = null
    private var socket: DatagramSocket? = null
    private var peerAddress: InetAddress? = null
    private val seq = AtomicInteger(0)
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    fun start(peerIp: String) {
        if (!running.compareAndSet(false, true)) return
        peerAddress = InetAddress.getByName(peerIp)
        scope.launch(Dispatchers.IO) {
            try {
                val sock = DatagramSocket(Ports.UDP_AUDIO)
                sock.soTimeout = 1000
                socket = sock
                AppBus.log("UDP 音频已开 :${Ports.UDP_AUDIO} → $peerIp")
                startPlayback(sock)
                startCapture(sock)
            } catch (e: Exception) {
                AppBus.log("UDP 音频启动失败: ${e.message}", "E")
                stop()
            }
        }
    }

    fun stop() {
        running.set(false)
        sendJob?.cancel()
        recvJob?.cancel()
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        audioRecord = null
        audioTrack = null
        socket = null
    }

    private fun startCapture(sock: DatagramSocket) {
        sendJob = scope.launch(Dispatchers.IO) {
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
                AppBus.log("AudioRecord 初始化失败（OEM 可能禁止通话上行采集）", "W")
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
                    val packetData = ProtocolCodec.packAudio(seq.getAndIncrement(), payload)
                    val addr = peerAddress ?: continue
                    try {
                        sock.send(DatagramPacket(packetData, packetData.size, addr, Ports.UDP_AUDIO))
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun startPlayback(sock: DatagramSocket) {
        recvJob = scope.launch(Dispatchers.IO) {
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
            val buf = ByteArray(Af.HEADER_SIZE + Af.FRAME_BYTES * 2)
            while (isActive && running.get()) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    sock.receive(packet)
                    val unpacked = ProtocolCodec.unpackAudio(packet.data.copyOf(packet.length)) ?: continue
                    val pcm = unpacked.second
                    track.write(pcm, 0, pcm.size)
                } catch (_: java.net.SocketTimeoutException) {
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }
    }
}
