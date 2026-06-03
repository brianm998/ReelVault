// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

//! Registry of the metadata "keys" the Library Filter can facet and filter on.
//!
//! This is the single source of truth that ties a wire token (e.g. `"iso"`)
//! to (a) a human-readable display name, (b) how to match it in SQL, (c) how
//! to enumerate its distinct values, and (d) how to format a raw value into a
//! user-facing string. Both the faceting queries (`db::distinct_facet_values`,
//! `db::metadata_keys_with_data`) and the generic metadata filter in
//! `db::list_videos_grouped` go through here, so adding a new filterable field
//! is a one-line registry change.
//!
//! Phase note: today every key is backed by a column the core already
//! populates (camera / lens / codec / year + the XMP-EXIF numerics) or by the
//! tag tables (keyword). Arbitrary XMP keys can be added later by extending
//! [`KEYS`] (and, if they aren't real columns, the SQL the [`KeyKind`]
//! variants generate) without touching either client.

/// How a metadata key maps onto storage and display.
pub enum KeyKind {
    /// A TEXT column on `metadata`. Token == raw value == display.
    Text(&'static str),
    /// An INTEGER column on `metadata`, displayed as a plain number.
    Int(&'static str),
    /// A REAL column on `metadata`, displayed via `fmt`.
    Real { col: &'static str, fmt: RealFmt },
    /// Capture year, derived from `metadata.creation_date` (Unix ms).
    Year,
    /// A keyword / tag. Token is the tag id; display is the tag name. Backed by
    /// the `tags` / `video_tags` tables, so it's handled specially by the query
    /// builders rather than as a `metadata` column.
    Keyword,
}

/// Display formatting for a REAL-valued key.
#[derive(Clone, Copy)]
pub enum RealFmt {
    /// Aperture f-number: `1.8 -> "f/1.8"`.
    FStop,
    /// Shutter speed in seconds: `0.00025 -> "1/4000 s"`, `2.0 -> "2 s"`.
    Shutter,
    /// Focal length in millimetres: `35.0 -> "35 mm"`.
    Millimeters,
}

/// One filterable / facetable metadata key.
pub struct MetaKey {
    /// Canonical wire token (stable; sent over gRPC as `MetadataFilter.key`).
    pub token: &'static str,
    /// Human-readable column title.
    pub display_name: &'static str,
    /// Whether values sort / format as numbers (a hint for the clients).
    pub is_numeric: bool,
    pub kind: KeyKind,
}

/// A typed value bound into a metadata predicate.
pub enum SqlVal {
    Text(String),
    Int(i64),
    Real(f64),
}

/// The registry. Order is the order the clients show keys in the picker.
pub static KEYS: &[MetaKey] = &[
    MetaKey { token: "camera", display_name: "Camera", is_numeric: false, kind: KeyKind::Text("camera_model") },
    MetaKey { token: "lens", display_name: "Lens", is_numeric: false, kind: KeyKind::Text("lens_model") },
    MetaKey { token: "codec", display_name: "Codec", is_numeric: false, kind: KeyKind::Text("codec_video") },
    MetaKey { token: "year", display_name: "Year", is_numeric: true, kind: KeyKind::Year },
    MetaKey { token: "keyword", display_name: "Keyword", is_numeric: false, kind: KeyKind::Keyword },
    MetaKey { token: "iso", display_name: "ISO", is_numeric: true, kind: KeyKind::Int("iso") },
    MetaKey { token: "aperture", display_name: "Aperture", is_numeric: true, kind: KeyKind::Real { col: "aperture", fmt: RealFmt::FStop } },
    MetaKey { token: "exposure", display_name: "Exposure", is_numeric: true, kind: KeyKind::Real { col: "exposure_time_s", fmt: RealFmt::Shutter } },
    MetaKey { token: "focal_length", display_name: "Focal Length", is_numeric: true, kind: KeyKind::Real { col: "focal_length_mm", fmt: RealFmt::Millimeters } },
];

/// Look up a key by its canonical wire token.
pub fn lookup(token: &str) -> Option<&'static MetaKey> {
    KEYS.iter().find(|k| k.token == token)
}

impl MetaKey {
    /// SQL boolean fragment matching this key against a single `?` bind.
    /// `None` for [`KeyKind::Keyword`] (handled via a tag subquery by the
    /// caller).
    pub fn predicate_sql(&self) -> Option<String> {
        match &self.kind {
            KeyKind::Text(col) | KeyKind::Int(col) => Some(format!("m.{col} = ?")),
            KeyKind::Real { col, .. } => Some(format!("m.{col} = ?")),
            KeyKind::Year => {
                Some("CAST(strftime('%Y', m.creation_date / 1000, 'unixepoch') AS INTEGER) = ?".to_string())
            }
            KeyKind::Keyword => None,
        }
    }

    /// Parse a wire token into the typed value for [`Self::predicate_sql`]'s
    /// bind. `None` for [`KeyKind::Keyword`], or when the token doesn't parse
    /// as the expected type.
    pub fn parse_value(&self, token: &str) -> Option<SqlVal> {
        match &self.kind {
            KeyKind::Text(_) => Some(SqlVal::Text(token.to_string())),
            KeyKind::Int(_) | KeyKind::Year => token.parse::<i64>().ok().map(SqlVal::Int),
            KeyKind::Real { .. } => token.parse::<f64>().ok().map(SqlVal::Real),
            KeyKind::Keyword => None,
        }
    }

    /// SQL scalar expression yielding this key's distinct value (for facets).
    /// `None` for [`KeyKind::Keyword`].
    pub fn distinct_expr(&self) -> Option<String> {
        match &self.kind {
            KeyKind::Text(col) | KeyKind::Int(col) => Some(format!("m.{col}")),
            KeyKind::Real { col, .. } => Some(format!("m.{col}")),
            KeyKind::Year => {
                Some("CAST(strftime('%Y', m.creation_date / 1000, 'unixepoch') AS INTEGER)".to_string())
            }
            KeyKind::Keyword => None,
        }
    }

    /// "Has a value" guard for distinct / EXISTS queries (excludes NULL/blank).
    /// `None` for [`KeyKind::Keyword`].
    pub fn distinct_guard(&self) -> Option<String> {
        match &self.kind {
            KeyKind::Text(col) => Some(format!("m.{col} IS NOT NULL AND m.{col} != ''")),
            KeyKind::Int(col) => Some(format!("m.{col} IS NOT NULL AND m.{col} > 0")),
            KeyKind::Real { col, .. } => Some(format!("m.{col} IS NOT NULL AND m.{col} > 0")),
            KeyKind::Year => Some("m.creation_date IS NOT NULL AND m.creation_date > 0".to_string()),
            KeyKind::Keyword => None,
        }
    }

    /// Sort direction for distinct values. Years read best newest-first.
    pub fn distinct_order(&self) -> &'static str {
        match &self.kind {
            KeyKind::Year => "DESC",
            _ => "ASC",
        }
    }

    /// Format a token into a user-facing display string. (Camera marketing
    /// names are applied by the service layer, which holds the per-catalog
    /// overrides; everything else is self-contained here.)
    pub fn format_value(&self, token: &str) -> String {
        match &self.kind {
            KeyKind::Real { fmt, .. } => match token.parse::<f64>() {
                Ok(v) => format_real(*fmt, v),
                Err(_) => token.to_string(),
            },
            _ => token.to_string(),
        }
    }
}

fn format_real(fmt: RealFmt, v: f64) -> String {
    match fmt {
        RealFmt::FStop => format!("f/{}", trim(v)),
        RealFmt::Millimeters => format!("{} mm", trim(v)),
        RealFmt::Shutter => {
            if v <= 0.0 {
                trim(v)
            } else if v < 1.0 {
                let denom = (1.0 / v).round() as i64;
                format!("1/{denom} s")
            } else {
                format!("{} s", trim(v))
            }
        }
    }
}

/// Shortest round-trippable decimal for `v` — Rust's `{}` already does this
/// (`2.0 -> "2"`, `1.8 -> "1.8"`), which is also what the facet `token`
/// strings use, so a formatted value parses back to the identical `f64`.
fn trim(v: f64) -> String {
    format!("{v}")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shutter_formats_as_fraction_below_one_second() {
        assert_eq!(format_real(RealFmt::Shutter, 0.00025), "1/4000 s");
        assert_eq!(format_real(RealFmt::Shutter, 0.5), "1/2 s");
        assert_eq!(format_real(RealFmt::Shutter, 2.0), "2 s");
    }

    #[test]
    fn fstop_and_mm_trim_trailing_zeros() {
        assert_eq!(format_real(RealFmt::FStop, 1.8), "f/1.8");
        assert_eq!(format_real(RealFmt::FStop, 2.0), "f/2");
        assert_eq!(format_real(RealFmt::Millimeters, 35.0), "35 mm");
    }

    #[test]
    fn known_tokens_resolve_and_unknown_does_not() {
        assert!(lookup("camera").is_some());
        assert!(lookup("exposure").is_some());
        assert!(lookup("nope").is_none());
    }

    #[test]
    fn numeric_tokens_round_trip_through_format() {
        // The token a facet emits must parse back to a value the predicate
        // can match, and re-formatting it must be stable.
        let ap = lookup("aperture").unwrap();
        assert_eq!(ap.format_value("1.8"), "f/1.8");
        assert!(matches!(ap.parse_value("1.8"), Some(SqlVal::Real(_))));
    }
}
