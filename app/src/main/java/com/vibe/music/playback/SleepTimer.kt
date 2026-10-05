package com.vibe.music.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface SleepState {
    data object Off : SleepState
    data class At(val endsAt: Long) : SleepState
    data object EndOfTrack : SleepState
}

/**
 * Časovač vypnutí. Posledních 15 s plynule ztlumí ([fadeFactor] čte služba přehrávání),
 * pak přehrávání pozastaví. Varianta „do konce skladby“ zastaví po dohrání aktuální skladby.
 */
class SleepTimer(private val scope: CoroutineScope) {
    /** Nastavuje služba přehrávání – pozastaví přehrávač. */
    @Volatile var pauseAction: (() -> Unit)? = null

    private val _state = MutableStateFlow<SleepState>(SleepState.Off)
    val state: StateFlow<SleepState> = _state

    private val _fadeFactor = MutableStateFlow(1f)
    val fadeFactor: StateFlow<Float> = _fadeFactor

    private var job: Job? = null

    fun start(minutes: Int) {
        cancel()
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        _state.value = SleepState.At(endsAt)
        job = scope.launch(Dispatchers.Main) {
            while (true) {
                val left = endsAt - System.currentTimeMillis()
                if (left <= 0) break
                _fadeFactor.value = (left / FADE_MS.toFloat()).coerceIn(0f, 1f)
                delay(if (left < FADE_MS) 200 else 1000)
            }
            pauseAction?.invoke()
            reset()
        }
    }

    fun startEndOfTrack() {
        cancel()
        _state.value = SleepState.EndOfTrack
    }

    /** Volá služba při automatickém přechodu na další skladbu. */
    fun onTrackEnded(): Boolean {
        if (_state.value != SleepState.EndOfTrack) return false
        reset()
        return true
    }

    fun cancel() {
        job?.cancel()
        reset()
    }

    private fun reset() {
        job = null
        _state.value = SleepState.Off
        _fadeFactor.value = 1f
    }

    companion object {
        private const val FADE_MS = 15_000L
    }
}
