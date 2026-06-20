> [English original](README.md)

# ReelVault

Una aplicación de catalogación de video multiplataforma inspirada en Lightroom — navegación rápida y nativa de grandes bibliotecas de video.

**ReelVault es para organizar, descubrir y gestionar videos. NO es un editor de video.**

## Descripción general

ReelVault te permite:

- **Explorar** miles de videos en una cuadrícula virtualizada y responsiva con tamaño de miniaturas ajustable.
- **Hacer scrubbing** al estilo Lightroom: pasa el cursor sobre una miniatura y desliza a izquierda↔derecha para previsualizar fotogramas a lo largo de la línea de tiempo.
- **Agrupar** variantes relacionadas (por ejemplo, exportaciones en 4K y 1080p del mismo origen) en pilas al estilo Lightroom; marca una como preferida para la cuadrícula y la apertura.
- **Descubrir** contenido mediante búsqueda de texto completo, filtrado por etiquetas y menús desplegables de filtro por campo (cámara, lente, códec, año de captura, palabra clave).
- **Inspeccionar** metadatos detallados: códec, resolución, FPS, tasa de bits, espacio de color, HDR, EXIF, GPS, modelo de cámara/lente.
- **Organizar** con notas de formato libre, palabras clave y operaciones de selección múltiple.
- **Transferir** clips a editores externos mediante arrastrar y soltar — arrastra una o más tarjetas directamente desde la cuadrícula hacia DaVinci Resolve, Final Cut Pro, Premiere Pro, o cualquier aplicación que acepte archivos arrastrados.
- **Flujos de trabajo multicatálogo**: abre, cierra y cambia catálogos SQLite desde el menú Archivo, con una lista de catálogos recientes y títulos de ventana por catálogo.

## Arquitectura

```
Desktop / macOS clients          iOS / Android clients
   ↓ gRPC over loopback             ↓ gRPC + HTTPS media over the LAN
   │                                │ (mDNS/NSD discovery · pinned TLS · paired)
   └───────────────┬────────────────┘
                   ↓
        Rust Backend Daemon (reelvault-core)
                   ↓
        SQLite Catalog + FFmpeg/FFprobe + Filesystem
```

- **Núcleo Rust** (`core/`) — Demonio gRPC basado en Tonic. Gestiona el catálogo SQLite, la extracción de metadatos con FFprobe, la generación de miniaturas y fotogramas de scrubbing, el escaneo/indexado, la búsqueda y la agregación de filtros. Soporta el intercambio en caliente del catálogo activo en tiempo de ejecución mediante las RPCs `OpenCatalog` / `CloseCatalog` / `GetCurrentCatalog`, de modo que un único proceso demonio puede servir múltiples bibliotecas durante su ciclo de vida.

- **Cliente de escritorio Kotlin Compose** (`kotlin-desktop/`) — Interfaz con Compose Multiplatform. Detecta automáticamente un demonio en ejecución en `127.0.0.1:50051`; si ninguno está activo, inicia el demonio incluido (recurriendo a un puerto asignado por el SO si el 50051 está ocupado).

- **Cliente macOS SwiftUI** (`macos/`) — Aplicación nativa macOS con paridad de funciones, el mismo flujo de arranque automático, un menú Archivo nativo de macOS (grupo de Commands) y un título de ventana reactivo que refleja el catálogo abierto.

- **Cliente iOS SwiftUI** (`ios/`) — Una aplicación iPhone / iPad **solo remota**. No tiene acceso a archivos locales ni incluye un demonio: descubre el demonio por Wi‑Fi (mDNS), se conecta a través de un canal TLS con huella digital fija tras un emparejamiento único, navega mediante gRPC y **transmite** video (HLS escalado a menor resolución) desde el servidor multimedia del demonio. Reemplaza el arrastre al editor con la hoja de compartir de iOS y agrega carga desde Fotos/Archivos. Consulta [`ios/README.md`](ios/README.md).

- **Kotlin Compose Android client** (`android/`) — A **remote-only**
  Android phone / tablet app. Connects to a daemon over the LAN (NSD
  discovery), streams video via ExoPlayer, and replaces editor drag-out with
  the Android share intent. Also embeds the full Rust core for on-device local
  library access — browse, catalog, and upload footage directly from the
  device. See [`android/README.md`](android/README.md).

- **ReelVaultKit** (`kit/`) — Un paquete SwiftPM local con Swift compartido por **ambos** clientes de Apple: modelos, view-models, el cliente gRPC, descubrimiento, TLS fijo y la capa de caché/streaming multimedia.

- **Catálogo SQLite** — Base de datos en modo WAL con FTS5 para búsqueda de texto completo. El esquema está en [`core/schema.sql`](core/schema.sql).

> **Las miniaturas de ProRes RAW son de calidad exclusiva en macOS.** ffmpeg no puede desarrollar ProRes RAW (Atomos S-Log3 / S-Gamut), por lo que en **macOS** el demonio lo decodifica a través de QuickLook / AVFoundation — color correcto y scrubbing real por fotograma. En **Linux / Windows** no existe tal decodificador, por lo que el demonio recurre a ffmpeg: fotogramas más planos/oscuros y un único fotograma de scrubbing repetido. El núcleo Rust se compila de forma idéntica en las tres plataformas — AVFoundation nunca se enlaza con él. Detalles: [`core/README.md`](core/README.md) (rutas de compilación y decodificación) y [`macos/README.md`](macos/README.md) (el cliente macOS). Todos los demás códecs son manejados por ffmpeg de la misma manera en todas partes.

## Estado del proyecto

**El MVP es funcional en macOS y Compose Desktop.** Ambos clientes incluyen el mismo conjunto de funciones; el cliente macOS agrega comandos nativos en la barra de menús y lanzamientos de editores a través de NSWorkspace. Un **cliente iOS solo remoto** (iPhone / iPad) se conecta a un demonio por la red local y transmite video — explorar, inspeccionar, apilar, compartir y cargar; consulta [`ios/README.md`](ios/README.md).

### ✅ Completado

**Núcleo**
- [x] Demonio gRPC con superficie RPC completa (videos, búsqueda, escaneo, etiquetas, colecciones, pilas, filtros, estado, configuración, ciclo de vida del catálogo).
- [x] Catálogo SQLite con modo WAL + FTS5; intercambio en caliente del catálogo en tiempo de ejecución mediante `OpenCatalog` / `CloseCatalog`.
- [x] Rich metadata extraction via FFprobe + platform-native helpers: codec,
      resolution, FPS, bitrate, bit depth, HDR (from transfer characteristics),
      color space, dynamic range / log profile, timecode, capture FPS, audio
      tracks / language / sample rate / bit depth, EXIF, GPS track (per-frame
      polyline), altitude, camera / lens model, ISO, aperture, exposure time,
      focal length, white balance, exposure mode/program, spatial video, 360°
      video. iPhone-specific QuickTime per-track metadata (lens, GPS, aperture)
      parsed natively so recorder-wrapped clips expose the true camera.- [x] Generación de miniaturas y fotogramas de scrubbing al estilo Lightroom (10 fotogramas por video) con bloqueos por video para deduplicar el trabajo.
- [x] Limitador de ffmpeg concurrente (por defecto igual al número de CPUs del host) para evitar saturar almacenamiento respaldado por SAN en escaneos de grandes bibliotecas.
- [x] Escaneo de biblioteca con recursión opcional y agrupación automática de variantes.
- [x] Opciones CLI `--db-path`, `--no-catalog`, `--port` (con respaldo a puerto asignado por el SO) y una línea de salida estándar `REELVAULT_LISTENING_ON=…` para que los lanzadores de clientes la analicen.

**Ambos clientes**
- [x] Vista de cuadrícula virtualizada con conteo de columnas adaptativo y control deslizante de tamaño de miniatura.
- [x] Previsualización de scrubbing al pasar el cursor, superposición de reproducción, selección múltiple con rango por Shift y alternancia con ⌘/Ctrl.
- [x] Interfaz de pilas (grupos): insignias de pila con conteo de miembros, clic para expandir, estrella para establecer como preferida, botones de apertura por miembro.
- [x] Paneles laterales: ubicaciones de biblioteca (izquierda), detalles + metadatos + notas + palabras clave (derecha). Tab alterna ambos, los chevrons individuales colapsan cada uno.
- [x] Menús desplegables de filtro en la barra superior (cámara / lente / palabra clave / códec / año) — solo se muestran los campos con datos; combinados con AND junto a la búsqueda.
- [x] Menú de ordenación con todos los campos principales (nombre de archivo, fechas, duración, tamaño, resolución, fps, códec, tasa de bits, cámara, lente, palabra clave); haz clic de nuevo para invertir la dirección.
- [x] Gestión de etiquetas/palabras clave: crear al vuelo, aplicar a selección múltiple, filtrar la cuadrícula haciendo clic en el chevron `>`.
- [x] Menú contextual con clic derecho en cada tarjeta: Abrir con reproductor predeterminado y Mostrar en Finder/Explorer — opera sobre toda la selección múltiple.
- [x] Transferencia por arrastrar y soltar: arrastra las tarjetas seleccionadas a cualquier aplicación que acepte archivos (DaVinci Resolve, Final Cut Pro, Premiere Pro, etc.).
- [x] Flujo multicatálogo: menú Archivo con Abrir / Cerrar / Abrir reciente, hoja "abrir un catálogo" en el primer lanzamiento, lista de recientes persistente, título de ventana que muestra el nombre del catálogo abierto.
- [x] Arranque automático del demonio incluido (con respaldo si el puerto está ocupado) cuando no hay backend activo al inicio.
- [x] Ayuda de texto al pasar el cursor (tooltips en Kotlin mediante `TooltipArea`; SwiftUI mediante `.help(_:)`) en cada elemento interactivo y campo de metadatos.
- [x] Modo oscuro por defecto; alternador de tema claro/oscuro.

### 🚧 Planificado

- [ ] Vigilancia de archivos en tiempo real (re-indexar cuando cambia la carpeta subyacente).
- [ ] Colecciones inteligentes (búsquedas guardadas con reglas de filtro en vivo).
- [ ] Generación de video proxy para material en 8K o superior.
- [ ] Actualización automática basada en GitHub.
- [ ] Canal de publicación CI/CD que produce instaladores firmados por plataforma.
- [ ] Empaquetar el binario del demonio dentro de los paquetes de aplicación cliente (actualmente el lanzador lo encuentra mediante `REELVAULT_CORE_BIN` o un árbol de desarrollo de cargo).

## Primeros pasos

### Prerequisitos

- **Rust** (1.75+ recomendado) — para compilar el demonio núcleo.
- **FFmpeg / FFprobe** — debe estar en el `PATH`. Se usa para extracción de metadatos y generación de miniaturas/fotogramas de scrubbing.
- **JDK 17+** + Gradle (wrapper incluido) — para el cliente de escritorio Kotlin.
- **Swift 5.9+ / Xcode 15+** — para el cliente macOS.
- **Xcode 16+ (iOS 18 SDK) + [XcodeGen](https://github.com/yonaskolb/XcodeGen)**
  (`brew install xcodegen`) — para el cliente iOS.

Consulta [`SETUP.md`](SETUP.md) para instrucciones de instalación específicas por plataforma.

### Compilar el demonio núcleo

```bash
cd core
cargo build --release
```

El binario queda en `core/target/release/reelvault-core`. Ejecútalo directamente si deseas controlarlo tú mismo, o deja que uno de los clientes lo inicie automáticamente en el primer lanzamiento:

```bash
# Por defecto — usa el catálogo predeterminado de la plataforma en el puerto predeterminado.
./target/release/reelvault-core

# Inicia sin catálogo (modo que usan los clientes); muestra el puerto asignado.
./target/release/reelvault-core --no-catalog --port 0

# Abre un catálogo específico al arrancar.
./target/release/reelvault-core --db-path /path/to/library.db
```

El demonio imprime una línea estable `REELVAULT_LISTENING_ON=127.0.0.1:N` en la salida estándar que los clientes analizan para descubrir el puerto asignado.

### Ejecutar el cliente de escritorio Kotlin Compose

```bash
cd kotlin-desktop
./gradlew run
```

En el primer lanzamiento el cliente sondea `127.0.0.1:50051` y — si nada está escuchando — inicia el demonio incluido. Orden de búsqueda de la ubicación de compilación:

1. `$REELVAULT_CORE_BIN` (ruta absoluta al ejecutable del demonio)
2. Un binario junto al archivo jar de la aplicación
3. `core/target/release/reelvault-core` o `core/target/debug/reelvault-core` en el árbol de desarrollo
4. `reelvault-core` en el `PATH`

Para el desarrollo, simplemente ejecuta `cargo build` dentro de `core/` y el cliente recogerá el binario de depuración.

### Ejecutar el cliente macOS SwiftUI

```bash
cd macos
swift run
```

El mismo flujo de arranque automático, el mismo orden de búsqueda de binarios (con la adición de una ruta `Resources/reelvault-core` en el bundle usada por los paquetes de aplicación firmados). Usa `⌘O` para abrir un catálogo y `⇧⌘W` para cerrarlo; la lista de recientes está en `File → Open Recent`.

### Ejecutar el cliente iOS SwiftUI

El cliente iOS es **solo remoto** — se conecta a un demonio por la red local en lugar de iniciar uno propio. Inicia el demonio en modo remoto en una máquina en el mismo Wi‑Fi:

```bash
reelvault-core --remote --db-path /path/to/library.db --import-dir /path/to/imports
```

Luego genera el proyecto Xcode (el `.xcodeproj` no está versionado) y compila:

```bash
cd ios
make project          # requires XcodeGen: brew install xcodegen
make build            # iOS Simulator; or open ReelVault.xcodeproj to run on a device
```

En el primer lanzamiento la aplicación descubre el demonio por mDNS, autorizas el dispositivo una vez con un código de emparejamiento de 6 dígitos (genéralo desde **File → Pair a New Device** en un cliente de escritorio, o desde el registro del demonio), y luego puedes explorar y transmitir. Requiere **iOS 18+** y **Xcode 16+**. Los detalles completos, incluyendo el modelo de transmisión y emparejamiento, están en [`ios/README.md`](ios/README.md).

## Documentación

- [`CLAUDE.md`](CLAUDE.md) — Visión del proyecto, detalles de arquitectura, esquema de base de datos y principios de diseño.
- [`SETUP.md`](SETUP.md) — Configuración del entorno de desarrollo.
- [`CORE_REFERENCE.md`](CORE_REFERENCE.md) — Referencia detallada de los módulos del núcleo Rust y la superficie RPC.
- [`CLIENT_COMPARISON.md`](CLIENT_COMPARISON.md) — Comparación lado a lado de los clientes Kotlin y SwiftUI.
- [`ios/README.md`](ios/README.md) — El cliente iOS (iPhone / iPad) solo remoto: descubrimiento, emparejamiento, TLS fijo y streaming HLS.
- [`macos/README.md`](macos/README.md) — El cliente nativo macOS.

## Contribuir

Consulta [`CLAUDE.md`](CLAUDE.md) para las pautas de desarrollo. Las contribuciones son bienvenidas — mantén la paridad de características en los cuatro clientes donde sea aplicable
(consulta CLAUDE.md para desviaciones legítimas por plataforma) y añade encabezados SPDX a cualquier archivo fuente nuevo (consulta la Licencia más abajo).

## Licencia

ReelVault es software libre, licenciado bajo la **Licencia Pública General GNU, versión 3 o (a tu elección) cualquier versión posterior**. El texto completo de la licencia está en [`LICENSE`](LICENSE); un aviso de copyright breve está en [`COPYRIGHT`](COPYRIGHT).

Cada archivo fuente lleva un identificador SPDX para que las herramientas de análisis de licencias (REUSE, FOSSology, el detector licensee de GitHub, etc.) puedan identificar la licencia de forma programática:

```
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors
```

Si distribuyes una versión modificada de ReelVault — o cualquier programa que enlace el núcleo Rust como biblioteca — la GPL te exige que pongas tu código fuente disponible bajo los mismos términos. Consulta el archivo LICENSE para el conjunto completo de obligaciones.
