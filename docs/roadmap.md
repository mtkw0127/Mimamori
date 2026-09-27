# ロードマップと学習ポイント

このプロジェクトは **開発を学ぶこと** も目的の一つ。
重要な箇所・理解しておくべき箇所は自分で実装し、定型的な部分は Claude に任せる方針で進める。

## 担当の凡例

| マーク | 意味 |
| --- | --- |
| 🧑‍💻 | **自分で実装する**。Claude はヒント・レビュー・解説に留める |
| 🤝 | 一緒に進める。Claude が骨組みを用意し、核心部分を自分で埋める |
| 🤖 | Claude に任せてよい（ボイラープレート・設定・定型コード） |

前提スキル: iOS は未経験、Android はある程度経験あり、WebRTC は未経験。
そのため **iOS 側（UI を含む）と WebRTC の核心部分は 🧑‍💻 を多め** にしている。
Android の UI は Claude に任せ、iOS の UI（SwiftUI）は自分で実装する。

## 進める順番

**Android を先行して M1〜M6 を一通り完成させ、その後 iOS で同じ道をたどる。**

1. Android で M1〜M6（Android ⇄ Android で全機能が動く状態）
2. iOS で M0〜M6（Android で理解した WebRTC の流れを、Swift / iOS の API で書き直す）
3. M7（iOS ⇄ Android の相互接続と実環境テスト）

WebRTC の概念を慣れている Android で先に理解しておくことで、iOS では言語と API の学習に集中できる。

---

## M0. プロジェクトの土台

| タスク | 担当 | 学習ポイント |
| --- | --- | --- |
| ✅ Android プロジェクト作成（Compose） | 🤖 | — |
| iOS プロジェクト作成（SwiftUI） | 🤝 | Xcode のプロジェクト構成、Signing、実機への転送、SPM での依存追加 |
| iOS: 起動画面（Recorder / Viewer の役割選択） | 🧑‍💻 | SwiftUI 最初の一歩。`App` / `Scene` / `View` の関係、Xcode Preview（`#Preview`） |
| WebRTC ライブラリの導入（両 OS） | 🤝 | ライブラリ選定の観点（メンテ状況・バージョン）。Android は `io.getstream:stream-webrtc-android` を導入済み |
| カメラ・ローカルネットワークの権限設定 | 🧑‍💻 (iOS) / 🤖 (Android ✅) | iOS の `Info.plist`（`NSCameraUsageDescription`・`NSLocalNetworkUsageDescription`・`NSBonjourServices`）と権限リクエストの流れ |

## M1. カメラ映像をローカルに表示

WebRTC の VideoTrack として取得したカメラ映像を、同じ端末の画面に表示する。

| タスク | 担当 | 学習ポイント |
| --- | --- | --- |
| iOS: `RTCCameraVideoCapturer` で撮影し画面に表示 | 🧑‍💻 | `PeerConnectionFactory` → `VideoSource` → `VideoTrack` → `Renderer` の関係。SwiftUI から UIKit の View を使う方法（`UIViewRepresentable`） |
| Android: `Camera2Capturer` で撮影し画面に表示 | 🧑‍💻 | 同上の Android 版。`SurfaceViewRenderer` と `EglBase` |
| 解像度・fps の指定 | 🧑‍💻 | 省電力設計の第一歩 |

## M2. シグナリングなしで P2P 接続（手動）

**WebRTC を理解するための最重要ステップ。**
SDP と ICE Candidate を、手動（コピー＆ペーストなど）で交換して、同じ Wi-Fi 上の 2 台を繋ぐ。

| タスク | 担当 | 学習ポイント |
| --- | --- | --- |
| PeerConnection の作成と映像トラック追加 | 🧑‍💻 | `RTCConfiguration`（同一 LAN なので ICE サーバーは空でよい理由） |
| offer / answer の作成と `setLocalDescription` / `setRemoteDescription` | 🧑‍💻 | offer/answer の順序と状態遷移（signalingState） |
| ICE Candidate の収集と追加 | 🧑‍💻 | Trickle ICE、`iceConnectionState` の遷移 |
| 受信した映像トラックの表示 | 🧑‍💻 | `onTrack` / `didAdd` のコールバック |
| iOS: デバッグ用のコピペ UI | 🧑‍💻 | SwiftUI の基本（`View`・`@State`・`TextField`・`Button`）、`UIPasteboard` |
| ✅ Android: デバッグ用のコピペ UI | 🤖 | `ManualP2PSession` インターフェースと、TODO 入りの `WebRtcManualP2PSession` を用意済み |

まずは **同一 OS 同士（Android ⇄ Android）→ iOS ⇄ iOS → iOS ⇄ Android** の順で繋ぐと切り分けしやすい。

## M3. LAN 内での発見とシグナリング

M2 の手作業（コピー＆ペースト）を、mDNS による自動発見と TCP による直接通信に置き換える。
設計は [architecture.md §3.1〜3.2](architecture.md#31-サービス発見mdns--dns-sd) を参照。

| タスク | 担当 | 学習ポイント |
| --- | --- | --- |
| メッセージ形式（JSON スキーマ）の設計 | 🧑‍💻 | iOS と Android で共有する「プロトコル」を自分で設計する経験 |
| iOS: `NWListener` での告知・待ち受け / `NWBrowser` での検索 | 🧑‍💻 | Network フレームワーク、Bonjour の TXT レコード、ローカルネットワーク権限 |
| iOS: `NWConnection` での改行区切り JSON の送受信 | 🧑‍💻 | TCP はメッセージの区切りを保証しない（受信バッファの扱い）、`Codable` |
| Android: `NsdManager` での告知・検索 | 🤝 | コールバック API を `Flow` にする部分は Claude、使う側は自分で |
| Android: TCP の待ち受け・接続と改行区切り JSON の送受信 | 🤝 | `ServerSocket` / `Socket` と Coroutines（`Dispatchers.IO`） |
| M2 の手動交換をこの仕組みに置き換え | 🧑‍💻 | — |
| iOS: 見つかった Recorder の一覧 UI | 🧑‍💻 | `List`、発見・消失に応じたリアルタイム更新 |
| Android: 見つかった Recorder の一覧 UI | 🤖 | — |

## M4. ペアリング（OTP）

| タスク | 担当 | 学習ポイント |
| --- | --- | --- |
| OTP 生成・表示・有効期限、TXT の `pairing` フラグ切り替え | 🧑‍💻 | 暗号学的に安全な乱数 |
| 端末鍵ペアの生成と保管 | 🧑‍💻 | iOS: CryptoKit + Secure Enclave / Android: Keystore |
| HMAC による証明の作成・検証 | 🧑‍💻 | [security.md §3.2](security.md#32-ペアリングの流れ) |
| iOS ⇄ Android での鍵・署名形式の相互運用 | 🧑‍💻 | DER / raw 形式の違い（ハマりどころ） |
| iOS: ペアリング画面の UI（OTP 表示・入力） | 🧑‍💻 | 画面遷移（`NavigationStack`）、`@Observable` による ViewModel との連携、キーボード・フォーカス制御（`@FocusState`）、有効期限のカウントダウン表示 |
| Android: ペアリング画面の UI | 🤖 | — |
| Recorder 側の失敗回数制限 | 🧑‍💻 | 総当たり対策（[security.md §3.1](security.md#31-otp)） |

## M5. 再接続認証と破棄

| タスク | 担当 | 学習ポイント |
| --- | --- | --- |
| チャレンジ・レスポンス認証 | 🧑‍💻 | nonce の役割、署名と検証 |
| SDP への署名と検証 | 🧑‍💻 | DTLS フィンガープリントと認証の結び付け（[security.md §4.2](security.md#42-sdp-への署名推奨)） |
| ペアリング済み一覧の永続化 | 🤝 | iOS: SwiftData / Keychain、Android: Room / DataStore |
| 破棄の通知と即時切断 | 🧑‍💻 | PeerConnection と TCP の正しいクローズとリソース解放 |
| iOS: Recorder / Viewer 一覧・設定画面 UI | 🧑‍💻 | `List`・スワイプ削除（`onDelete`）、確認ダイアログ（`confirmationDialog`）、状態に応じた表示切替（オンライン / オフライン / 破棄済み） |
| Android: 一覧・設定画面 UI | 🤖 | — |

## M6. 省電力・発熱対策

| タスク | 担当 | 学習ポイント |
| --- | --- | --- |
| 視聴者ゼロ時のカメラ停止 | 🧑‍💻 | カメラのライフサイクル管理 |
| ビットレート上限・`degradationPreference` の設定 | 🧑‍💻 | `RTCRtpSender.parameters` の扱い |
| 温度状態の監視と段階的な画質調整 | 🧑‍💻 | iOS `thermalState` の通知 / Android `OnThermalStatusChangedListener` |
| iOS: 省電力表示モード（黒画面・スリープ抑止） | 🧑‍💻 | `isIdleTimerDisabled`、iOS のバックグラウンド制約 |
| Android: Foreground Service 化（カメラ・待ち受け・mDNS 告知） | 🤝 | `foregroundServiceType="camera"` と OS バージョンごとの制約 |
| 消費電力の計測 | 🤝 | Xcode Instruments (Energy Log) / Android Battery Historian |

## M7. 実環境での安定性

| タスク | 担当 | 学習ポイント |
| --- | --- | --- |
| iOS ⇄ Android の相互接続テスト | 🧑‍💻 | 鍵・署名・SDP の形式の違いによる不具合の切り分け |
| 自宅環境（メッシュ Wi-Fi・中継器を含む）での実機テスト | 🧑‍💻 | mDNS が届く範囲 |
| Wi-Fi 切断・IP 変更からの自動再接続 | 🧑‍💻 | 接続状態の監視、mDNS での再検索 |
| 繋がらないときの原因表示（権限なし・AP アイソレーションなど） | 🤝 | エラーの分類とユーザーへの伝え方 |

---

## Claude との進め方メモ

- 🧑‍💻 のタスクでは、Claude はいきなり完成コードを書かず、**方針・参考 API・ヒントを示す → 自分で書く → Claude がレビュー** の順で進める。
- 詰まったら「ヒントを 1 段階だけ」「答えを見せて」など、欲しい粒度を伝える。
- 各マイルストーンの完了時に、学んだことをこのファイルか別のメモに残すと振り返りやすい。
