# Screenshot Shrinker 仕様書

## 1. 概要

Androidスクリーンショットを「トリミング → 縮小 → JPG保存」の一連の流れで処理するモバイルアプリ。
高解像度スクリーンショットを取り回しやすいサイズに圧縮することを目的とする。

## 2. 背景・目的

- 端末のスクリーンショットは解像度が高く、共有・保存・閲覧で容量が嵩む
- 既存アプリの組み合わせ（ギャラリー → トリミングアプリ → 圧縮アプリ）は手間がかかる
- 自分のワークフローに最適化した一気通貫アプリを自作する

## 3. ターゲットユーザー

開発者本人（自分用ツール）。Play Store公開は視野に入れるが当面は対象外。

## 4. 動作環境

| 項目 | 内容 |
|---|---|
| プラットフォーム | Android |
| 最低APIレベル | API 26 (Android 8.0) |
| 開発言語 | Kotlin |
| 開発環境 | Android Studio |
| UIフレームワーク | View System もしくは Jetpack Compose（後述） |

## 5. 機能要件

### 5.1 必須機能（MVP）

#### F-01: 画像入力
- ギャラリーから画像を選択して読み込む
- `ActivityResultContracts.GetContent` を使用

#### F-02: トリミング
- 画像上で矩形範囲を指定できる
- ドラッグでハンドルを動かして範囲調整
- アスペクト比は自由（縛りなし）

#### F-03: 縮小
- リサイズ倍率または出力辺長を指定
- プリセット（例: 1080px長辺、720px長辺、50%、25%）
- カスタム数値入力も可能

#### F-04: JPG保存
- JPG形式で出力
- 品質（quality）を指定可能（既定: 80）
- `MediaStore` API経由で端末ギャラリーに保存

#### F-05: 保存通知
- 保存完了時にトーストやスナックバーで結果通知
- 保存先パス・ファイル名・最終ファイルサイズを表示

### 5.2 推奨機能（v1.1以降）

#### F-06: 共有メニュー対応
- 他アプリの「共有」から本アプリを呼び出せる
- `AndroidManifest.xml` に `ACTION_SEND` / `image/*` のintent-filter登録

#### F-07: 設定の記憶
- 前回使用した品質・リサイズ設定を `SharedPreferences` で保持

#### F-08: バッチ処理
- 複数画像をまとめて処理
- 個別トリミングはなし（全画像同一倍率で縮小のみ）

### 5.3 将来検討機能

- WebP出力対応
- EXIF情報の保持／削除選択
- 出力ファイル名規則のカスタマイズ
- ダークモード対応

## 6. 非機能要件

| 項目 | 要件 |
|---|---|
| 起動速度 | コールドスタート2秒以内を目標 |
| 処理速度 | 4000×3000程度の画像で3秒以内に保存完了 |
| メモリ | OOM回避のため `inSampleSize` で読み込み時にダウンサンプル |
| 権限 | 最小権限。Android 10+ではScoped Storageに準拠し画像権限不要 |

## 7. 画面構成

```
[起動画面]
   ↓ 「画像を選択」ボタン
[ギャラリーピッカー（システム）]
   ↓ 画像選択
[トリミング画面]
  - 画像表示エリア
  - 矩形選択ハンドル
  - 「次へ」ボタン
   ↓
[リサイズ・品質設定画面]
  - リサイズ方式選択（プリセット / カスタム）
  - 品質スライダー（1〜100）
  - プレビュー（任意）
  - 「保存」ボタン
   ↓
[完了通知]
  - 保存先・ファイルサイズ表示
  - 「もう一度」ボタンで先頭に戻る
```

簡略化のため、トリミングと設定画面を1画面に統合する案もあり（実装時に判断）。

## 8. 技術選定

### 8.1 トリミングUI

以下から選択。MVPでは**ライブラリ利用**を推奨。

| 選択肢 | メリット | デメリット |
|---|---|---|
| ライブラリ（uCrop / Android-Image-Cropper） | 実装早い・安定 | カスタマイズ性に制限 |
| 自前実装（Canvas + MotionEvent） | 自由度高い | 実装コスト大 |

### 8.2 UIフレームワーク

| 選択肢 | 備考 |
|---|---|
| View System (XML) | 既存ライブラリとの互換性高い・情報量多い |
| Jetpack Compose | モダン・宣言的・将来性 |

学習コストとライブラリ互換性を考慮し、MVPでは **View System** で着手予定。

### 8.3 画像処理

- `Bitmap` を中心に処理
- 読み込み: `BitmapFactory.decodeStream` + `inSampleSize`
- リサイズ: `Bitmap.createScaledBitmap`
- JPG出力: `Bitmap.compress(JPEG, quality, OutputStream)`
- 保存: `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`

## 9. ファイル命名規則

出力ファイル名の既定:

```
shrunk_<元ファイル名>_<タイムスタンプ>.jpg
例: shrunk_Screenshot_20251104_143022.jpg
```

## 10. プロジェクト構成（予定）

```
screenshot-shrinker/
├── app/
│   ├── src/main/
│   │   ├── java/com/codria/screenshotshrinker/
│   │   │   ├── MainActivity.kt
│   │   │   ├── CropActivity.kt
│   │   │   ├── SettingsActivity.kt
│   │   │   └── util/
│   │   │       ├── ImageLoader.kt
│   │   │       ├── ImageResizer.kt
│   │   │       └── ImageSaver.kt
│   │   ├── res/
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── README.md
├── SPECIFICATION.md  ← 本ドキュメント
└── .gitignore
```

## 11. 開発ロードマップ

| フェーズ | 内容 | 状態 |
|---|---|---|
| Phase 0 | 環境構築・GitHubリポジトリ作成 | 進行中 |
| Phase 1 | 画像選択 → 単純コピー保存（疎通確認） | 未着手 |
| Phase 2 | リサイズ＋JPG品質指定保存 | 未着手 |
| Phase 3 | トリミングUI追加 | 未着手 |
| Phase 4 | 共有メニューからの起動対応 | 未着手 |
| Phase 5 | 設定記憶・UI調整 | 未着手 |

## 12. 未確定事項

- トリミングライブラリの最終選定（uCrop / Android-Image-Cropper / 自前）
- プレビュー表示の有無
- バッチ処理の優先度
- 出力先フォルダのカスタマイズ可否

---

最終更新: 2026-05-05  
作者: codria
