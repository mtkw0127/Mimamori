package io.github.mtkw0127.mimamori.ui.recorder

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mtkw0127.mimamori.webrtc.LocalCameraSession
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import javax.inject.Inject

/**
 * [LocalCameraSession]（アプリに1つだけのカメラ）を Recorder 画面に繋ぐだけの薄い ViewModel。
 * カメラの生成・解放のロジックそのものは [LocalCameraSession] 側にある。
 */
@HiltViewModel
class RecorderViewModel @Inject constructor(
    private val cameraSession: LocalCameraSession,
    val eglBase: EglBase,
) : ViewModel() {

    val videoTrack: StateFlow<VideoTrack?> = cameraSession.videoTrack
    val isFrontFacingCamera: StateFlow<Boolean?> = cameraSession.isFrontFacingCamera
    val currentCameraDeviceName: StateFlow<String?> = cameraSession.currentCameraDeviceName

    /** 端末が持つレンズの一覧。撮影を開始するまでは空リスト */
    val availableCameras: StateFlow<List<LocalCameraSession.CameraOption>> = cameraSession.availableCameras
    val zoomInfo: StateFlow<LocalCameraSession.ZoomInfo?> = cameraSession.zoomInfo

    suspend fun start() = cameraSession.start()

    fun stop() = cameraSession.stop()

    fun switchCamera(deviceName: String) = cameraSession.switchTo(deviceName)

    fun setZoomRatio(ratio: Float) = cameraSession.setZoomRatio(ratio)
}
