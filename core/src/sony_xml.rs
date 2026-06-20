// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

//! Reader for Sony professional XML sidecars (`<clip>.XML` next to `<clip>.MP4` or `<clip>.MXF`).
//! These contain production metadata like clip name, scene, take, ND filter, iris F-number, and LUT.

use std::path::Path;

/// Metadata from a Sony professional XML sidecar.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct SonyXmlMetadata {
    /// Clip name (e.g., "A001C001_220807").
    pub clip_name: Option<String>,
    /// Scene number (e.g., "1").
    pub scene: Option<String>,
    /// Take number (e.g., "3").
    pub take: Option<String>,
    /// ND filter setting (e.g., "1/4", "1/8", "Auto").
    pub nd_filter: Option<String>,
    /// Iris F-number (e.g., 2.8).
    pub iris_f_number: Option<f64>,
    /// LUT name (e.g., "Venice_Look1.cube").
    pub lut_name: Option<String>,
}

impl SonyXmlMetadata {
    pub fn is_empty(&self) -> bool {
        self.clip_name.is_none()
            && self.scene.is_none()
            && self.take.is_none()
            && self.nd_filter.is_none()
            && self.iris_f_number.is_none()
            && self.lut_name.is_none()
    }
}

/// Look for a sidecar `<clip>.XML` / `<clip>.xml` next to `video_path` and parse it.
/// Returns empty when there's no sidecar or it can't be read.
pub fn read_sidecar(video_path: &Path) -> SonyXmlMetadata {
    for ext in ["XML", "xml"] {
        let sidecar = video_path.with_extension(ext);
        if sidecar == *video_path {
            continue;
        }
        if let Ok(text) = read_full(&sidecar) {
            let m = parse_xml(&text);
            if !m.is_empty() {
                return m;
            }
        }
    }
    SonyXmlMetadata::default()
}

/// Read an entire file as lossy UTF-8.
fn read_full(path: &Path) -> std::io::Result<String> {
    let bytes = std::fs::read(path)?;
    Ok(String::from_utf8_lossy(&bytes).into_owned())
}

/// Parse Sony XML metadata using simple string/regex search (no full XML parser).
pub fn parse_xml(text: &str) -> SonyXmlMetadata {
    SonyXmlMetadata {
        clip_name: extract_text(text, r"<ClipName>(.*?)</ClipName>"),
        scene: extract_text(text, r#"Scene="(\d+)""#),
        take: extract_text(text, r#"Take="(\d+)""#),
        nd_filter: extract_text(text, r#"<NDFilter Value="([^"]+)""#),
        iris_f_number: extract_float(text, r"<IrisFNumber>([\d.]+)</IrisFNumber>"),
        lut_name: extract_text(text, r#"<LUT LUTName="([^"]+)""#),
    }
}

/// Extract the first captured group matching a regex pattern.
fn extract_text(text: &str, pattern: &str) -> Option<String> {
    use regex::Regex;
    let re = Regex::new(pattern).ok()?;
    re.captures(text)?.get(1).map(|m| m.as_str().to_string())
}

/// Extract and parse a float from the first captured group.
fn extract_float(text: &str, pattern: &str) -> Option<f64> {
    extract_text(text, pattern).and_then(|s| s.parse::<f64>().ok())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_sony_xml() {
        let xml = r#"<?xml version="1.0" encoding="utf-8"?>
<NonRealTimeMeta xmlns="urn:schemas-professionalDisc:nonRealTimeMeta:ver.2.20">
  <ClipContent>
    <ClipName>A001C001_220807</ClipName>
    <SceneInformation Scene="1" Take="3"/>
    <IrisFNumber>2.8</IrisFNumber>
    <NDFilter Value="1/4"/>
    <LUT LUTName="Venice_Look1.cube"/>
  </ClipContent>
</NonRealTimeMeta>"#;
        let m = parse_xml(xml);
        assert_eq!(m.clip_name, Some("A001C001_220807".to_string()));
        assert_eq!(m.scene, Some("1".to_string()));
        assert_eq!(m.take, Some("3".to_string()));
        assert_eq!(m.nd_filter, Some("1/4".to_string()));
        assert!((m.iris_f_number.unwrap_or(0.0) - 2.8).abs() < 0.01);
        assert_eq!(m.lut_name, Some("Venice_Look1.cube".to_string()));
    }

    #[test]
    fn empty_when_no_sidecar() {
        let m = parse_xml("");
        assert!(m.is_empty());
    }
}
