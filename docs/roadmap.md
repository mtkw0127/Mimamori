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

---

## M0. プロジェクトの土台

| タスク | 担当 | 学習ポイント |
| --- | --- | --- |
| Android プロジェクト作成（Compose） | 🤖 | — |
| iOS プロジェクト作成（SwiftUI） | 🤝 | Xcode のプロジェクト構成、Signing、実機への転送、SPM での依存追加 |
| iOS: 起動画面（Recorder / Viewer の役割選択） | 🧑‍💻 | SwiftUI 最初の一歩。`App` / `Scene` / `View` の関係、Xcode Preview（`#Preview`） |
| WebRTC ライブラリの導入（両 OS） | 🤝 | ライブラリ選定の観点（メンテ状況・バージョン） |
| カメラ・ネットワークの権限設定 | 🧑‍💻 (iOS) / 🤖 (Android) | iOS の `Info.plist`（`NSCameraUsageDescription`）と権限リクエストの流れ |

## M1. カメラ映像をローカルに表示

WebRTC の VideoTrack として取得したカメラ映像を、同じ端末の画面に表示する。

| タスク | 担当 | 学習ポイント |
| --- | --- | --- |
| iOS: `RTCCameraVideoCapturer` で撮影し画面に表示 | 🧑‍💻 | `PeerConnectionFactory` → `VideoSource` → `VideoTrack` → `Renderer` の関係。SwiftUI から UIKit の View を使う方法（`UIViewRepresentable`） |
| Android: `Camera2Capturer` で撮影し画面に表示 | 🧑‍💻 | 同上の Android 版。`SurfaceViewRenderer` と `EglBase` |
| 解像度・fps の指定 | 🧑‍💻 | 省電力設計の第一歩 |

## M2. シグナリングなしで P2P 接続（手動）

**WebRTC を理解するための最重要ステップ。**
SDP と ICE Candidate を、シグナリングサーバーの代わりに手動（コピー＆ペーストや QR など）で交換して、2 台を繋ぐ。

| タスク | 担当 | 学習ポイント |
| --- | --- | --- |
| PeerConnection の作成と映像トラック追加 | 🧑‍💻 | `RTCConfiguration`（ICE サーバー設定） |
| offer / answer の作成と `setLocalDescription` / `setRemoteDescription` | 🧑‍💻 | offer/answer の順序と状態遷移（signalingState） |
| ICE Candidate の収集と追加 | 🧑‍💻 | Trickle ICE、`iceConnectionState` の遷移 |
| 受信した映像トラックの表示 | 🧑‍💻 | `onTrack` / `didAdd` のコールバック |
| iOS: デバッグ用のコピペ UI | 🧑‍💻 | SwiftUI の基本（`View`・`@State`・`TextField`・`Button`）、`UIPasteboard` |
| Android: デバッグ用のコピペ UI | 🤖 | — |

まずは **同一 OS 同士（Android ⇄ Android）→ iOS ⇄ iOS → iOS ⇄ Android** の順で繋ぐと切り分けしやすい。

## M3. シグナリングサーバー

| タスク | 担当 | 学習ポイント |
| --- | --- | --- |
| シグナリング方式の決定 | 🤝 | [architecture.md §3.1](architecture.md#31-シグナリングサーバー) |
| メッセージ形式（JSON スキーマ）の設計 | 🧑‍💻 | 両 OS とサーバーで共有する「プロトコル」を自分で設計する経験 |
| サーバー本体の実装 | 🤖（ローカル用の簡易版） | — |
| アプリ側のシグナリングクライアント | 🤝 | WebSocket 接続の維持・再接続 |
| M2 の手動交換をシグナリング経由に置き換え | 🧑‍💻 | — |

## M4. ペアリング（OTP）

| タスク | 担当 | 学習ポイント |
| --- | --- | --- |
| OTP 生成・表示・有効期限 | 🧑‍💻 | 暗号学的に安全な乱数 |
| 端末鍵ペアの生成と保管 | 🧑‍💻 | iOS: CryptoKit + Secure Enclave / Android: Keystore |
| HMAC による証明の作成・検証 | 🧑‍💻 | [security.md §3.2](security.md#32-ペアリングの流れ) |
| iOS ⇄ Android での鍵・署名形式の相互運用 | 🧑‍💻 | DER / raw 形式の違い（ハマりどころ） |
| iOS: ペアリング画面の UI（OTP 表示・入力） | 🧑‍💻 | 画面遷移（`NavigationStack`）、`@Observable` による ViewModel との連携、キーボード・フォーカス制御（`@FocusState`）、有効期限のカウントダウン表示 |
| Android: ペアリング画面の UI | 🤖 | — |
| サーバー側のレート制限 | 🤖 | — |

## M5. 再接続認証と破棄

| タスク | 担当 | 学習ポイント |
| --- | --- | --- |
| チャレンジ・レスポンス認証 | 🧑‍💻 | nonce の役割、署名と検証 |
| ペアリング済み一覧の永続化 | 🤝 | iOS: SwiftData / Keychain、Android: Room / DataStore |
| 破棄と即時切断 | 🧑‍💻 | PeerConnection の正しいクローズとリソース解放 |
| iOS: Recorder / Viewer 一覧・設定画面 UI | 🧑‍💻 | `List`・スワイプ削除（`onDelete`）、確認ダイアログ（`confirmationDialog`）、状態に応じた表示切替（オンライン / オフライン / 破棄済み） |
| Android: 一覧・設定画面 UI | 🤖 | — |

## M6. 省電力・発熱対策

| タスク | 担当 | 学習ポイント |
| --- | --- | --- |
| 視聴者ゼロ時のカメラ停止 | 🧑‍💻 | カメラのライフサイクル管理 |
| ビットレート上限・`degradationPreference` の設定 | 🧑‍💻 | `RTCRtpSender.parameters` の扱い |
| 温度状態の監視と段階的な画質調整 | 🧑‍💻 | iOS `thermalState` の通知 / Android `OnThermalStatusChangedListener` |
| iOS: 省電力表示モード（黒画面・スリープ抑止） | 🧑‍💻 | `isIdleTimerDisabled`、iOS のバックグラウンド制約 |
| Android: Foreground Service 化 | 🤝 | `foregroundServiceType="camera"` と OS バージョンごとの制約 |
| 消費電力の計測 | 🤝 | Xcode Instruments (Energy Log) / Android Battery Historian |

## M7. 実環境での接続性

| タスク | 担当 | 学習ポイント |
| --- | --- | --- |
| モバイル回線 ⇄ Wi-Fi での接続テスト | 🧑‍💻 | NAT の種類と STUN の限界 |
| TURN サーバーの用意 | 🤝 | coturn の設定 or マネージドサービス |
| 接続状態の監視と自動再接続 | 🧑‍💻 | ICE restart |

---

## Claude との進め方メモ

- 🧑‍💻 のタスクでは、Claude はいきなり完成コードを書かず、**方針・参考 API・ヒントを示す → 自分で書く → Claude がレビュー** の順で進める。
- 詰まったら「ヒントを 1 段階だけ」「答えを見せて」など、欲しい粒度を伝える。
- 各マイルストーンの完了時に、学んだことをこのファイルか別のメモに残すと振り返りやすい。
