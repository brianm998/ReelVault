> [Version originale en anglais](SETUP.md)

# Configuration de l'environnement de développement ReelVault

## Prérequis

### Configuration système requise

- **macOS 11+**, **Linux (Ubuntu 20.04+)** ou **Windows 10+**
- **Rust 1.70+** ([Installation via rustup](https://rustup.rs/))
- **FFmpeg & FFprobe** (pour l'analyse vidéo et la génération de miniatures)

### Installer FFmpeg

**macOS :**
```bash
brew install ffmpeg
```

**Linux (Ubuntu/Debian) :**
```bash
sudo apt-get install ffmpeg
```

**Windows :**
Téléchargez depuis https://ffmpeg.org/download.html ou utilisez :
```bash
choco install ffmpeg
```

### Installer Rust

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env
```

Vérification :
```bash
rustc --version
cargo --version
```

## Compilation du noyau

```bash
cd core
cargo build --release
```

Le binaire compilé se trouvera à `core/target/release/reelvault-core`.

## Lancement du démon noyau

```bash
./core/target/release/reelvault-core
```

Le démon va :
- Créer une base de données à `~/.reelvault/catalog.db`
- Créer un répertoire de cache pour les miniatures
- Écouter sur `127.0.0.1:50051` les connexions gRPC

## Exécution des tests

```bash
cd core
cargo test
```

## Commandes de développement

**Vérifier le code sans compiler :**
```bash
cargo check
```

**Formater le code :**
```bash
cargo fmt
```

**Analyser le code (lint) :**
```bash
cargo clippy
```

**Compiler avec les symboles de débogage :**
```bash
cargo build
```

## Connexion d'un frontend

Une fois le démon noyau en cours d'exécution, les frontends peuvent se connecter via gRPC à `http://127.0.0.1:50051`.

Les définitions proto se trouvent dans `core/proto/reelvault.proto` et doivent être utilisées pour générer le code client pour chaque langage frontend.

## Dépannage

**« ffmpeg not found »**
- Assurez-vous que FFmpeg est installé et dans votre PATH
- Vérifiez : `which ffmpeg && which ffprobe`

**Base de données verrouillée**
- Une seule instance du démon doit tourner à la fois
- Arrêtez les processus existants : `pkill reelvault-core`

**Port déjà utilisé**
- Changez le port dans `core/src/main.rs` si 50051 est occupé
- Ou arrêtez le processus : `lsof -ti:50051 | xargs kill`

**Problèmes de compilation sur Linux**
- Installez les dépendances de développement supplémentaires : `sudo apt-get install build-essential libssl-dev`

## Prochaines étapes

1. **Client macOS** : Implémenter le frontend SwiftUI (post-MVP)
2. **Client Desktop** : Implémenter le frontend Kotlin Compose (priorité MVP)
3. **Tests** : Ajouter des tests d'intégration pour l'API gRPC
4. **CI/CD** : Configurer GitHub Actions pour les compilations et les publications
