# Claude Code 引継ぎ文書

## このドキュメントについて

ChatベースのClaudeとの会話で本プロジェクトの初期セットアップを行いました。
ここからの実装フェーズをClaude Codeに引き継ぎます。

このドキュメントはプロジェクトルートに置いてあります。最初に読んでください。

## プロジェクト概要

**名称**: Screenshot Shrinker  
**目的**: Androidスクリーンショットをトリミング＋縮小JPG保存するモバイルアプリ  
**作者**: codria（個人開発、まずは自分用）

詳細仕様は同じディレクトリの `SPECIFICATION.md` を参照してください。

## 現在の状態

### 完了済み

- [x] GitHubリポジトリ作成: https://github.com/codria/screenshot-shrinker
- [x] ローカルクローン済み: `C:\Users\ms-mini\.git\screenshot-shrinker\`
- [x] 仕様書作成: `SPECIFICATION.md`
- [x] Android Studio インストール
- [x] Android Studio で「Empty Views Activity」テンプレートからプロジェクト作成
  - Package name: `com.codria.screenshotshrinker`
  - Language: Kotlin
  - Minimum SDK: API 26 (Android 8.0)
  - Build configuration: Kotlin DSL (build.gradle.kts)
  - UIフレームワーク: View System (XML)
- [x] 「Hello World」動作確認

### 未着手

- [ ] Phase 1: 画像選択 → 単純コピー保存（MediaStore疎通確認）
- [ ] Phase 2: リサイズ＋JPG品質指定保存
- [ ] Phase 3: トリミングUI追加
- [ ] Phase 4: 共有メニューからの起動対応
- [ ] Phase 5: 設定記憶・UI調整

## 次にやるタスク（Phase 1）

### 目的

「画像選択 → 端末ギャラリーに保存」の疎通確認。
リサイズもトリミングも一切なしで、まずパイプラインを通す。
ここで `MediaStore` API周りの動作確認を完了させ、後続フェーズで詰まらないようにする。

### 実装内容

1. `MainActivity` に「画像を選択」ボタンを配置
2. ボタン押下で `ActivityResultContracts.GetContent` を起動し、ギャラリーから画像選択
3. 選択した画像を `Bitmap` に読み込み（`inSampleSize` でダウンサンプル考慮）
4. `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` 経由でJPGとして端末に保存
5. 保存完了をToastかSnackbarで通知（保存ファイル名・サイズ表示）

### ファイル命名規則

仕様書 §9 参照:
```
shrunk_<元ファイル名>_<タイムスタンプ>.jpg
例: shrunk_Screenshot_20251104_143022.jpg
```

Phase 1 ではリサイズなしなので元のファイル名にプレフィックスとタイムスタンプを付ける形でOK。

### 注意事項

- **Android 10 (API 29) 以降のScoped Storageに準拠すること**
  - `WRITE_EXTERNAL_STORAGE` は使わない
  - `MediaStore` API + `ContentValues` でURI取得 → `OutputStream` 書き込み
- **権限は最小限**
  - 画像選択は `GetContent` 経由なら権限不要
  - 保存も MediaStore 経由なら権限不要（API 29+）
- **OOM対策**
  - 大きい画像をそのまま `decodeStream` するとOOMの可能性
  - `BitmapFactory.Options.inJustDecodeBounds = true` で先にサイズ取得 → `inSampleSize` 計算 → 本読み込み

## プロジェクト構成（予定）

```
screenshot-shrinker/
├── app/
│   ├── src/main/
│   │   ├── java/com/codria/screenshotshrinker/
│   │   │   ├── MainActivity.kt
│   │   │   ├── CropActivity.kt        ← Phase 3で追加
│   │   │   ├── SettingsActivity.kt    ← Phase 2以降で追加
│   │   │   └── util/
│   │   │       ├── ImageLoader.kt     ← Phase 1で作成
│   │   │       ├── ImageResizer.kt    ← Phase 2で追加
│   │   │       └── ImageSaver.kt      ← Phase 1で作成
│   │   ├── res/
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── README.md
├── SPECIFICATION.md
├── HANDOVER.md  ← 本ドキュメント
└── .gitignore
```

`util/` 配下にロジックを切り出して、Activityを薄く保つ方針。
ただしPhase 1は最小実装でMainActivityに集約してもOK。リファクタは後段で。

## 技術的決定事項

| 項目 | 選択 | 備考 |
|---|---|---|
| 言語 | Kotlin | |
| UIフレームワーク | View System (XML) | Composeはライブラリ互換性考慮で見送り |
| 最小SDK | API 26 | |
| トリミングライブラリ | 未定 | Phase 3で uCrop / Android-Image-Cropper / 自前 から選定 |
| ビルド設定 | Kotlin DSL (build.gradle.kts) | |

## 開発フロー希望

- **コミット粒度**: フェーズ単位ではなく機能単位で細かめに
- **ブランチ運用**: 当面 `main` 直push でOK（個人開発）
- **コミットメッセージ**: 日本語OK、Conventional Commits風だと尚良し
- **動作確認**: 実機ベース。エミュレータは画像選択の確認しづらいので主役にしない

## ユーザー（codria）の前提知識

- Git/GitHub の基本操作はOK
- Android開発は今回が初めて or 久しぶり
- Kotlin文法は読める前提
- 実装で迷いそうな箇所は適宜解説してもらえると助かる

## 仕様書の未確定事項

`SPECIFICATION.md` §12 にある未確定事項:

- トリミングライブラリの最終選定（Phase 3で判断）
- プレビュー表示の有無
- バッチ処理の優先度
- 出力先フォルダのカスタマイズ可否

これらはPhase 1完了後に都度議論。Phase 1の実装には影響しない。

## 参考リソース

- 公式: https://developer.android.com/training/data-storage/shared/media
- MediaStore: https://developer.android.com/reference/android/provider/MediaStore
- ActivityResult API: https://developer.android.com/training/basics/intents/result

---

最終更新: 2026-05-05  
記録者: Chat Claude → Claude Code への引継ぎ
