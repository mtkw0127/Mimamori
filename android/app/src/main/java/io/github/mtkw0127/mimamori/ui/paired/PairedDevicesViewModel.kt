package io.github.mtkw0127.mimamori.ui.paired

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mtkw0127.mimamori.pairing.PairedRecorder
import io.github.mtkw0127.mimamori.pairing.PairedRecorderStore
import io.github.mtkw0127.mimamori.pairing.PairedViewer
import io.github.mtkw0127.mimamori.pairing.PairedViewerStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 「ペアリング済みの端末」画面の ViewModel。
 * この端末が Recorder として許可している Viewer と、Viewer として登録している Recorder の両方を扱う。
 */
@HiltViewModel
class PairedDevicesViewModel @Inject constructor(
    private val viewerStore: PairedViewerStore,
    private val recorderStore: PairedRecorderStore,
) : ViewModel() {

    private val _viewers = MutableStateFlow<List<PairedViewer>>(emptyList())
    val viewers: StateFlow<List<PairedViewer>> = _viewers.asStateFlow()

    private val _recorders = MutableStateFlow<List<PairedRecorder>>(emptyList())
    val recorders: StateFlow<List<PairedRecorder>> = _recorders.asStateFlow()

    init {
        reload()
    }

    /**
     * Recorder 側: Viewer を破棄する。
     *
     * 今は登録情報（公開鍵）を消すだけ。消した後の新規接続は
     * `RecorderViewModel.handleConnectRequest` が「未登録」として拒否する。
     * 接続中の Viewer への破棄通知と即時切断は、Recorder 画面の「Viewer を管理」
     * （`RecorderViewModel.revokeViewer`）で行う。この画面は Recorder の撮影中には開けない。
     */
    fun removeViewer(viewerId: String) {
        viewModelScope.launch {
            viewerStore.remove(viewerId)
            reload()
        }
    }

    /** Viewer 側: Recorder を忘れる（ローカルの登録情報を消す）。再接続するには OTP で再ペアリングが必要 */
    fun removeRecorder(recorderId: String) {
        viewModelScope.launch {
            recorderStore.remove(recorderId)
            reload()
        }
    }

    private fun reload() {
        viewModelScope.launch {
            _viewers.value = viewerStore.all()
            _recorders.value = recorderStore.all()
        }
    }
}
