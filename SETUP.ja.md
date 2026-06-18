> [English original](SETUP.md)

# ReelVault 開発環境セットアップ

## 前提条件

### システム要件

- **macOS 11+**、**Linux（Ubuntu 20.04+）**、または **Windows 10+**
- **Rust 1.70+**（[rustup でインストール](https://rustup.rs/)）
- **FFmpeg & FFprobe**（ビデオ解析とサムネイル生成に使用）

### FFmpeg のインストール

**macOS：**
```bash
brew install ffmpeg
```

**Linux（Ubuntu/Debian）：**
```bash
sudo apt-get install ffmpeg
```

**Windows：**
https://ffmpeg.org/download.html からダウンロードするか、以下を使用：
```bash
choco install ffmpeg
```

### Rust のインストール

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env
```

確認：
```bash
rustc --version
cargo --version
```

## コアのビルド

```bash
cd core
cargo build --release
```

コンパイルされたバイナリは `core/target/release/reelvault-core` に出力されます。

## コアデーモンの実行

```bash
./core/target/release/reelvault-core
```

デーモンは以下を行います：
- `~/.reelvault/catalog.db` にデータベースを作成
- サムネイルキャッシュディレクトリを作成
- gRPC 接続のために `127.0.0.1:50051` でリスニング

## テストの実行

```bash
cd core
cargo test
```

## 開発コマンド

**コンパイルせずにコードを確認：**
```bash
cargo check
```

**コードのフォーマット：**
```bash
cargo fmt
```

**コードの静的解析：**
```bash
cargo clippy
```

**デバッグシンボル付きのビルド：**
```bash
cargo build
```

## フロントエンドの接続

コアデーモンが起動したら、フロントエンドは `http://127.0.0.1:50051` の gRPC を通じて接続できます。

Proto 定義は `core/proto/reelvault.proto` にあり、各フロントエンド言語向けのクライアントコード生成に使用してください。

## トラブルシューティング

**"ffmpeg not found"（FFmpeg が見つかりません）**
- FFmpeg がインストールされ、PATH に含まれていることを確認してください
- 確認コマンド：`which ffmpeg && which ffprobe`

**データベースのロック**
- デーモンのインスタンスは同時に 1 つだけ実行する必要があります
- 既存のプロセスを終了：`pkill reelvault-core`

**ポートが使用中**
- 50051 が使用中の場合、`core/src/main.rs` のポートを変更してください
- またはプロセスを終了：`lsof -ti:50051 | xargs kill`

**Linux でのビルド問題**
- 追加の開発依存関係をインストール：`sudo apt-get install build-essential libssl-dev`

## 次のステップ

1. **macOS クライアント**：SwiftUI フロントエンドの実装（MVP 後）
2. **デスクトップクライアント**：Kotlin Compose フロントエンドの実装（MVP 優先）
3. **テスト**：gRPC API の統合テストを追加
4. **CI/CD**：ビルドとリリース用の GitHub Actions を設定
