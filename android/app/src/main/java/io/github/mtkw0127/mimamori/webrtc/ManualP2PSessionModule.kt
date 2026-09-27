package io.github.mtkw0127.mimamori.webrtc

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

/**
 * [ManualP2PSession] は「P2P 接続を1回試みる」ごとの状態を持つので、
 * アプリ全体の [dagger.hilt.components.SingletonComponent] ではなく、
 * ViewModel のライフサイクルに合わせた [ViewModelComponent] にスコープする。
 * ManualP2PScreen を開くたびに新しい接続として、新しいインスタンスが作られる。
 */
@Module
@InstallIn(ViewModelComponent::class)
abstract class ManualP2PSessionModule {

    @Binds
    abstract fun bindManualP2PSession(impl: WebRtcManualP2PSession): ManualP2PSession
}
