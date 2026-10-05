package com.vibe.music.playback

import android.media.audiofx.Equalizer
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.vibe.music.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Vyrovnání hlasitosti „za běhu“: měří průměrnou hlasitost (RMS) za posledních pár sekund
 * a pomalu dorovnává zesílení k cílové úrovni. Hlasité skladby ztlumí, tiché mírně zesílí
 * (max +4 dB) a měkký limiter hlídá, aby nevzniklo přebuzení.
 * YouTube údaj o hlasitosti bez přihlášení nevydá, proto se měří přímo z audia.
 */
class VolumeNormalizer : BaseAudioProcessor() {
    @Volatile var enabled = false

    private var meanSquare = TARGET_RMS * TARGET_RMS
    private var gain = 1f
    // Časové konstanty v počtu vzorků (nastaví se podle sample rate)
    private var envelopeAlpha = 0f
    private var gainAlpha = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        val samplesPerSecond = inputAudioFormat.sampleRate * inputAudioFormat.channelCount
        envelopeAlpha = 1f / (samplesPerSecond * 3f)   // měření hlasitosti ~3 s
        gainAlpha = 1f / (samplesPerSecond * 1.5f)     // změna zesílení ~1,5 s (žádné „pumpování“)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        if (!enabled) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        while (input.remaining() >= 2) {
            val sample = input.short / 32768f
            meanSquare += (sample * sample - meanSquare) * envelopeAlpha
            val rms = sqrt(meanSquare).coerceAtLeast(0.01f)
            val target = (TARGET_RMS / rms).coerceIn(MIN_GAIN, MAX_GAIN)
            gain += (target - gain) * gainAlpha
            var v = sample * gain
            // Měkký limiter nad 0,9
            if (abs(v) > 0.9f) v = (if (v > 0) 1f else -1f) * (0.9f + (abs(v) - 0.9f) / (1f + (abs(v) - 0.9f) * 10f))
            out.putShort((v.coerceIn(-1f, 0.999f) * 32767f).toInt().toShort())
        }
        out.flip()
    }

    override fun onReset() {
        meanSquare = TARGET_RMS * TARGET_RMS
        gain = 1f
    }

    companion object {
        private const val TARGET_RMS = 0.2f   // ≈ −14 dBFS, podobně jako normalizace streamovacích služeb
        private const val MIN_GAIN = 0.3f     // max. ztlumení ≈ −10 dB
        private const val MAX_GAIN = 1.6f     // max. zesílení ≈ +4 dB
    }
}

data class EqBand(val centerHz: Int)

data class EqInfo(
    val bands: List<EqBand>,
    val minLevel: Int,
    val maxLevel: Int,
    val presets: List<String>,
    /** Aktuální úrovně pásem (mB) – po výběru předvolby se přečtou z ekvalizéru. */
    val levels: List<Int>,
)

/**
 * Systémový ekvalizér napojený na audio session přehrávače.
 * Instanci drží služba přehrávání, UI čte [info] a mění nastavení přes Settings.
 */
class EqualizerController {
    private var equalizer: Equalizer? = null
    private val _info = MutableStateFlow<EqInfo?>(null)
    /** null = ekvalizér zatím není k dispozici (nic nehrálo, nebo ho zařízení nepodporuje). */
    val info: StateFlow<EqInfo?> = _info

    fun attach(audioSessionId: Int, settings: Settings) {
        release()
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId == 0) return
        equalizer = runCatching { Equalizer(0, audioSessionId) }
            .onFailure { Log.w("Equalizer", "Ekvalizér není k dispozici", it) }
            .getOrNull()
        apply(settings)
    }

    fun apply(s: Settings) {
        val eq = equalizer ?: return
        runCatching {
            if (s.eqPreset >= 0 && s.eqPreset < eq.numberOfPresets) {
                eq.usePreset(s.eqPreset.toShort())
            } else if (s.eqLevels.size == eq.numberOfBands.toInt()) {
                s.eqLevels.forEachIndexed { i, level -> eq.setBandLevel(i.toShort(), level.toShort()) }
            }
            eq.enabled = s.eqEnabled
            publish(eq)
        }.onFailure { Log.w("Equalizer", "Nastavení ekvalizéru selhalo", it) }
    }

    private fun publish(eq: Equalizer) {
        val range = eq.bandLevelRange
        val bands = (0 until eq.numberOfBands).map { EqBand(eq.getCenterFreq(it.toShort()) / 1000) }
        _info.value = EqInfo(
            bands = bands,
            minLevel = range[0].toInt(),
            maxLevel = range[1].toInt(),
            presets = (0 until eq.numberOfPresets).map { eq.getPresetName(it.toShort()) },
            levels = (0 until eq.numberOfBands).map { eq.getBandLevel(it.toShort()).toInt() },
        )
    }

    fun release() {
        runCatching { equalizer?.release() }
        equalizer = null
    }
}
