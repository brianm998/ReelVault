use std::collections::BTreeMap;
use std::env;
use std::fs;
use std::path::Path;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    tonic_build::compile_protos("proto/reelvault.proto")?;
    emit_sensor_table()?;
    emit_frameshot()?;
    Ok(())
}

/// Compile the `rv-frameshot` AVFoundation helper (macOS only) and emit
/// `$OUT_DIR/frameshot.rs` exposing its bytes as
/// `pub static FRAMESHOT_BIN: Option<&[u8]>`. The daemon embeds those bytes and
/// extracts them on first use to seek ProRes RAW to arbitrary timestamps —
/// something `qlmanage` (poster only) and ffmpeg (can't develop ProRes RAW)
/// can't do. Off macOS, or when `swiftc` isn't installed, this emits `None` and
/// the daemon keeps its single-QuickLook-poster fallback; Linux/Windows core
/// builds are unaffected.
fn emit_frameshot() -> Result<(), Box<dyn std::error::Error>> {
    let out_dir = env::var("OUT_DIR")?;
    let gen_path = Path::new(&out_dir).join("frameshot.rs");
    let src = Path::new("macos/rv-frameshot.swift");
    println!("cargo:rerun-if-changed={}", src.display());

    let mut embedded = false;
    let target_macos = env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("macos");
    if target_macos {
        let has_swiftc = std::process::Command::new("swiftc")
            .arg("--version")
            .output()
            .map(|o| o.status.success())
            .unwrap_or(false);
        if has_swiftc {
            let bin_path = Path::new(&out_dir).join("rv-frameshot");
            let status = std::process::Command::new("swiftc")
                .args(["-O", "-o"])
                .arg(&bin_path)
                .arg(src)
                .status();
            match status {
                Ok(s) if s.success() && bin_path.exists() => embedded = true,
                _ => println!(
                    "cargo:warning=rv-frameshot.swift failed to compile; ProRes RAW \
                     scrub frames will fall back to a single QuickLook poster"
                ),
            }
        } else {
            println!(
                "cargo:warning=swiftc not found; ProRes RAW scrub frames will fall back \
                 to a single QuickLook poster"
            );
        }
    }

    // The generated file lives in OUT_DIR alongside the compiled binary, so the
    // relative include_bytes! resolves correctly.
    let body = if embedded {
        "pub static FRAMESHOT_BIN: Option<&[u8]> = Some(include_bytes!(\"rv-frameshot\"));\n"
    } else {
        "pub static FRAMESHOT_BIN: Option<&[u8]> = None;\n"
    };
    fs::write(&gen_path, body)?;
    Ok(())
}

/// Read `data/sensor_resolutions.json` and emit `$OUT_DIR/sensor_data.rs`
/// containing the static `SENSOR_RESOLUTIONS` (natives) and
/// `SENSOR_VIDEO_MAX` (max in-camera video resolution) slices used by
/// `crate::full_resolution`. The runtime module `include!`s the result.
///
/// We use a sorted `BTreeMap` so the emitted source is byte-identical
/// across rebuilds whenever the JSON content is unchanged — keeps
/// incremental compilation cheap.
fn emit_sensor_table() -> Result<(), Box<dyn std::error::Error>> {
    let data_path = Path::new("data/sensor_resolutions.json");
    println!("cargo:rerun-if-changed={}", data_path.display());

    let json_text = fs::read_to_string(data_path)?;
    let value: serde_json::Value = serde_json::from_str(&json_text)?;

    let cameras = value
        .get("cameras")
        .and_then(|v| v.as_object())
        .ok_or("sensor_resolutions.json missing `cameras` object")?;

    let mut natives_map: BTreeMap<&str, Vec<(u32, u32)>> = BTreeMap::new();
    let mut video_max_map: BTreeMap<&str, (u32, u32)> = BTreeMap::new();

    for (model, body) in cameras {
        let obj = body
            .as_object()
            .ok_or_else(|| format!("camera {model:?}: value must be an object"))?;

        // natives — required, non-empty list of [w,h]
        let natives_arr = obj
            .get("natives")
            .and_then(|v| v.as_array())
            .ok_or_else(|| format!("camera {model:?}: missing `natives` array"))?;
        let mut natives = Vec::with_capacity(natives_arr.len());
        for pair in natives_arr {
            let (w, h) = parse_wh_pair(pair)
                .ok_or_else(|| format!("camera {model:?}: invalid natives entry {pair}"))?;
            natives.push((w, h));
        }
        if natives.is_empty() {
            return Err(format!("camera {model:?}: empty natives list").into());
        }
        natives_map.insert(model.as_str(), natives);

        // video_max — optional. Absent or null = no timelapse detection for
        // this camera. Present = strict (w, h).
        if let Some(vm) = obj.get("video_max") {
            if !vm.is_null() {
                let (w, h) = parse_wh_pair(vm).ok_or_else(|| {
                    format!("camera {model:?}: invalid video_max entry {vm}")
                })?;
                video_max_map.insert(model.as_str(), (w, h));
            }
        }
    }

    let mut out = String::with_capacity(96 * natives_map.len());
    out.push_str(
        "// Generated by build.rs from data/sensor_resolutions.json — do not edit.\n",
    );
    out.push_str("pub(crate) const SENSOR_RESOLUTIONS: &[(&str, &[(u32, u32)])] = &[\n");
    for (model, natives) in &natives_map {
        out.push_str("    (\"");
        out.push_str(model);
        out.push_str("\", &[");
        for (i, (w, h)) in natives.iter().enumerate() {
            if i > 0 {
                out.push_str(", ");
            }
            out.push_str(&format!("({w}, {h})"));
        }
        out.push_str("]),\n");
    }
    out.push_str("];\n\n");

    out.push_str(
        "/// Max in-camera video recording resolution per camera. Only includes\n\
         /// bodies where this is strictly less (by pixel area) than at least one\n\
         /// of the camera's photo native modes; cinema / open-gate cameras that\n\
         /// record video at sensor native are intentionally omitted so the\n\
         /// timelapse heuristic stays silent for them.\n",
    );
    out.push_str("pub(crate) const SENSOR_VIDEO_MAX: &[(&str, (u32, u32))] = &[\n");
    for (model, (w, h)) in &video_max_map {
        out.push_str(&format!("    (\"{model}\", ({w}, {h})),\n"));
    }
    out.push_str("];\n");

    let out_dir = env::var("OUT_DIR")?;
    let out_path = Path::new(&out_dir).join("sensor_data.rs");
    fs::write(&out_path, out)?;
    Ok(())
}

fn parse_wh_pair(v: &serde_json::Value) -> Option<(u32, u32)> {
    let arr = v.as_array()?;
    if arr.len() != 2 {
        return None;
    }
    let w = arr[0].as_u64()? as u32;
    let h = arr[1].as_u64()? as u32;
    if w == 0 || h == 0 {
        return None;
    }
    Some((w, h))
}
