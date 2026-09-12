package com.sshtab.pad.service

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.sshtab.pad.R
import com.sshtab.pad.log.SessionLog

/**
 * 国行 ROM 几乎不杀「正在播放」的应用。循环播放近乎无声的音频，
 * 把会话进程伪装成媒体播放，避免切走后被冷冻。
 */
class KeepAlivePlayer(private val context: Context) {
    private var player: MediaPlayer? = null
    private var session: MediaSessionCompat? = null

    val mediaSession: MediaSessionCompat? get() = session

    fun start() {
        stop()
        try {
            session = MediaSessionCompat(context, "sshpad-keepalive").apply {
                setMetadata(
                    MediaMetadataCompat.Builder()
                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "SSH 会话保活")
                        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "SSH Pad")
                        .build()
                )
                setPlaybackState(
                    PlaybackStateCompat.Builder()
                        .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE)
                        .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1f)
                        .build()
                )
                isActive = true
            }
            player = MediaPlayer.create(context, R.raw.silence)?.apply {
                isLooping = true
                setVolume(0.01f, 0.01f)
                setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
                if (Build.VERSION.SDK_INT >= 21) {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                }
                start()
            }
            SessionLog.event("keepalive player started playing=${player?.isPlaying}")
        } catch (t: Throwable) {
            SessionLog.event("keepalive player failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun stop() {
        try { player?.stop() } catch (_: Throwable) {}
        try { player?.release() } catch (_: Throwable) {}
        player = null
        try {
            session?.isActive = false
            session?.release()
        } catch (_: Throwable) {}
        session = null
    }
}
