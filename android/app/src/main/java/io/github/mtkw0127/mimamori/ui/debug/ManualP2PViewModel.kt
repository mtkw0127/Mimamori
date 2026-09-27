package io.github.mtkw0127.mimamori.ui.debug

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mtkw0127.mimamori.webrtc.LocalCameraSession
import io.github.mtkw0127.mimamori.webrtc.ManualP2PSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack
import javax.inject.Inject

enum class ManualP2PRole {
    /** offer を作る側（Recorder） */
    Offerer,

    /** answer を返す側（Viewer） */
    Answerer,
}

data class ManualP2PUiState(
    val role: ManualP2PRole,
    val localSdp: String = "",
    val remoteSdpInput: String = "",
    val connectionState: PeerConnection.PeerConnectionState = PeerConnection.PeerConnectionState.NEW,
    val remoteVideoTrack: VideoTrack? = null,
    val isBusy: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class ManualP2PViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    private val session: ManualP2PSession,
    val eglBase: EglBase,
    private val cameraSession: LocalCameraSession,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(
        ManualP2PUiState(role = ManualP2PRole.valueOf(checkNotNull(savedStateHandle.get<String>("role")))),
    )
    val uiState: StateFlow<ManualP2PUiState> = _uiState.asStateFlow()

    /** Offerer 側で表示する、自分のカメラ映像（Answerer は使わない） */
    val localVideoTrack: StateFlow<VideoTrack?> = cameraSession.videoTrack
    val isFrontFacingCamera: StateFlow<Boolean?> = cameraSession.isFrontFacingCamera

    init {
        viewModelScope.launch {
            session.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
            }
        }
        viewModelScope.launch {
            session.remoteVideoTrack.collect { vt ->
                _uiState.update { it.copy(remoteVideoTrack = vt) }
            }
        }
    }

    fun onRemoteSdpInputChange(value: String) {
        _uiState.update { it.copy(remoteSdpInput = value) }
    }

    fun onCreateOffer() = runAction {
        val offer = session.createOffer()
        _uiState.update { it.copy(localSdp = offer) }
    }

    fun onApplyRemoteSdp() = runAction {
        val remoteSdp = _uiState.value.remoteSdpInput.trim()
        require(remoteSdp.isNotEmpty()) { "相手の SDP を貼り付けてください" }
        when (_uiState.value.role) {
            ManualP2PRole.Offerer -> session.acceptAnswer(remoteSdp)
            ManualP2PRole.Answerer -> {
                val answer = session.acceptOfferAndCreateAnswer(remoteSdp)
                _uiState.update { it.copy(localSdp = answer) }
            }
        }
    }

    fun onErrorShown() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun runAction(block: suspend () -> Unit) {
        if (_uiState.value.isBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotImplementedError) {
                _uiState.update { it.copy(errorMessage = "未実装です: ${e.message}") }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: e.toString()) }
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    override fun onCleared() {
        session.close()
    }
}
