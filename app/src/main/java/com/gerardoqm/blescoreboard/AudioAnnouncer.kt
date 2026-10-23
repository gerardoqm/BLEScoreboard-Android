package com.gerardoqm.blescoreboard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The sounds the scoreboard plays, made in code (no sound files to ship). */
enum class GameSound { Whistle, DoubleWhistle, Horn, LongHorn }

/**
 * Speaks announcements and plays whistles and horns, one after another, through whatever the phone plays media on
 * (its speaker, or a paired Bluetooth speaker). Music in other apps is lowered while it speaks and comes back after.
 */
class AudioAnnouncer(private val context: Context, private val languageCode: () -> String, private val voiceName: () -> String) {
    private sealed class Item { data class Sound(val sound: GameSound) : Item(); data class Speech(val text: String) : Item() }

    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Item>()
    private var busy = false
    private var ttsReady = false
    private var ttsInitDone = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        main.post { ttsReady = status == TextToSpeech.SUCCESS; ttsInitDone = true; if (!busy) next() }
    }
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var focusRequest: AudioFocusRequest? = null
    private var track: AudioTrack? = null
    private val soundCache = HashMap<GameSound, ShortArray>()
    private val watchdog = Runnable { finished() }

    init {
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { main.post { finished() } }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { main.post { finished() } }
            override fun onError(utteranceId: String?, errorCode: Int) { main.post { finished() } }
        })
    }

    fun play(sound: GameSound) { enqueue(Item.Sound(sound)) }
    fun speak(text: String) { enqueue(Item.Speech(text)) }

    private fun enqueue(item: Item) {
        main.post {
            queue.addLast(item)
            if (!busy) next()
        }
    }

    private fun next() {
        val item = queue.removeFirstOrNull()
        if (item == null) {
            busy = false
            main.removeCallbacks(watchdog)
            abandonFocus()           // music in other apps comes back
            return
        }
        if (item is Item.Speech && !ttsReady && ttsInitDone) { next(); return }   // no TTS engine: drop speech, keep the sounds going
        if (item is Item.Speech && !ttsReady) {   // the engine is still starting: wait for its init callback
            queue.addFirst(item)
            busy = false             // otherwise the init callback's `if (!busy) next()` never resumes the queue
            main.removeCallbacks(watchdog)
            abandonFocus()
            return
        }
        busy = true
        requestFocus()
        var expectedMs = 8000L
        when (item) {
            is Item.Sound -> {
                val samples = soundCache.getOrPut(item.sound) { pcm(item.sound) }
                expectedMs = samples.size * 1000L / RATE + 400
                playPcm(samples)
            }
            is Item.Speech -> {
                tts.language = locale()
                voice()?.let { tts.voice = it }
                tts.setSpeechRate(0.96f)          // a little slower than the default sounds less hurried
                expectedMs = max(6000L, item.text.length * 120L)
                tts.speak(item.text, TextToSpeech.QUEUE_FLUSH, null, "announce-${System.nanoTime()}")
            }
        }
        // if a callback never comes, do not block the queue
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, expectedMs)
    }

    private fun finished() {
        if (!busy) return
        main.removeCallbacks(watchdog)
        track?.release(); track = null
        next()
    }

    private fun playPcm(samples: ShortArray) {
        track?.release()
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(samples, 0, samples.size)
        t.notificationMarkerPosition = samples.size
        t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(track: AudioTrack?) { main.post { finished() } }
            override fun onPeriodicNotification(track: AudioTrack?) {}
        }, main)
        track = t
        t.play()
    }

    private fun requestFocus() {
        if (focusRequest != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).build()
        audioManager.requestAudioFocus(request)
        focusRequest = request
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    // MARK: - Voices

    private fun locale(): Locale = if (languageCode() == "es") Locale("es", "MX") else Locale.US

    /** The voices installed for the app's language, best first: higher quality, offline, then the preferred region. */
    fun voices(): List<Voice> {
        if (!ttsReady) return emptyList()
        val code = languageCode()
        val regions = if (code == "es") listOf("MX", "US", "ES") else listOf("US", "GB", "AU", "CA")
        return (runCatching { tts.voices }.getOrNull() ?: emptySet())
            .filter { it.locale.language == code && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareBy<Voice>({ it.isNetworkConnectionRequired }, { -it.quality }, { regions.indexOf(it.locale.country).let { i -> if (i < 0) 99 else i } }, { it.name }))
    }

    /** The voice chosen on the Alerts and Sound page, or the best one installed. */
    private fun voice(): Voice? {
        val available = voices()
        val chosen = voiceName()
        return available.firstOrNull { it.name == chosen && chosen.isNotEmpty() } ?: available.firstOrNull()
    }

    fun shutdown() { tts.shutdown(); track?.release() }

    companion object {
        private const val RATE = 44_100

        /** Whistles and horns, made in code. */
        private fun pcm(sound: GameSound): ShortArray {
            val samples = ArrayList<Short>()
            fun note(seconds: Double, wave: (Double) -> Double) {
                val count = (seconds * RATE).toInt()
                for (i in 0 until count) {
                    val t = i.toDouble() / RATE
                    val envelope = max(0.0, min(1.0, min(t / 0.02, (seconds - t) / 0.08)))
                    samples += (max(-1.0, min(1.0, wave(t) * envelope)) * 0.8 * 32767).toInt().toShort()
                }
            }
            fun silence(seconds: Double) = repeat((seconds * RATE).toInt()) { samples += 0 }
            // a referee's pea whistle: a high tone wobbling about 28 times a second
            fun whistle(seconds: Double) = note(seconds) { t -> 0.8 * sin(2 * PI * 3000 * t + 4 * sin(2 * PI * 28 * t)) + 0.15 * sin(2 * PI * 6000 * t) }
            // an air horn: two slightly different low tones with their harmonics, so they beat
            fun horn(seconds: Double) = note(seconds) { t ->
                var sum = 0.0
                for (k in 1..6) sum += (sin(2 * PI * 392 * k * t) + sin(2 * PI * 397 * k * t)) / k
                sum / 3.2
            }
            when (sound) {
                GameSound.Whistle -> whistle(0.7)
                GameSound.DoubleWhistle -> { whistle(0.35); silence(0.15); whistle(0.35) }
                GameSound.Horn -> horn(1.2)
                GameSound.LongHorn -> horn(2.4)
            }
            return samples.toShortArray()
        }
    }
}
