package com.orico.gestureassistant.keepalive

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * 可插拔的无声音频辅助保活层。
 *
 * 通过流式 AudioTrack 持续写入全 0 PCM 数据；任何音频初始化、写入或释放失败都会静默降级，
 * 绝不影响手势服务主流程。
 */
class SilentAudioKeepAlive {
    private val lock = Any()
    private var session: Session? = null

    fun start() {
        runCatching {
            synchronized(lock) {
                if (session != null) return
                val newSession = Session()
                val thread = Thread(
                    { playSilence(newSession) },
                    "SilentAudioKeepAlive",
                ).apply { isDaemon = true }
                newSession.thread = thread
                session = newSession
                runCatching { thread.start() }.onFailure {
                    session = null
                    newSession.active.set(false)
                }
            }
        }
    }

    fun stop() {
        runCatching {
            val oldSession = synchronized(lock) {
                session?.also {
                    session = null
                    it.active.set(false)
                }
            } ?: return

            // release 可解除阻塞中的 write；所有调用均保护，系统拒绝音频时直接静默降级。
            runCatching { oldSession.track?.pause() }
            runCatching { oldSession.track?.flush() }
            runCatching { oldSession.track?.release() }
            oldSession.track = null
            runCatching { oldSession.thread?.interrupt() }
            runCatching { oldSession.thread?.join(STOP_JOIN_TIMEOUT_MS) }
        }
    }

    private fun playSilence(current: Session) {
        var track: AudioTrack? = null
        try {
            val minBufferSize = runCatching {
                AudioTrack.getMinBufferSize(
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            }.getOrDefault(AudioTrack.ERROR)
            if (minBufferSize <= 0 || !current.active.get()) return

            val bufferSize = max(minBufferSize, SILENCE_BUFFER_SAMPLES * Short.SIZE_BYTES)
            val audioTrack = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE_HZ)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrNull() ?: return
            track = audioTrack
            current.track = audioTrack

            val initialized = runCatching {
                audioTrack.state == AudioTrack.STATE_INITIALIZED
            }.getOrDefault(false)
            if (!current.active.get() || !initialized) return
            runCatching { audioTrack.setVolume(0f) }.getOrElse { return }
            runCatching { audioTrack.play() }.getOrElse { return }

            val silence = ShortArray(SILENCE_BUFFER_SAMPLES)
            while (current.active.get()) {
                val written = runCatching {
                    audioTrack.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
                }.getOrNull() ?: break
                if (written <= 0) break
            }
        } catch (_: Throwable) {
            // 音频能力可能被 ROM 或系统策略拒绝；辅助层失败不向上传播。
        } finally {
            current.track = null
            runCatching { track?.pause() }
            runCatching { track?.flush() }
            runCatching { track?.release() }
            synchronized(lock) {
                if (session === current) session = null
            }
        }
    }

    private class Session {
        val active = AtomicBoolean(true)
        var thread: Thread? = null
        @Volatile var track: AudioTrack? = null
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 8_000
        const val SILENCE_BUFFER_SAMPLES = 512
        const val STOP_JOIN_TIMEOUT_MS = 500L
    }
}
