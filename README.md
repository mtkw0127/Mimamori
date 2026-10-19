# Mimamori

手元のスマートフォンをカメラとして使い、家の中の別の場所から様子を見守るための iOS / Android アプリです。
同じ Wi-Fi に繋がった 2 台の端末を WebRTC で P2P 接続し、映像を家の外に出さずに直接届けます。

## 概要

| 役割 | 説明 |
| --- | --- |
| **Recorder** | 端末のカメラで様子を撮影し、映像を配信する側 |
| **Viewer** | Recorder の映像を視聴する側 |

- **同一ネットワーク（家の Wi-Fi）内での利用に限定**します。サーバーは使わず、端末 2 台だけで完結します。
- Viewer は同じネットワーク内の Recorder を自動で見つけます（mDNS / Bonjour）。
- Recorder と Viewer は **ワンタイムパスワード (OTP)** によるペアリングで紐付けます。
  OTP は Recorder の画面にだけ表示されるため、「Recorder の持ち主 = Viewer の持ち主（またはその許可を得た人）」であることを保証できます。
- 一度ペアリングに成功した Viewer は、以降パスワードなしでいつでも接続できます。
- Recorder 側から、ペアリング済みの Viewer をいつでも破棄（接続権限の取り消し）できます。
- Viewer から Recorder のカメラのズームを遠隔で操作できます。
- Recorder は端末内で顔検出を行い、一定時間顔が見えないと Viewer に通知します。接続が切れたときも通知します。
- Recorder は長時間動かし続けることを想定しているため、**バッテリー消費と発熱の抑制**を重視します。

## 対応プラットフォーム

- iOS
- Android

Recorder / Viewer はどちらの OS でも動作し、iOS ⇄ Android の組み合わせも対象です。

## ドキュメント

| ドキュメント | 内容 |
| --- | --- |
| [docs/product.md](docs/product.md) | プロダクト仕様（ユースケース・機能要件・非機能要件） |
| [docs/architecture.md](docs/architecture.md) | システム構成、WebRTC の基礎と接続シーケンス、省電力設計 |
| [docs/security.md](docs/security.md) | ペアリング・認証・Viewer 破棄の設計と脅威モデル |
| [docs/roadmap.md](docs/roadmap.md) | 開発マイルストーンと学習ポイント（誰が実装するか） |

## リポジトリ構成

```
Mimamori/
├── ios/         # iOS アプリ (Swift / SwiftUI) ※未作成
├── android/     # Android アプリ (Kotlin / Jetpack Compose)
└── docs/        # 設計ドキュメント
```

## 開発の始め方

### Android

Android Studio で `android/` を開くか、コマンドラインでビルドする。

```sh
cd android
./gradlew :app:installDebug
```

### iOS

未作成。[docs/roadmap.md](docs/roadmap.md) の M0 を参照。

## ステータス

| プラットフォーム | 状況 |
| --- | --- |
| Android | M1〜M5（カメラ表示・P2P 接続・LAN 内の自動発見・OTP ペアリング・再接続認証と破棄）と顔検出による見守り通知を実装済み。M6（省電力・発熱対策）は未着手 |
| iOS | 未着手（M0 から） |

詳細と今後の進め方は [docs/roadmap.md](docs/roadmap.md) を参照してください。
