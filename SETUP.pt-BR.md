> [Original em inglês](SETUP.md)

# Configuração do Ambiente de Desenvolvimento ReelVault

## Pré-requisitos

### Requisitos de sistema

- **macOS 11+**, **Linux (Ubuntu 20.04+)** ou **Windows 10+**
- **Rust 1.70+** ([Instale via rustup](https://rustup.rs/))
- **FFmpeg & FFprobe** (para análise de vídeo e geração de miniaturas)

### Instalar FFmpeg

**macOS:**
```bash
brew install ffmpeg
```

**Linux (Ubuntu/Debian):**
```bash
sudo apt-get install ffmpeg
```

**Windows:**
Baixe de https://ffmpeg.org/download.html ou use:
```bash
choco install ffmpeg
```

### Instalar Rust

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env
```

Verificação:
```bash
rustc --version
cargo --version
```

## Compilando o Núcleo

```bash
cd core
cargo build --release
```

O binário compilado estará em `core/target/release/reelvault-core`.

## Executando o Daemon Núcleo

```bash
./core/target/release/reelvault-core
```

O daemon irá:
- Criar um banco de dados em `~/.reelvault/catalog.db`
- Criar um diretório de cache de miniaturas
- Escutar em `127.0.0.1:50051` por conexões gRPC

## Executando os Testes

```bash
cd core
cargo test
```

## Comandos de Desenvolvimento

**Verificar código sem compilar:**
```bash
cargo check
```

**Formatar código:**
```bash
cargo fmt
```

**Analisar código (lint):**
```bash
cargo clippy
```

**Compilar com símbolos de depuração:**
```bash
cargo build
```

## Conectando um Frontend

Com o daemon núcleo em execução, os frontends podem conectar-se via gRPC em `http://127.0.0.1:50051`.

As definições proto estão em `core/proto/reelvault.proto` e devem ser usadas para gerar o código cliente em cada linguagem de frontend.

## Solução de Problemas

**"ffmpeg not found"**
- Certifique-se de que FFmpeg está instalado e no seu PATH
- Verifique: `which ffmpeg && which ffprobe`

**Banco de dados bloqueado**
- Apenas uma instância do daemon deve estar rodando por vez
- Encerre os processos existentes: `pkill reelvault-core`

**Porta já em uso**
- Altere a porta em `core/src/main.rs` se a 50051 estiver ocupada
- Ou encerre o processo: `lsof -ti:50051 | xargs kill`

**Problemas de compilação no Linux**
- Instale dependências de desenvolvimento adicionais: `sudo apt-get install build-essential libssl-dev`

## Próximos Passos

1. **Cliente macOS**: Implementar o frontend SwiftUI (pós-MVP)
2. **Cliente Desktop**: Implementar o frontend Kotlin Compose (prioridade MVP)
3. **Testes**: Adicionar testes de integração para a API gRPC
4. **CI/CD**: Configurar GitHub Actions para compilações e lançamentos
