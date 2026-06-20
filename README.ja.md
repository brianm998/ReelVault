> [English original](README.md)

# ReelVault

Lightroom にインスパイアされたクロスプラットフォーム対応のビデオカタログ管理アプリケーション — 大規模なビデオライブラリを高速かつネイティブに閲覧できます。

**ReelVault はビデオの整理・発見・管理を目的としています。ビデオエディターではありません。**

## 概要

ReelVault でできること：

- **閲覧** — サムネイルサイズを調整できる、レスポンシブな仮想化グリッドで数千のビデオを表示します。
- **スクラブ** Lightroom スタイルのプレビュー：サムネイル上にカーソルを合わせて左右にスライドさせると、タイムライン上のフレームをプレビューできます。
- **グループ化** — 関連するバリアント（例：同じ素材の 4K と 1080p エクスポート）を Lightroom スタイルのスタックにまとめます。グリッド表示と開く操作の優先項目として 1 つをマーク設定できます。
- **コンテンツの発見** — 全文検索、タグフィルタリング、フィールドごとのフィルタードロップダウン（カメラ、レンズ、コーデック、撮影年、キーワード）を活用します。
- **メタデータの詳細確認** — コーデック、解像度、FPS、ビットレート、色空間、HDR、EXIF、GPS、カメラ/レンズモデルを確認できます。
- **整理** — フリーフォームのメモ、キーワード、複数選択操作を使って管理します。
- **クリップの受け渡し** — ドラッグ＆ドロップで外部エディターに渡します。グリッドから 1 枚または複数枚のカードを DaVinci Resolve、Final Cut Pro、Premiere Pro など、ファイルドロップに対応する任意のアプリに直接ドラッグできます。
- **マルチカタログ** ワークフロー — ファイルメニューから SQLite カタログを開く/閉じる/切り替えられます。最近使ったカタログの一覧とカタログごとのウィンドウタイトルに対応しています。

## アーキテクチャ

```
Desktop / macOS clients          iOS client (iPhone / iPad)
   ↓ gRPC over loopback             ↓ gRPC + HTTPS media over the LAN
   │                                │ (mDNS discovery · pinned TLS · paired)
   └───────────────┬────────────────┘
                   ↓
        Rust Backend Daemon (reelvault-core)
                   ↓
        SQLite Catalog + FFmpeg/FFprobe + Filesystem
```

- **Rust コア** (`core/`) — Tonic ベースの gRPC デーモンです。SQLite カタログ、FFprobe によるメタデータ抽出、サムネイル・スクラブフレーム生成、スキャン/インデックス、検索、フィルター集計を管理します。`OpenCatalog` / `CloseCatalog` / `GetCurrentCatalog` RPC を通じてランタイム中のアクティブカタログのホットスワップをサポートするため、単一のデーモンプロセスがその生存期間中に複数のライブラリを提供できます。

- **Kotlin Compose デスクトップクライアント** (`kotlin-desktop/`) — Compose Multiplatform UI です。`127.0.0.1:50051` で実行中のデーモンを自動検出します。実行中のデーモンがない場合は、バンドルされたデーモンを自動起動します（50051 が使用中の場合は OS が割り当てたポートにフォールバック）。

- **SwiftUI macOS クライアント** (`macos/`) — 同等の機能を持つネイティブ macOS アプリです。同じ自動起動フロー、本物の macOS ファイルメニュー（Commands グループ）、開いているカタログを追跡するリアクティブなウィンドウタイトルを備えています。

- **SwiftUI iOS クライアント** (`ios/`) — **リモート専用**の iPhone / iPad アプリです。ローカルファイルアクセスはなく、デーモンも内蔵していません。Wi‑Fi（mDNS）を通じてデーモンを発見し、ワンタイムペアリング後に指紋固定された TLS チャンネルで接続し、gRPC でブラウズ、デーモンのメディアサーバーからビデオを**ストリーミング**（ダウンスケール HLS）します。エディターへのドラッグアウトの代わりに iOS の共有シートを使用し、写真/ファイルからのアップロードも追加されています。詳しくは [`ios/README.md`](ios/README.md) を参照してください。

- **ReelVaultKit** (`kit/`) — **両方**の Apple クライアントで共用されるローカル SwiftPM パッケージです。モデル、ビューモデル、gRPC クライアント、Discovery、固定 TLS、メディアキャッシュ/ストリーミング層が含まれています。

- **SQLite カタログ** — 全文検索用の FTS5 を備えた WAL モードデータベースです。スキーマは [`core/schema.sql`](core/schema.sql) にあります。

> **ProRes RAW のサムネイルは macOS のみ完全な品質で生成されます。** ffmpeg は ProRes RAW（Atomos S-Log3 / S-Gamut）を現像できないため、**macOS** ではデーモンが QuickLook / AVFoundation を通じてデコードします — 正確な色と真のフレーム単位のスクラブが可能です。**Linux / Windows** にはそのようなデコーダーがないため、デーモンは ffmpeg にフォールバックします：フラットで暗いフレームと単一の繰り返しスクラブフレームになります。Rust コアは 3 つのプラットフォームすべてで同一にビルドされ、AVFoundation がリンクされることはありません。詳細：[`core/README.md`](core/README.md)（ビルドおよびデコードパス）と [`macos/README.md`](macos/README.md)（macOS クライアント）。その他すべてのコーデックは ffmpeg によってどこでも同じ方法で処理されます。

## プロジェクト状況

**MVP は macOS と Compose Desktop で動作しています。** 両クライアントは同じ機能セットを提供しており、macOS クライアントにはネイティブのメニューバーコマンドと NSWorkspace を使ったエディター起動が追加されています。**リモート専用の iOS クライアント**（iPhone / iPad）は LAN 経由でデーモンに接続してビデオをストリーミングします — ブラウズ、検査、スタック、共有、アップロードが可能です。詳しくは [`ios/README.md`](ios/README.md) を参照してください。

### ✅ 完了

**コア**
- [x] 完全な RPC サーフェスを持つ gRPC デーモン（動画、検索、スキャン、タグ、コレクション、スタック、フィルター、ステータス、設定、カタログライフサイクル）。
- [x] WAL モード + FTS5 の SQLite カタログ、`OpenCatalog` / `CloseCatalog` によるランタイムカタログのホットスワップ。
- [x] FFprobe によるメタデータ抽出（コーデック、解像度、FPS、ビットレート、HDR、EXIF、GPS、カメラ/レンズ）。
- [x] サムネイルと Lightroom スタイルのスクラブフレーム生成（動画ごとに 10 フレーム）。動画ごとのロックにより処理の重複を排除。
- [x] 並行 ffmpeg スロットル（デフォルトはホスト CPU 数）。大規模ライブラリのスキャンが SAN バックストレージを圧迫しないよう制御します。
- [x] オプションの再帰と変種の自動グループ化を備えたライブラリスキャン。
- [x] `--db-path`、`--no-catalog`、`--port`（OS 割り当てポートへのフォールバック付き）の CLI フラグと、クライアントランチャーが解析できる `REELVAULT_LISTENING_ON=…` 標準出力行。

**両クライアント**
- [x] 適応型列数とサムネイルサイズスライダーを持つ仮想化グリッドビュー。
- [x] ホバースクラブプレビュー、ホバー再生オーバーレイ、Shift 範囲選択と ⌘/Ctrl トグルによる複数選択。
- [x] スタック（グループ）UI：メンバー数付きのスタックバッジ、クリックで展開、星印で優先設定、メンバーごとの開くボタン。
- [x] サイドパネル：ライブラリの場所（左）、詳細 + メタデータ + メモ + キーワード（右）。Tab で両方を切り替え、個別のシェブロンで各パネルを折りたたみ。
- [x] トップバーのフィルタードロップダウン（カメラ / レンズ / キーワード / コーデック / 年）— データのあるフィールドのみ表示、検索と AND 結合。
- [x] 主要フィールド（ファイル名、日付、長さ、サイズ、解像度、fps、コーデック、ビットレート、カメラ、レンズ、キーワード）を網羅したソートメニュー。再クリックで方向を逆転。
- [x] タグ/キーワード管理：その場で作成、複数選択に適用、`>` シェブロンをクリックしてグリッドをフィルタリング。
- [x] すべてのカードの右クリックコンテキストメニュー：デフォルトプレーヤーで開く、Finder/エクスプローラーで表示 — 複数選択全体に対して動作。
- [x] ドラッグ＆ドロップによる受け渡し：選択したカードをファイルドロップを受け付ける任意のアプリ（DaVinci Resolve、Final Cut Pro、Premiere Pro など）にドラッグ。
- [x] マルチカタログフロー：ファイルメニューに開く/閉じる/最近使ったものを開く、初回起動時の「カタログを開く」シート、永続的な最近使用リスト、開いているカタログ名を表示するウィンドウタイトル。
- [x] 起動時にバックエンドが実行されていない場合、バンドルされたデーモンを自動起動（ポート使用中の場合はフォールバック）。
- [x] すべてのインタラクティブ要素とメタデータフィールドにホバーテキストヘルプ（Kotlin では `TooltipArea`、SwiftUI では `.help(_:)` を使用）。
- [x] デフォルトでダークモード、ライト/ダークテーマの切り替え機能付き。

### 🚧 計画中

- [ ] リアルタイムファイル監視（基盤となるフォルダが変更されたときに再インデックス）。
- [ ] スマートコレクション（ライブフィルタールール付きの保存済み検索）。
- [ ] 8K 以上の映像用のプロキシビデオ生成。
- [ ] GitHub ベースの自動更新。
- [ ] プラットフォームごとに署名済みインストーラーを生成する CI/CD リリースパイプライン。
- [ ] クライアントアプリバンドル内へのデーモンバイナリのバンドル（現在ランチャーは `REELVAULT_CORE_BIN` または cargo 開発ツリーを通じて検索）。

## はじめに

### 前提条件

- **Rust**（1.75+ 推奨）— コアデーモンのビルドに必要です。
- **FFmpeg / FFprobe** — `PATH` に含まれている必要があります。メタデータ抽出とサムネイル/スクラブフレーム生成に使用します。
- **JDK 17+** + Gradle（wrapper 同梱）— Kotlin デスクトップクライアント用。
- **Swift 5.9+ / Xcode 15+** — macOS クライアント用。
- **Xcode 16+（iOS 18 SDK）+ [XcodeGen](https://github.com/yonaskolb/XcodeGen)**
  (`brew install xcodegen`) — iOS クライアント用。

プラットフォーム固有のインストール手順については [`SETUP.md`](SETUP.md) を参照してください。

### コアデーモンのビルド

```bash
cd core
cargo build --release
```

バイナリは `core/target/release/reelvault-core` に出力されます。直接実行することも、クライアントの初回起動時に自動起動させることもできます：

```bash
# デフォルト — デフォルトポートでプラットフォームデフォルトのカタログを使用。
./target/release/reelvault-core

# カタログなしで起動（クライアントがこのモードを使用）。バインドされたポートを出力。
./target/release/reelvault-core --no-catalog --port 0

# 起動時に特定のカタログを開く。
./target/release/reelvault-core --db-path /path/to/library.db
```

デーモンは安定した `REELVAULT_LISTENING_ON=127.0.0.1:N` の行を標準出力に出力します。クライアントはこの行を解析して割り当てられたポートを検出します。

### Kotlin Compose デスクトップクライアントの実行

```bash
cd kotlin-desktop
./gradlew run
```

初回起動時にクライアントは `127.0.0.1:50051` をプローブし、何もリスニングしていなければバンドルされたデーモンを起動します。ビルド場所の検索順序：

1. `$REELVAULT_CORE_BIN`（デーモン実行ファイルへの絶対パス）
2. アプリの JAR ファイルと同じディレクトリにあるバイナリ
3. 開発ツリー内の `core/target/release/reelvault-core` または `core/target/debug/reelvault-core`
4. `PATH` 上の `reelvault-core`

開発時は `core/` 内で `cargo build` するだけで、クライアントがデバッグバイナリを自動的に検出します。

### macOS SwiftUI クライアントの実行

```bash
cd macos
swift run
```

同じ自動起動フロー、同じバイナリ検索順序（署名済みアプリバンドルで使用されるバンドル内の `Resources/reelvault-core` パスが追加されています）。カタログを開くには `⌘O`、閉じるには `⇧⌘W` を使用します。最近使用したリストは `File → Open Recent` にあります。

### iOS SwiftUI クライアントの実行

iOS クライアントは**リモート専用**です — 自身でデーモンを起動するのではなく、LAN 上のデーモンに接続します。同じ Wi‑Fi 上のマシンでリモートモードでデーモンを起動します：

```bash
reelvault-core --remote --db-path /path/to/library.db --import-dir /path/to/imports
```

次に Xcode プロジェクトを生成（`.xcodeproj` はコミットされていません）してビルドします：

```bash
cd ios
make project          # requires XcodeGen: brew install xcodegen
make build            # iOS Simulator; or open ReelVault.xcodeproj to run on a device
```

初回起動時にアプリは mDNS 経由でデーモンを発見します。デスクトップクライアントの **File → Pair a New Device** またはデーモンログから生成した 6 桁のペアリングコードでデバイスを一度承認すると、ブラウズとストリーミングが可能になります。**iOS 18+** と **Xcode 16+** が必要です。ストリーミングとペアリングモデルを含む完全な詳細は [`ios/README.md`](ios/README.md) を参照してください。

## ドキュメント

- [`CLAUDE.md`](CLAUDE.md) — プロジェクトのビジョン、アーキテクチャの詳細、データベーススキーマ、設計原則。
- [`SETUP.md`](SETUP.md) — 開発環境のセットアップ。
- [`CORE_REFERENCE.md`](CORE_REFERENCE.md) — Rust コアのモジュールと RPC サーフェスの詳細リファレンス。
- [`CLIENT_COMPARISON.md`](CLIENT_COMPARISON.md) — Kotlin と SwiftUI クライアントの並列比較。
- [`ios/README.md`](ios/README.md) — リモート専用 iOS クライアント（iPhone / iPad）：Discovery、ペアリング、固定 TLS、HLS ストリーミング。
- [`macos/README.md`](macos/README.md) — ネイティブ macOS クライアント。

## コントリビューション

開発ガイドラインについては [`CLAUDE.md`](CLAUDE.md) を参照してください。プルリクエストを歓迎します — 2 つのクライアントの機能パリティを保ち、新しいソースファイルには SPDX ヘッダーを追加してください（下記ライセンス参照）。

## ライセンス

ReelVault はフリーソフトウェアで、**GNU 一般公衆利用許諾書バージョン 3（またはお好みで任意のより新しいバージョン）**の下でライセンスされています。完全なライセンステキストは [`LICENSE`](LICENSE) にあります。簡単な著作権表示は [`COPYRIGHT`](COPYRIGHT) にあります。

すべてのソースファイルには SPDX 識別子が記載されており、ライセーススキャンツール（REUSE、FOSSology、GitHub の licensee 検出器など）がライセンスをプログラム的に識別できます：

```
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors
```

ReelVault の変更版を配布する場合、または Rust コアをライブラリとしてリンクするプログラムを配布する場合、GPL は同じ条件でソースコードを公開することを求めています。完全な義務の一覧については LICENSE ファイルを参照してください。
