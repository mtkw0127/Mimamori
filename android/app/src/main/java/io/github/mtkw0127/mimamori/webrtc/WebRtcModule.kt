package io.github.mtkw0127.mimamori.webrtc

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
    ): PeerConnectionFactory {
        val initializationOptions = PeerConnectionFactory.InitializationOptions
            .builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)
        return PeerConnectionFactory.builder().createPeerConnectionFactory()
    }
}
