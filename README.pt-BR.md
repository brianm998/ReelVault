> [Original em inglês](README.md)

# ReelVault

Um aplicativo de catalogação de vídeos multiplataforma inspirado no Lightroom — navegação rápida e nativa em grandes bibliotecas de vídeo.

**ReelVault serve para organizar, descobrir e gerenciar vídeos. NÃO é um editor de vídeo.**

## Visão geral

O ReelVault permite:

- **Navegar** por milhares de vídeos em uma grade responsiva e virtualizada com tamanho de miniatura ajustável.
- **Fazer scrub** no estilo Lightroom: passe o cursor sobre uma miniatura e deslize para esquerda↔direita para visualizar quadros ao longo da linha do tempo.
- **Agrupar** variantes relacionadas (ex.: exportações 4K e 1080p da mesma fonte) em pilhas no estilo Lightroom; marque uma como preferida para a grade e para abrir.
- **Descobrir** conteúdo por meio de busca em texto completo, filtragem por tags e menus suspensos de filtro por campo (câmera, lente, codec, ano de captura, palavra-chave).
- **Inspecionar** metadados detalhados: codec, resolução, FPS, bitrate, espaço de cor, HDR, EXIF, GPS, modelo de câmera/lente.
- **Organizar** com notas livres, palavras-chave e operações de seleção múltipla.
- **Transferir** clipes para editores externos por arrastar e soltar — arraste um ou mais cartões diretamente da grade para DaVinci Resolve, Final Cut Pro, Premiere Pro ou qualquer aplicativo que aceite arquivos soltos.
- **Fluxos multi-catálogo**: abrir / fechar / alternar catálogos SQLite pelo menu Arquivo, com uma lista de catálogos recentes e títulos de janela por catálogo.

## Arquitetura

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

- **Núcleo Rust** (`core/`) — Daemon gRPC baseado em Tonic. Gerencia o catálogo SQLite, extração de metadados com FFprobe, geração de miniaturas e quadros de scrub, varredura/indexação, busca e agregação de filtros. Suporta troca a quente do catálogo ativo em tempo de execução via RPCs `OpenCatalog` / `CloseCatalog` / `GetCurrentCatalog`, para que um único processo daemon possa servir múltiplas bibliotecas ao longo de sua vida.

- **Cliente desktop Kotlin Compose** (`desktop/`) — Interface Compose Multiplatform. Detecta automaticamente um daemon em execução em `127.0.0.1:50051`; se nenhum estiver rodando, inicia o daemon embutido (recorrendo a uma porta atribuída pelo SO se a 50051 estiver ocupada).

- **Cliente macOS SwiftUI** (`macos/`) — Aplicativo macOS nativo com paridade de recursos, o mesmo fluxo de início automático, um menu Arquivo macOS real (grupo Commands) e um título de janela reativo que acompanha o catálogo aberto.

- **Cliente iOS SwiftUI** (`ios/`) — Um aplicativo iPhone / iPad **exclusivamente remoto**. Não tem acesso a arquivos locais e não embutiu daemon: descobre um daemon via Wi-Fi (mDNS), conecta-se por um canal TLS com impressão digital fixada após um pareamento único, navega via gRPC e **transmite** vídeo (HLS redimensionado) pelo servidor de mídia do daemon. Substitui o arrastar para editores pela folha de compartilhamento do iOS e adiciona upload pelo Fotos / Arquivos. Veja [`ios/README.md`](ios/README.md).

- **ReelVaultKit** (`kit/`) — Um pacote SwiftPM local de código Swift compartilhado usado por **ambos** os clientes Apple: modelos, view-models, cliente gRPC, descoberta, TLS fixado e a camada de cache/streaming de mídia.

- **Catálogo SQLite** — Banco de dados em modo WAL com FTS5 para busca em texto completo. O esquema está em [`core/schema.sql`](core/schema.sql).

> **As miniaturas ProRes RAW são de qualidade exclusiva do macOS.** O ffmpeg não consegue desenvolver ProRes RAW (Atomos S-Log3 / S-Gamut), portanto no **macOS** o daemon o decodifica via QuickLook / AVFoundation — cor correta e scrubbing real quadro a quadro. No **Linux / Windows** não existe tal decodificador, então o daemon recorre ao ffmpeg: quadros mais opacos/escuros e um único quadro de scrub repetido. O núcleo Rust compila de forma idêntica nas três plataformas — AVFoundation nunca é vinculado a ele. Detalhes: [`core/README.md`](core/README.md) (caminhos de compilação e decodificação) e [`macos/README.md`](macos/README.md) (o cliente macOS). Todos os outros codecs são tratados pelo ffmpeg da mesma forma em todos os lugares.

## Status do projeto

**O MVP é funcional no macOS e no Compose Desktop.** Ambos os clientes oferecem o mesmo conjunto de recursos; o cliente macOS adiciona comandos nativos na barra de menus e aberturas de editor via NSWorkspace. Um **cliente iOS exclusivamente remoto** (iPhone / iPad) conecta-se a um daemon pela LAN e transmite vídeo — navegar, inspecionar, empilhar, compartilhar e fazer upload; veja [`ios/README.md`](ios/README.md).

### ✅ Concluído

**Núcleo**
- [x] Daemon gRPC com superfície RPC completa (vídeos, busca, varredura, tags, coleções, pilhas, filtros, status, config, ciclo de vida do catálogo).
- [x] Catálogo SQLite com modo WAL + FTS5; troca a quente do catálogo em tempo de execução via `OpenCatalog` / `CloseCatalog`.
- [x] Extração de metadados FFprobe (codec, resolução, FPS, bitrate, HDR, EXIF, GPS, câmera/lente).
- [x] Geração de miniaturas e quadros de scrub no estilo Lightroom (10 quadros por vídeo) com bloqueios por vídeo para evitar trabalho duplicado.
- [x] Limitação de ffmpeg concorrente (padrão: número de CPUs do host) para evitar saturar armazenamento SAN em varreduras de grandes bibliotecas.
- [x] Varredura de biblioteca com recursão opcional e agrupamento automático de variantes.
- [x] Flags CLI para `--db-path`, `--no-catalog`, `--port` (com recuo para porta atribuída pelo SO) e uma linha stdout `REELVAULT_LISTENING_ON=…` analisável pelos iniciadores de clientes.

**Ambos os clientes**
- [x] Grade virtualizada com contagem de colunas adaptativa e controle deslizante de tamanho de miniatura.
- [x] Pré-visualização por hover-scrub, sobreposição de reprodução ao passar o cursor, seleção múltipla com Shift+intervalo e alternância ⌘/Ctrl.
- [x] Interface de pilhas (grupos): emblemas de pilha com contagem de membros, clique para expandir, estrela para definir preferido, botões de abertura por membro.
- [x] Painéis laterais: locais da biblioteca (esquerda), detalhes + metadados + notas + palavras-chave (direita). Tab alterna os dois, chevrons individuais recolhem cada um.
- [x] Menus suspensos de filtro na barra superior (câmera / lente / palavra-chave / codec / ano) — somente campos com dados são exibidos; combinados com AND junto à busca.
- [x] Menu de ordenação com todos os campos principais (nome do arquivo, datas, duração, tamanho, resolução, fps, codec, bitrate, câmera, lente, palavra-chave); clique novamente para inverter a direção.
- [x] Gerenciamento de tags/palavras-chave: criar na hora, aplicar a seleção múltipla, filtrar a grade clicando no chevron `>`.
- [x] Menu de contexto ao clicar com o botão direito em cada cartão: Abrir com Player Padrão e Revelar no Finder/Explorer — opera sobre toda a seleção múltipla.
- [x] Transferência por arrastar e soltar: arraste cartões selecionados para qualquer aplicativo que aceite arquivos soltos (DaVinci Resolve, Final Cut Pro, Premiere Pro, etc.).
- [x] Fluxo multi-catálogo: menu Arquivo com Abrir / Fechar / Abrir Recente, folha "abrir um catálogo" no primeiro lançamento, lista de recentes persistente, título da janela mostrando o nome do catálogo aberto.
- [x] Início automático do daemon embutido (com recuo se a porta estiver ocupada) quando nenhum backend está rodando na inicialização.
- [x] Ajuda ao passar o cursor (dicas de ferramenta no Kotlin via `TooltipArea`; SwiftUI via `.help(_:)`) em cada elemento interativo e campo de metadados.
- [x] Modo escuro por padrão; alternância de tema claro/escuro.

### 🚧 Planejado

- [ ] Monitoramento de arquivos em tempo real (reindexar quando a pasta subjacente muda).
- [ ] Coleções inteligentes (buscas salvas com regras de filtro dinâmicas).
- [ ] Geração de proxy de vídeo para conteúdo 8K e acima.
- [ ] Atualização automática via GitHub.
- [ ] Pipeline de lançamento CI/CD produzindo instaladores assinados por plataforma.
- [ ] Empacotar o binário do daemon dentro dos pacotes de aplicativos clientes (atualmente o iniciador o encontra via `REELVAULT_CORE_BIN` ou uma árvore de desenvolvimento cargo).

## Primeiros passos

### Pré-requisitos

- **Rust** (1.75+ recomendado) — para compilar o daemon núcleo.
- **FFmpeg / FFprobe** — deve estar no `PATH`. Usado para extração de metadados e geração de miniaturas/quadros de scrub.
- **JDK 17+** + Gradle (wrapper incluído) — para o cliente desktop Kotlin.
- **Swift 5.9+ / Xcode 15+** — para o cliente macOS.
- **Xcode 16+ (iOS 18 SDK) + [XcodeGen](https://github.com/yonaskolb/XcodeGen)**
  (`brew install xcodegen`) — para o cliente iOS.

Veja [`SETUP.md`](SETUP.md) para instruções de instalação específicas por plataforma.

### Compilar o daemon núcleo

```bash
cd core
cargo build --release
```

O binário fica em `core/target/release/reelvault-core`. Execute-o diretamente se quiser controlá-lo você mesmo, ou deixe um dos clientes iniciá-lo no primeiro lançamento:

```bash
# Padrão — usa o catálogo padrão da plataforma na porta padrão.
./target/release/reelvault-core

# Iniciar sem catálogo (modo usado pelos clientes); exibir a porta vinculada.
./target/release/reelvault-core --no-catalog --port 0

# Abrir um catálogo específico na inicialização.
./target/release/reelvault-core --db-path /path/to/library.db
```

O daemon exibe uma linha estável `REELVAULT_LISTENING_ON=127.0.0.1:N` no stdout que os clientes analisam para descobrir a porta atribuída.

### Executar o cliente desktop Kotlin Compose

```bash
cd desktop
./gradlew run
```

No primeiro lançamento, o cliente sonda `127.0.0.1:50051` e — se nada estiver escutando — inicia o daemon embutido. Ordem de busca do local de compilação:

1. `$REELVAULT_CORE_BIN` (um caminho absoluto para o executável daemon)
2. Um binário ao lado do jar do aplicativo
3. `core/target/release/reelvault-core` ou `core/target/debug/reelvault-core` na árvore de desenvolvimento
4. `reelvault-core` no `PATH`

Para desenvolvimento, basta `cargo build` dentro de `core/` e o cliente usará o binário de depuração.

### Executar o cliente macOS SwiftUI

```bash
cd macos
swift run
```

Mesmo fluxo de início automático, mesma ordem de busca de binário (com a adição de um caminho `Resources/reelvault-core` no bundle usado por pacotes de aplicativos assinados). Use `⌘O` para abrir um catálogo e `⇧⌘W` para fechá-lo; a lista de recentes está em `Arquivo → Abrir Recente`.

### Executar o cliente iOS SwiftUI

O cliente iOS é **exclusivamente remoto** — ele se conecta a um daemon pela LAN em vez de iniciar um. Inicie o daemon em modo remoto em uma máquina na mesma rede Wi-Fi:

```bash
reelvault-core --remote --db-path /path/to/library.db --import-dir /path/to/imports
```

Em seguida, gere o projeto Xcode (o `.xcodeproj` não é versionado) e compile:

```bash
cd ios
make project          # requires XcodeGen: brew install xcodegen
make build            # iOS Simulator; or open ReelVault.xcodeproj to run on a device
```

No primeiro lançamento, o aplicativo descobre o daemon via mDNS, você autoriza o dispositivo uma vez com um código de pareamento de 6 dígitos (gere-o pelo menu **Arquivo → Parear Novo Dispositivo** de um cliente desktop, ou pelo log do daemon), e então navegue e transmita. Requer **iOS 18+** e **Xcode 16+**. Detalhes completos, incluindo o modelo de streaming e pareamento, estão em [`ios/README.md`](ios/README.md).

## Documentação

- [`CLAUDE.md`](CLAUDE.md) — Visão do projeto, detalhes de arquitetura, esquema do banco de dados e princípios de design.
- [`SETUP.md`](SETUP.md) — Configuração do ambiente de desenvolvimento.
- [`CORE_REFERENCE.md`](CORE_REFERENCE.md) — Referência detalhada dos módulos do núcleo Rust e da superfície RPC.
- [`CLIENT_COMPARISON.md`](CLIENT_COMPARISON.md) — Comparação lado a lado dos clientes Kotlin e SwiftUI.
- [`ios/README.md`](ios/README.md) — O cliente iOS (iPhone / iPad) exclusivamente remoto: descoberta, pareamento, TLS fixado e streaming HLS.
- [`macos/README.md`](macos/README.md) — O cliente macOS nativo.

## Contribuindo

Veja [`CLAUDE.md`](CLAUDE.md) para as diretrizes de desenvolvimento. Pull requests são bem-vindos — mantenha a paridade de recursos entre os dois clientes e adicione cabeçalhos SPDX em qualquer novo arquivo-fonte (veja Licença abaixo).

## Licença

ReelVault é um software livre, licenciado sob a **GNU General Public License, versão 3 ou (a sua escolha) qualquer versão posterior**. O texto completo da licença está em [`LICENSE`](LICENSE); um aviso de copyright resumido está em [`COPYRIGHT`](COPYRIGHT).

Cada arquivo-fonte possui um identificador SPDX para que ferramentas de análise de licença (REUSE, FOSSology, o detector licensee do GitHub, etc.) possam identificar a licença programaticamente:

```
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors
```

Se você distribuir uma versão modificada do ReelVault — ou qualquer programa que vincule o núcleo Rust como biblioteca — a GPL exige que você disponibilize seu código-fonte sob os mesmos termos. Veja o arquivo LICENSE para o conjunto completo de obrigações.
