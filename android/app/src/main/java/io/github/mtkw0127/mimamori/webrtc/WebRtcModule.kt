package io.github.mtkw0127.mimamori.webrtc

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import javax.inject.Singleton

/**
 * WebRTC のうち「アプリ全体で 1 つあれば十分なもの」を Hilt に提供させるモジュール。
 *
 * ここに含めていないもの（意図的）:
 *  - VideoCapturer / VideoSource / VideoTrack（カメラそのもの）
 *    → 単純なシングルトンにすると、視聴者がいなくてもカメラが起動しっぱなしになりかねない
 *      （docs/architecture.md §7.1）。[LocalCameraSession] で start/stop を自分で制御すること。
 */
@Module
@InstallIn(SingletonComponent::class)
object WebRtcModule {

    @Provides
    @Singleton
    fun provideEglBase(): EglBase = EglBase.create()

    @Provides
    @Singleton
    fun providePeerConnectionFactory(
        @ApplicationContext context: Context,
        eglBase: EglBase,
    ): PeerConnectionFactory {
        val initializationOptions = PeerConnectionFactory.InitializationOptions
            .builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)
        // これが無いと映像コーデックが1つも無い状態になり、offer の m=video が
        // port=0（無効）になって ICE Candidate の収集自体が始まらない
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        return PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }
}
