> [Version originale en anglais](README.md)

# ReelVault

Une application de catalogage vidéo multiplateforme inspirée de Lightroom — navigation rapide et native dans de grandes bibliothèques vidéo.

**ReelVault sert à organiser, découvrir et gérer des vidéos. Ce n'est PAS un éditeur vidéo.**

## Présentation

ReelVault vous permet de :

- **Parcourir** des milliers de vidéos dans une grille réactive et virtualisée avec une taille de miniature ajustable.
- **Scrubber** à la manière de Lightroom : survolez une miniature et glissez vers la gauche↔droite pour prévisualiser les images à travers la timeline.
- **Regrouper** des variantes associées (par ex. exports 4K et 1080p de la même source) en piles style Lightroom ; marquez-en une comme préférée pour la grille et l'ouverture.
- **Découvrir** du contenu via la recherche plein texte, le filtrage par tags et les menus déroulants de filtre par champ (caméra, objectif, codec, année de capture, mot-clé).
- **Inspecter** les métadonnées détaillées : codec, résolution, FPS, débit, espace colorimétrique, HDR, EXIF, GPS, modèle caméra/objectif.
- **Organiser** avec des notes libres, des mots-clés et des opérations multi-sélection.
- **Transmettre** des clips à des éditeurs externes par glisser-déposer — faites glisser une ou plusieurs cartes directement depuis la grille vers DaVinci Resolve, Final Cut Pro, Premiere Pro ou toute application acceptant les dépôts de fichiers.
- **Flux multi-catalogues** : ouvrir / fermer / basculer entre des catalogues SQLite depuis un menu Fichier, avec une liste des catalogues récents et des titres de fenêtre par catalogue.

## Architecture

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

- **Noyau Rust** (`core/`) — Démon gRPC basé sur Tonic. Gère le catalogue SQLite, l'extraction de métadonnées FFprobe, la génération de miniatures et de frames de scrub, le scan/indexation, la recherche et l'agrégation des filtres. Prend en charge l'échange à chaud du catalogue actif à l'exécution via les RPCs `OpenCatalog` / `CloseCatalog` / `GetCurrentCatalog`, afin qu'un seul processus démon puisse servir plusieurs bibliothèques au cours de sa durée de vie.

- **Client desktop Kotlin Compose** (`kotlin-desktop/`) — Interface Compose Multiplatform. Détecte automatiquement un démon en cours d'exécution sur `127.0.0.1:50051` ; si aucun n'est actif, il lance lui-même le démon intégré (en revenant sur un port assigné par l'OS si 50051 est occupé).

- **Client macOS SwiftUI** (`macos/`) — Application macOS native avec parité de fonctionnalités, le même flux de démarrage automatique, un vrai menu Fichier macOS (groupe Commands) et un titre de fenêtre réactif qui suit le catalogue ouvert.

- **Client iOS SwiftUI** (`ios/`) — Une application iPhone / iPad **exclusivement distante**. Elle n'a pas d'accès aux fichiers locaux et n'embarque pas de démon : elle découvre un démon via Wi-Fi (mDNS), se connecte via un canal TLS épinglé par empreinte après un jumelage unique, navigue via gRPC et **diffuse** la vidéo (HLS redimensionnée) depuis le serveur média du démon. Remplace le glisser-déposer vers les éditeurs par la feuille de partage iOS et ajoute l'importation depuis Photos / Fichiers. Voir [`ios/README.md`](ios/README.md).

- **ReelVaultKit** (`kit/`) — Un package SwiftPM local de code Swift partagé utilisé par **les deux** clients Apple : modèles, view-models, client gRPC, découverte, TLS épinglé et la couche de cache/streaming média.

- **Catalogue SQLite** — Base de données en mode WAL avec FTS5 pour la recherche plein texte. Le schéma est dans [`core/schema.sql`](core/schema.sql).

> **Les miniatures ProRes RAW sont d'une qualité macOS uniquement.** ffmpeg ne peut pas développer le ProRes RAW (Atomos S-Log3 / S-Gamut), donc sur **macOS** le démon le décode via QuickLook / AVFoundation — couleur correcte et scrubbing vrai image par image. Sur **Linux / Windows** il n'existe pas de tel décodeur, donc le démon revient à ffmpeg : images plus ternes/sombres et une seule frame de scrub répétée. Le noyau Rust se compile de manière identique sur les trois plateformes — AVFoundation n'y est jamais lié. Détails : [`core/README.md`](core/README.md) (chemins de compilation et décodage) et [`macos/README.md`](macos/README.md) (le client macOS). Tous les autres codecs sont traités par ffmpeg de la même manière partout.

## État du projet

**Le MVP est fonctionnel sur macOS et Compose Desktop.** Les deux clients offrent le même ensemble de fonctionnalités ; le client macOS ajoute des commandes natives dans la barre de menus et des lancements d'éditeurs via NSWorkspace. Un **client iOS exclusivement distant** (iPhone / iPad) se connecte à un démon via le réseau local et diffuse la vidéo — parcourir, inspecter, empiler, partager et importer ; voir [`ios/README.md`](ios/README.md).

### ✅ Réalisé

**Noyau**
- [x] Démon gRPC avec surface RPC complète (vidéos, recherche, scan, tags, collections, piles, filtres, statut, config, cycle de vie du catalogue).
- [x] Catalogue SQLite avec mode WAL + FTS5 ; échange à chaud du catalogue à l'exécution via `OpenCatalog` / `CloseCatalog`.
- [x] Extraction de métadonnées FFprobe (codec, résolution, FPS, débit, HDR, EXIF, GPS, caméra/objectif).
- [x] Génération de miniatures et de frames de scrub style Lightroom (10 frames par vidéo) avec verrous par vidéo pour éviter les doublons de travail.
- [x] Limitation concurrente de ffmpeg (par défaut le nombre de CPU de la machine hôte) pour éviter de saturer le stockage SAN lors des scans de grandes bibliothèques.
- [x] Scan de bibliothèque avec récursion optionnelle et regroupement automatique des variantes.
- [x] Options CLI pour `--db-path`, `--no-catalog`, `--port` (avec repli sur un port assigné par l'OS) et une ligne stdout `REELVAULT_LISTENING_ON=…` analysable par les lanceurs clients.

**Les deux clients**
- [x] Vue en grille virtualisée avec nombre de colonnes adaptatif et curseur de taille de miniature.
- [x] Prévisualisation par survol-scrub, superposition de lecture au survol, multi-sélection avec plage Shift et bascule ⌘/Ctrl.
- [x] Interface de piles (groupes) : badges de pile avec compteurs de membres, clic pour développer, étoile pour définir le préféré, boutons d'ouverture par membre.
- [x] Panneaux latéraux : emplacements de bibliothèque (gauche), détails + métadonnées + notes + mots-clés (droite). Tab bascule les deux, les chevrons individuels réduisent chacun.
- [x] Menus déroulants de filtre dans la barre supérieure (caméra / objectif / mot-clé / codec / année) — seuls les champs avec des données sont affichés ; combinés par AND avec la recherche.
- [x] Menu de tri avec tous les champs principaux (nom de fichier, dates, durée, taille, résolution, fps, codec, débit, caméra, objectif, mot-clé) ; cliquer à nouveau pour inverser la direction.
- [x] Gestion des tags/mots-clés : créer à la volée, appliquer à une multi-sélection, filtrer la grille en cliquant sur le chevron `>`.
- [x] Menu contextuel au clic droit sur chaque carte : Ouvrir avec le lecteur par défaut et Révéler dans Finder/Explorateur — opère sur toute la multi-sélection.
- [x] Transfert par glisser-déposer : faites glisser les cartes sélectionnées vers toute application acceptant les dépôts de fichiers (DaVinci Resolve, Final Cut Pro, Premiere Pro, etc.).
- [x] Flux multi-catalogues : menu Fichier avec Ouvrir / Fermer / Ouvrir Récent, feuille « ouvrir un catalogue » au premier lancement, liste des récents persistante, titre de fenêtre affichant le nom du catalogue ouvert.
- [x] Démarrage automatique du démon intégré (avec repli si le port est occupé) quand aucun backend ne tourne au démarrage.
- [x] Aide au survol (infobulles sur Kotlin via `TooltipArea` ; SwiftUI via `.help(_:)`) sur chaque élément interactif et champ de métadonnée.
- [x] Mode sombre par défaut ; bascule de thème clair/sombre.

### 🚧 Prévu

- [ ] Surveillance des fichiers en temps réel (réindexation quand le dossier sous-jacent change).
- [ ] Collections intelligentes (recherches sauvegardées avec règles de filtre dynamiques).
- [ ] Génération de proxy vidéo pour les contenus 8K et plus.
- [ ] Mise à jour automatique via GitHub.
- [ ] Pipeline de publication CI/CD produisant des installateurs signés par plateforme.
- [ ] Intégration du binaire démon dans les bundles d'applications clients (actuellement le lanceur le trouve via `REELVAULT_CORE_BIN` ou un arbre de développement cargo).

## Démarrage rapide

### Prérequis

- **Rust** (1.75+ recommandé) — pour compiler le démon noyau.
- **FFmpeg / FFprobe** — doit être dans le `PATH`. Utilisé pour l'extraction de métadonnées et la génération de miniatures/frames de scrub.
- **JDK 17+** + Gradle (wrapper inclus) — pour le client desktop Kotlin.
- **Swift 5.9+ / Xcode 15+** — pour le client macOS.
- **Xcode 16+ (iOS 18 SDK) + [XcodeGen](https://github.com/yonaskolb/XcodeGen)**
  (`brew install xcodegen`) — pour le client iOS.

Voir [`SETUP.md`](SETUP.md) pour les instructions d'installation spécifiques à chaque plateforme.

### Compiler le démon noyau

```bash
cd core
cargo build --release
```

Le binaire se trouve à `core/target/release/reelvault-core`. Exécutez-le directement si vous souhaitez le piloter vous-même, ou laissez simplement l'un des clients le démarrer pour vous au premier lancement :

```bash
# Par défaut — utilise le catalogue par défaut de la plateforme sur le port par défaut.
./target/release/reelvault-core

# Démarrer sans catalogue (mode utilisé par les clients) ; afficher le port lié.
./target/release/reelvault-core --no-catalog --port 0

# Ouvrir un catalogue spécifique au démarrage.
./target/release/reelvault-core --db-path /path/to/library.db
```

Le démon affiche une ligne stable `REELVAULT_LISTENING_ON=127.0.0.1:N` sur stdout que les clients analysent pour découvrir le port assigné.

### Lancer le client desktop Kotlin Compose

```bash
cd kotlin-desktop
./gradlew run
```

Au premier lancement, le client sonde `127.0.0.1:50051` et — si rien n'écoute — lance le démon intégré. Ordre de recherche de l'emplacement de compilation :

1. `$REELVAULT_CORE_BIN` (un chemin absolu vers l'exécutable démon)
2. Un binaire à côté du jar de l'application
3. `core/target/release/reelvault-core` ou `core/target/debug/reelvault-core` dans l'arbre de développement
4. `reelvault-core` dans le `PATH`

Pour le développement, faites simplement `cargo build` dans `core/` et le client récupérera le binaire de débogage.

### Lancer le client macOS SwiftUI

```bash
cd macos
swift run
```

Même flux de démarrage automatique, même ordre de recherche de binaire (avec l'ajout d'un chemin `Resources/reelvault-core` dans le bundle utilisé par les bundles d'applications signés). Utilisez `⌘O` pour ouvrir un catalogue et `⇧⌘W` pour le fermer ; la liste des récents est sous `Fichier → Ouvrir Récent`.

### Lancer le client iOS SwiftUI

Le client iOS est **exclusivement distant** — il se connecte à un démon via le réseau local plutôt que d'en démarrer un. Lancez le démon en mode distant sur une machine du même Wi-Fi :

```bash
reelvault-core --remote --db-path /path/to/library.db --import-dir /path/to/imports
```

Ensuite, générez le projet Xcode (le `.xcodeproj` n'est pas versionné) et compilez :

```bash
cd ios
make project          # requires XcodeGen: brew install xcodegen
make build            # iOS Simulator; or open ReelVault.xcodeproj to run on a device
```

Au premier lancement, l'application découvre le démon via mDNS, vous autorisez l'appareil une fois avec un code de jumelage à 6 chiffres (générez-le depuis le menu **Fichier → Associer un nouvel appareil** d'un client desktop, ou depuis le journal du démon), puis parcourez et diffusez. Nécessite **iOS 18+** et **Xcode 16+**. Les détails complets, y compris le modèle de streaming et de jumelage, sont dans [`ios/README.md`](ios/README.md).

## Documentation

- [`CLAUDE.md`](CLAUDE.md) — Vision du projet, détails d'architecture, schéma de base de données et principes de conception.
- [`SETUP.md`](SETUP.md) — Configuration de l'environnement de développement.
- [`CORE_REFERENCE.md`](CORE_REFERENCE.md) — Référence détaillée des modules du noyau Rust et de la surface RPC.
- [`CLIENT_COMPARISON.md`](CLIENT_COMPARISON.md) — Comparaison côte à côte des clients Kotlin et SwiftUI.
- [`ios/README.md`](ios/README.md) — Le client iOS (iPhone / iPad) exclusivement distant : découverte, jumelage, TLS épinglé et streaming HLS.
- [`macos/README.md`](macos/README.md) — Le client macOS natif.

## Contribution

Voir [`CLAUDE.md`](CLAUDE.md) pour les directives de développement. Les pull requests sont les bienvenues — veuillez maintenir la parité de fonctionnalités entre les deux clients et ajouter des en-têtes SPDX à tout nouveau fichier source (voir Licence ci-dessous).

## Licence

ReelVault est un logiciel libre, distribué sous la **GNU General Public License, version 3 ou (à votre choix) toute version ultérieure**. Le texte complet de la licence se trouve dans [`LICENSE`](LICENSE) ; une notice de copyright abrégée est dans [`COPYRIGHT`](COPYRIGHT).

Chaque fichier source porte un identifiant SPDX afin que les outils d'analyse de licences (REUSE, FOSSology, le détecteur licensee de GitHub, etc.) puissent identifier la licence de manière programmatique :

```
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors
```

Si vous distribuez une version modifiée de ReelVault — ou tout programme qui lie le noyau Rust en tant que bibliothèque — la GPL vous oblige à rendre votre source disponible selon les mêmes termes. Voir le fichier LICENSE pour l'ensemble complet des obligations.
