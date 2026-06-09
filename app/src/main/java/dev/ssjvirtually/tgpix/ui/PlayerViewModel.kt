package dev.ssjvirtually.tgpix.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun setPlaying(playing: Boolean) {
        _isPlaying.value = playing
    }

    fun seekTo(positionMs: Long) {
        _currentPosition.value = positionMs
    }

    fun setDuration(durationMs: Long) {
        _duration.value = durationMs
    }

    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }
}
