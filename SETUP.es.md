> [English original](SETUP.md)

# Configuración del entorno de desarrollo de ReelVault

## Prerequisitos

### Requisitos del sistema

- **macOS 11+**, **Linux (Ubuntu 20.04+)** o **Windows 10+**
- **Rust 1.70+** ([Instalar mediante rustup](https://rustup.rs/))
- **FFmpeg y FFprobe** (para análisis de video y generación de miniaturas)

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
Descarga desde https://ffmpeg.org/download.html o usa:
```bash
choco install ffmpeg
```

### Instalar Rust

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env
```

Verificar:
```bash
rustc --version
cargo --version
```

## Compilar el núcleo

```bash
cd core
cargo build --release
```

El binario compilado estará en `core/target/release/reelvault-core`.

## Ejecutar el demonio núcleo

```bash
./core/target/release/reelvault-core
```

El demonio:
- Creará una base de datos en `~/.reelvault/catalog.db`
- Creará un directorio de caché de miniaturas
- Escuchará en `127.0.0.1:50051` para conexiones gRPC

## Ejecutar pruebas

```bash
cd core
cargo test
```

## Comandos de desarrollo

**Verificar código sin compilar:**
```bash
cargo check
```

**Formatear código:**
```bash
cargo fmt
```

**Analizar código:**
```bash
cargo clippy
```

**Compilar con símbolos de depuración:**
```bash
cargo build
```

## Conectar un frontend

Una vez que el demonio núcleo esté en ejecución, los frontends pueden conectarse mediante gRPC en `http://127.0.0.1:50051`.

Las definiciones proto están en `core/proto/reelvault.proto` y deben usarse para generar el código de cliente para cada lenguaje de frontend.

## Resolución de problemas

**"ffmpeg not found" (FFmpeg no encontrado)**
- Asegúrate de que FFmpeg esté instalado y en el PATH
- Verifica con: `which ffmpeg && which ffprobe`

**Base de datos bloqueada**
- Solo debe ejecutarse una instancia del demonio a la vez
- Termina los procesos existentes: `pkill reelvault-core`

**Puerto ya en uso**
- Cambia el puerto en `core/src/main.rs` si el 50051 está ocupado
- O termina el proceso: `lsof -ti:50051 | xargs kill`

**Problemas de compilación en Linux**
- Instala dependencias de desarrollo adicionales: `sudo apt-get install build-essential libssl-dev`

## Próximos pasos

1. **Cliente macOS**: Implementar el frontend SwiftUI (post-MVP)
2. **Cliente de escritorio**: Implementar el frontend Kotlin Compose (prioridad MVP)
3. **Pruebas**: Agregar pruebas de integración para la API gRPC
4. **CI/CD**: Configurar GitHub Actions para compilaciones y publicaciones
