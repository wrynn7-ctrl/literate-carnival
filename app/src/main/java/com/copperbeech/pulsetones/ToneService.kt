package com.copperbeech.pulsetones

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlin.math.PI
import kotlin.math.sin

/**
 * Foreground media-playback service that synthesizes isochronic tones with AudioTrack.
 *
 * The synth is a sine oscillator gated by a 50%-duty-cycle envelope with 5 ms
 * linear ramps (anti-click). Pitch, beat rate, and volume are @Volatile and can
 * be changed live from the bound activity; changes are smoothed per-sample to
 * avoid zipper noise.
 */
class ToneService : Service() {

    companion object {
        const val CHANNEL_ID = "pulse_playback"
        const val NOTIF_ID = 1
        const val ACTION_START = "com.copperbeech.pulsetones.START"
        const val ACTION_STOP = "com.copperbeech.pulsetones.STOP"
        const val EXTRA_PITCH = "pitch"
        const val EXTRA_RATE = "rate"
        const val EXTRA_VOLUME = "volume"
        const val SAMPLE_RATE = 44100
        const val RAMP_SECONDS = 0.005f
    }

    inner class LocalBinder : Binder() {
        val service: ToneService get() = this@ToneService
    }

    private val binder = LocalBinder()

    // Live-tunable parameters (slider values, not audio-domain values).
    @Volatile var pitchHz: Float = 180f
    @Volatile var rateHz: Float = 10f
    @Volatile var volume: Float = 0.5f   // 0..1 from the UI slider

    @Volatile private var running = false
    private var audioThread: Thread? = null

    /** Set by the bound activity so the UI can react when playback stops (e.g. notification Stop). */
    var onStopped: (() -> Unit)? = null

    private var focusRequest: AudioFocusRequest? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val p = intent.getFloatExtra(EXTRA_PITCH, 180f)
                val r = intent.getFloatExtra(EXTRA_RATE, 10f)
                val v = intent.getFloatExtra(EXTRA_VOLUME, 0.5f)
                startTone(p, r, v)
            }
            ACTION_STOP -> stopTone()
        }
        return START_NOT_STICKY
    }

    fun isPlaying(): Boolean = running

    @Synchronized
    fun startTone(pitch: Float, rate: Float, vol: Float) {
        pitchHz = pitch
        rateHz = rate
        volume = vol
        if (running) return
        if (!requestAudioFocus()) return

        // A foreground service must post its notification promptly after starting.
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        running = true
        audioThread = Thread(::audioLoop, "PulseToneSynth").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    @Synchronized
    fun stopTone() {
        if (!running) {
            stopSelf()
            return
        }
        running = false
        try { audioThread?.join(500) } catch (_: InterruptedException) {}
        audioThread = null
        abandonAudioFocus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        onStopped?.invoke()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    // ---------------- Synthesis ----------------

    private fun audioLoop() {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track.play()

        val buf = FloatArray(2048)
        var phase = 0.0            // oscillator phase, radians
        var gatePos = 0.0          // position within one beat period, 0..1
        var smoothFreq = pitchHz.toDouble()
        var smoothGain = 0.0
        val twoPi = 2.0 * PI

        // Ramp playback in from silence.
        while (running) {
            val targetFreq = pitchHz.toDouble()
            val rate = rateHz.toDouble().coerceIn(0.1, 60.0)
            val v = volume.toDouble().coerceIn(0.0, 1.0)
            val targetGain = v * v * 0.5          // perceptual curve, headphone-safe cap
            val gateStep = rate / SAMPLE_RATE
            // Ramp width as a fraction of the period (5 ms, but never eat the whole "on" half).
            val rampFrac = (RAMP_SECONDS * rate).coerceAtMost(0.2)

            for (i in buf.indices) {
                // Smooth parameter motion to avoid clicks/zipper noise.
                smoothFreq += (targetFreq - smoothFreq) * 0.0005
                smoothGain += (targetGain - smoothGain) * 0.001

                // Gate envelope: on for the first half of the period, with linear ramps.
                val env = when {
                    gatePos < rampFrac -> gatePos / rampFrac
                    gatePos < 0.5 - rampFrac -> 1.0
                    gatePos < 0.5 -> (0.5 - gatePos) / rampFrac
                    else -> 0.0
                }

                buf[i] = (sin(phase) * env * smoothGain).toFloat()

                phase += twoPi * smoothFreq / SAMPLE_RATE
                if (phase > twoPi) phase -= twoPi
                gatePos += gateStep
                if (gatePos >= 1.0) gatePos -= 1.0
            }
            track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
        }

        track.stop()
        track.release()
    }

    // ---------------- Audio focus ----------------

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS) stopTone()
    }

    private fun requestAudioFocus(): Boolean {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        return am.requestAudioFocus(focusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    // ---------------- Notification ----------------

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Tone playback", NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Shown while isochronic tones are playing" }
            )
        }

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ToneService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Pulse Tones")
            .setContentText("Isochronic tones playing")
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
