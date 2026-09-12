package com.sshtab.pad.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import com.sshtab.pad.log.SessionLog
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * 国行用 mediaPlayback 前台服务保活。不创建 MediaSession，
 * 避免灵动岛 / 媒体通知出现播放动画和暂停按钮（点暂停会冻死进程）。
 * AudioTrack 写 18Hz 极弱正弦，人耳听不见，OEM 也不会当静音优化掉。
 */
class KeepAlivePlayer(private val context: Context) {
    private var track: AudioTrack? = null
    private val running = AtomicBoolean(false)
    private var writer: Thread? = null

    fun start() {
        stop()
        try {
            val sampleRate = 8000
            val min = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufSize = min.coerceAtLeast(1600)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val t = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            t.setVolume(0.08f)
            val period = (sampleRate / 18).coerceAtLeast(8)
            val frame = ByteArray(period * 2)
            for (i in 0 until period) {
                val s = (sin(2.0 * PI * i / period) * 280).toInt().toShort()
                frame[i * 2] = (s.toInt() and 0xff).toByte()
                frame[i * 2 + 1] = (s.toInt() shr 8).toByte()
            }
            running.set(true)
            track = t
            t.play()
            writer = thread(name = "keepalive-audio", isDaemon = false) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                while (running.get()) {
                    try {
                        if (t.write(frame, 0, frame.size) < 0) break
                    } catch (_: Throwable) {
                        break
                    }
                }
            }
            SessionLog.event("keepalive audiotrack state=${t.playState} no-media-session")
        } catch (t: Throwable) {
            SessionLog.event("keepalive audio failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun stop() {
        running.set(false)
        try { writer?.interrupt() } catch (_: Throwable) {}
        writer = null
        try { track?.pause() } catch (_: Throwable) {}
        try { track?.release() } catch (_: Throwable) {}
        track = null
    }
}
