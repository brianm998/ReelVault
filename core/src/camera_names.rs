// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

//! Camera internal-name → marketing-name mapping.
//!
//! Cameras report a wide range of internal/model strings in their EXIF /
//! QuickTime metadata. FFprobe surfaces whatever's there:
//!
//! ```text
//! make:  "SONY"          model: "ILCE-7RM3"
//! make:  "NIKON CORPORATION"   model: "NIKON Z 8"
//! make:  "Panasonic"     model: "DC-S5M2"
//! make:  "FUJIFILM"      model: "X-T5"
//! ```
//!
//! [`crate::metadata::combine_make_model`] concatenates these into
//! a single human-readable string like `"SONY ILCE-7RM3"`. That string is
//! what we store in the catalog's `metadata.camera_model` column.
//!
//! For browsing purposes the **marketing** name (`Sony a7R III`) reads much
//! better than the internal model code (`SONY ILCE-7RM3`). This module
//! holds a hand-curated lookup from the latter to the former, focused on
//! the manufacturers where the divergence is largest (Sony, Nikon,
//! Panasonic, Fujifilm, Blackmagic). Canon's marketing names usually
//! match what's recorded (`Canon EOS R5`), so we only remap the older
//! 5D-series bodies that drop the "EOS" prefix in marketing material.
//!
//! Lookup is case-insensitive on the input but the returned marketing
//! name is the curated string verbatim.
//!
//! Custom mappings: users can extend (and override) the built-in table
//! via the Camera Names editor in either client. Those entries are
//! stored per-catalog in the `config` table as a JSON blob and merged
//! over the built-in MAPPINGS at lookup time — see
//! [`marketing_name_for_with_custom`].

use std::collections::HashMap;

/// Look up a marketing-friendly name for a camera's internal model code.
///
/// Returns `None` when no mapping is known — callers should then fall
/// back to displaying the internal name unchanged.
///
/// The lookup is case-insensitive and tolerant of whitespace variation:
/// `"SONY ILCE-7RM3"`, `"sony ilce-7rm3"`, and `"SONY  ILCE-7RM3"`
/// (double space) all resolve to the same entry.
pub fn marketing_name_for(internal: &str) -> Option<&'static str> {
    let key = normalise(internal);
    if key.is_empty() {
        return None;
    }
    MAPPINGS
        .iter()
        .find(|(k, _)| *k == key.as_str())
        .map(|(_, v)| *v)
}

/// Same as [`marketing_name_for`] but consults a user-supplied override
/// map first. Map keys must already be normalised (see [`normalise`]);
/// build them via [`build_custom_overrides`].
///
/// Override semantics:
/// - A key present in `custom_overrides` *replaces* any built-in entry
///   for the same internal name. This lets users correct a built-in
///   mapping they disagree with as well as add brand-new ones.
/// - Returns the override string verbatim — callers can render that
///   directly as the marketing name.
pub fn marketing_name_for_with_custom(
    internal: &str,
    custom_overrides: &HashMap<String, String>,
) -> Option<String> {
    let key = normalise(internal);
    if key.is_empty() {
        return None;
    }
    if let Some(custom) = custom_overrides.get(&key) {
        return Some(custom.clone());
    }
    MAPPINGS
        .iter()
        .find(|(k, _)| *k == key.as_str())
        .map(|(_, v)| v.to_string())
}

/// Build a normalised-key override map from an arbitrary
/// `(internal, marketing)` iterator. Internal-side keys are passed
/// through [`normalise`] so callers don't need to know about the
/// canonical form. Empty / blank marketing values are dropped — they
/// represent "no override" rather than "set the marketing name to the
/// empty string".
pub fn build_custom_overrides<I, S1, S2>(entries: I) -> HashMap<String, String>
where
    I: IntoIterator<Item = (S1, S2)>,
    S1: AsRef<str>,
    S2: AsRef<str>,
{
    let mut out = HashMap::new();
    for (internal, marketing) in entries {
        let key = normalise(internal.as_ref());
        let value = marketing.as_ref().trim();
        if key.is_empty() || value.is_empty() {
            continue;
        }
        out.insert(key, value.to_string());
    }
    out
}

/// Return the entire built-in mapping table as `(internal, marketing)`
/// pairs in the order they're declared. Used by the
/// `ListCameraNameMappings` RPC so clients can render the curated list
/// alongside any custom overrides the user has added.
pub fn builtin_entries() -> impl Iterator<Item = (&'static str, &'static str)> {
    MAPPINGS.iter().copied()
}

/// Strip extraneous whitespace and uppercase the input so different
/// equivalent representations of the same camera all collide on the
/// same map key. Public so callers can construct override maps with
/// matching keys.
pub fn normalise(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut last_space = true;
    for ch in s.chars() {
        if ch.is_whitespace() {
            if !last_space {
                out.push(' ');
                last_space = true;
            }
        } else {
            out.push(ch.to_ascii_uppercase());
            last_space = false;
        }
    }
    while out.ends_with(' ') {
        out.pop();
    }
    out
}

// Entries are stored uppercase + single-spaced to match `normalise(...)`.
// Add new mappings here as users surface them — the table is the only
// source of truth for both clients via the gRPC `camera_display_name`
// field.
//
// When a new camera is discovered without a marketing mapping, the
// clients show the raw internal name; users can request it be added.
#[rustfmt::skip]
const MAPPINGS: &[(&str, &str)] = &[
    // ---------------------------------------------------------------
    // Sony — Alpha mirrorless. `ILCE-` = "Interchangeable Lens Camera
    // E-mount"; users universally know them as `a7…`, `a9…`, `a1`,
    // `a6…`, etc.
    // ---------------------------------------------------------------
    ("SONY ILCE-7",        "Sony a7"),
    ("SONY ILCE-7M2",      "Sony a7 II"),
    ("SONY ILCE-7M3",      "Sony a7 III"),
    ("SONY ILCE-7M4",      "Sony a7 IV"),
    ("SONY ILCE-7R",       "Sony a7R"),
    ("SONY ILCE-7RM2",     "Sony a7R II"),
    ("SONY ILCE-7RM3",     "Sony a7R III"),
    // ILCE-7RM3A is the silently-refreshed body Sony shipped in late
    // 2021 with a faster processor and the higher-res EVF/screen from
    // the IV. Sony's own marketing calls it "α7R IIIA" (lower-case
    // alpha in shop pages, capital R as always). Keep "Sony a7R IIIA"
    // to stay consistent with the rest of the Sony table.
    ("SONY ILCE-7RM3A",    "Sony a7R IIIA"),
    ("SONY ILCE-7RM4",     "Sony a7R IV"),
    // Same story for the a7R IVA refresh.
    ("SONY ILCE-7RM4A",    "Sony a7R IVA"),
    ("SONY ILCE-7RM5",     "Sony a7R V"),
    ("SONY ILCE-7S",       "Sony a7S"),
    ("SONY ILCE-7SM2",     "Sony a7S II"),
    ("SONY ILCE-7SM3",     "Sony a7S III"),
    ("SONY ILCE-7C",       "Sony a7C"),
    ("SONY ILCE-7CM2",     "Sony a7C II"),
    ("SONY ILCE-7CR",      "Sony a7CR"),
    ("SONY ILCE-9",        "Sony a9"),
    ("SONY ILCE-9M2",      "Sony a9 II"),
    ("SONY ILCE-9M3",      "Sony a9 III"),
    ("SONY ILCE-1",        "Sony a1"),
    ("SONY ILCE-1M2",      "Sony a1 II"),
    // Sony APS-C
    ("SONY ILCE-6000",     "Sony a6000"),
    ("SONY ILCE-6100",     "Sony a6100"),
    ("SONY ILCE-6300",     "Sony a6300"),
    ("SONY ILCE-6400",     "Sony a6400"),
    ("SONY ILCE-6500",     "Sony a6500"),
    ("SONY ILCE-6600",     "Sony a6600"),
    ("SONY ILCE-6700",     "Sony a6700"),
    ("SONY ILCE-5000",     "Sony a5000"),
    ("SONY ILCE-5100",     "Sony a5100"),
    // Sony vlogging / compacts
    ("SONY ZV-1",          "Sony ZV-1"),
    ("SONY ZV-1F",         "Sony ZV-1F"),
    ("SONY ZV-1M2",        "Sony ZV-1 II"),
    ("SONY ZV-E10",        "Sony ZV-E10"),
    ("SONY ZV-E10M2",      "Sony ZV-E10 II"),
    ("SONY ZV-E1",         "Sony ZV-E1"),
    // Sony Cinema Line
    ("SONY ILME-FX3",      "Sony FX3"),
    ("SONY ILME-FX30",     "Sony FX30"),
    ("SONY ILME-FX6",      "Sony FX6"),
    ("SONY ILME-FX9",      "Sony FX9"),
    // Sony RX-series
    ("SONY DSC-RX100",     "Sony RX100"),
    ("SONY DSC-RX100M2",   "Sony RX100 II"),
    ("SONY DSC-RX100M3",   "Sony RX100 III"),
    ("SONY DSC-RX100M4",   "Sony RX100 IV"),
    ("SONY DSC-RX100M5",   "Sony RX100 V"),
    ("SONY DSC-RX100M5A",  "Sony RX100 VA"),
    ("SONY DSC-RX100M6",   "Sony RX100 VI"),
    ("SONY DSC-RX100M7",   "Sony RX100 VII"),

    // ---------------------------------------------------------------
    // Nikon — Z mirrorless drops the space in marketing, and the body
    // suffixes ("_2") map to roman-numeral generations.
    // ---------------------------------------------------------------
    ("NIKON CORPORATION NIKON Z 5",       "Nikon Z5"),
    ("NIKON CORPORATION NIKON Z 5_2",     "Nikon Z5 II"),
    ("NIKON CORPORATION NIKON Z 6",       "Nikon Z6"),
    ("NIKON CORPORATION NIKON Z 6_2",     "Nikon Z6 II"),
    ("NIKON CORPORATION NIKON Z 6_3",     "Nikon Z6 III"),
    ("NIKON CORPORATION NIKON Z 7",       "Nikon Z7"),
    ("NIKON CORPORATION NIKON Z 7_2",     "Nikon Z7 II"),
    ("NIKON CORPORATION NIKON Z 8",       "Nikon Z8"),
    ("NIKON CORPORATION NIKON Z 9",       "Nikon Z9"),
    ("NIKON CORPORATION NIKON Z 30",      "Nikon Z30"),
    ("NIKON CORPORATION NIKON Z 50",      "Nikon Z50"),
    ("NIKON CORPORATION NIKON Z FC",      "Nikon Z fc"),
    ("NIKON CORPORATION NIKON Z F",       "Nikon Zf"),
    // The shorter "NIKON" prefix some bodies emit
    ("NIKON Z 8",                         "Nikon Z8"),
    ("NIKON Z 9",                         "Nikon Z9"),
    ("NIKON Z 6_3",                       "Nikon Z6 III"),
    // Nikon DSLRs — the names are usually fine as-is, but
    // double-spaces sometimes show up.
    ("NIKON CORPORATION NIKON D6",        "Nikon D6"),
    ("NIKON CORPORATION NIKON D850",      "Nikon D850"),
    ("NIKON CORPORATION NIKON D780",      "Nikon D780"),
    ("NIKON CORPORATION NIKON D750",      "Nikon D750"),

    // ---------------------------------------------------------------
    // Panasonic / Lumix — `DC-S5M2` → `Lumix S5 II`, and so on.
    // ---------------------------------------------------------------
    ("PANASONIC DC-S5",     "Panasonic Lumix S5"),
    ("PANASONIC DC-S5M2",   "Panasonic Lumix S5 II"),
    ("PANASONIC DC-S5M2X",  "Panasonic Lumix S5 IIX"),
    ("PANASONIC DC-S1",     "Panasonic Lumix S1"),
    ("PANASONIC DC-S1H",    "Panasonic Lumix S1H"),
    ("PANASONIC DC-S1R",    "Panasonic Lumix S1R"),
    ("PANASONIC DC-S9",     "Panasonic Lumix S9"),
    ("PANASONIC DC-GH5",    "Panasonic Lumix GH5"),
    ("PANASONIC DC-GH5S",   "Panasonic Lumix GH5S"),
    ("PANASONIC DC-GH5M2",  "Panasonic Lumix GH5 II"),
    ("PANASONIC DC-GH6",    "Panasonic Lumix GH6"),
    ("PANASONIC DC-GH7",    "Panasonic Lumix GH7"),
    ("PANASONIC DC-G9",     "Panasonic Lumix G9"),
    ("PANASONIC DC-G9M2",   "Panasonic Lumix G9 II"),
    ("PANASONIC DC-BGH1",   "Panasonic Lumix BGH1"),
    ("PANASONIC DC-BS1H",   "Panasonic Lumix BS1H"),

    // ---------------------------------------------------------------
    // Fujifilm — the model itself is usually fine; we just normalise
    // the brand and add the brand prefix the body omits.
    // ---------------------------------------------------------------
    ("FUJIFILM X-H2",        "Fujifilm X-H2"),
    ("FUJIFILM X-H2S",       "Fujifilm X-H2S"),
    ("FUJIFILM X-T5",        "Fujifilm X-T5"),
    ("FUJIFILM X-T4",        "Fujifilm X-T4"),
    ("FUJIFILM X-T3",        "Fujifilm X-T3"),
    ("FUJIFILM X-T2",        "Fujifilm X-T2"),
    ("FUJIFILM X-S10",       "Fujifilm X-S10"),
    ("FUJIFILM X-S20",       "Fujifilm X-S20"),
    ("FUJIFILM X-PRO3",      "Fujifilm X-Pro3"),
    ("FUJIFILM X-PRO2",      "Fujifilm X-Pro2"),
    ("FUJIFILM X100V",       "Fujifilm X100V"),
    ("FUJIFILM X100VI",      "Fujifilm X100VI"),
    ("FUJIFILM GFX100",      "Fujifilm GFX 100"),
    ("FUJIFILM GFX100S",     "Fujifilm GFX 100S"),
    ("FUJIFILM GFX100 II",   "Fujifilm GFX 100 II"),
    ("FUJIFILM GFX 50S",     "Fujifilm GFX 50S"),
    ("FUJIFILM GFX 50S II",  "Fujifilm GFX 50S II"),
    ("FUJIFILM GFX 50R",     "Fujifilm GFX 50R"),

    // ---------------------------------------------------------------
    // Blackmagic — the internal `BMPCC…` slugs are widely recognised
    // as the actual marketing name on YouTube, but Blackmagic's
    // website spells the products out, so we expand here.
    // ---------------------------------------------------------------
    ("BLACKMAGIC DESIGN BMPCC4K",    "Blackmagic Pocket Cinema Camera 4K"),
    ("BLACKMAGIC DESIGN BMPCC6K",    "Blackmagic Pocket Cinema Camera 6K"),
    ("BLACKMAGIC DESIGN BMPCC6KPRO", "Blackmagic Pocket Cinema Camera 6K Pro"),
    ("BLACKMAGIC DESIGN BMPCC6KG2",  "Blackmagic Pocket Cinema Camera 6K G2"),
    ("BLACKMAGIC DESIGN BMCC",       "Blackmagic Cinema Camera 6K"),
    ("BLACKMAGIC DESIGN URSA MINI PRO",     "Blackmagic URSA Mini Pro"),
    ("BLACKMAGIC DESIGN URSA MINI PRO 4.6K G2",
        "Blackmagic URSA Mini Pro 4.6K G2"),
    ("BLACKMAGIC DESIGN URSA MINI PRO 12K", "Blackmagic URSA Mini Pro 12K"),

    // ---------------------------------------------------------------
    // Canon — most modern bodies marketing-match their EXIF strings,
    // so we mostly leave them alone. A handful of older 5D-series
    // names drop "EOS" in marketing material; include those.
    // ---------------------------------------------------------------
    ("CANON EOS 5D MARK II",  "Canon 5D Mark II"),
    ("CANON EOS 5D MARK III", "Canon 5D Mark III"),
    ("CANON EOS 5D MARK IV",  "Canon 5D Mark IV"),
    ("CANON EOS-1D X MARK II",  "Canon 1DX Mark II"),
    ("CANON EOS-1D X MARK III", "Canon 1DX Mark III"),

    // ---------------------------------------------------------------
    // GoPro — the wearable label is typically what users say.
    // ---------------------------------------------------------------
    ("GOPRO HERO9 BLACK",  "GoPro Hero9"),
    ("GOPRO HERO10 BLACK", "GoPro Hero10"),
    ("GOPRO HERO11 BLACK", "GoPro Hero11"),
    ("GOPRO HERO12 BLACK", "GoPro Hero12"),
    ("GOPRO HERO13 BLACK", "GoPro Hero13"),

    // ---------------------------------------------------------------
    // DJI — selected drones with widely-recognised marketing names.
    // ---------------------------------------------------------------
    ("DJI OSMO POCKET 3", "DJI Osmo Pocket 3"),
    ("DJI POCKET 2",      "DJI Pocket 2"),
];

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sony_a7riii_resolves() {
        assert_eq!(marketing_name_for("SONY ILCE-7RM3"), Some("Sony a7R III"));
    }

    #[test]
    fn lookup_is_case_insensitive() {
        assert_eq!(marketing_name_for("sony ilce-7rm3"), Some("Sony a7R III"));
        assert_eq!(marketing_name_for("Sony ilce-7RM3"), Some("Sony a7R III"));
    }

    #[test]
    fn extra_whitespace_collapses() {
        assert_eq!(
            marketing_name_for("SONY  ILCE-7RM3"),
            Some("Sony a7R III"),
            "double-space between make and model should still match"
        );
        assert_eq!(
            marketing_name_for("  SONY ILCE-7RM3  "),
            Some("Sony a7R III"),
            "leading/trailing whitespace should be ignored"
        );
    }

    #[test]
    fn unknown_camera_returns_none() {
        assert_eq!(marketing_name_for("SOMETHING UNKNOWN"), None);
        assert_eq!(marketing_name_for(""), None);
    }

    #[test]
    fn nikon_z8_matches_full_make_prefix() {
        assert_eq!(
            marketing_name_for("NIKON CORPORATION NIKON Z 8"),
            Some("Nikon Z8")
        );
    }

    #[test]
    fn nikon_z8_matches_short_prefix() {
        assert_eq!(marketing_name_for("NIKON Z 8"), Some("Nikon Z8"));
    }

    #[test]
    fn panasonic_s5ii_resolves() {
        assert_eq!(
            marketing_name_for("Panasonic DC-S5M2"),
            Some("Panasonic Lumix S5 II")
        );
    }

    #[test]
    fn sony_a7riii_a_refresh_resolves() {
        assert_eq!(
            marketing_name_for("SONY ILCE-7RM3A"),
            Some("Sony a7R IIIA")
        );
        // The user's bug report came in lowercase — make sure the
        // case-insensitive lookup catches that too.
        assert_eq!(
            marketing_name_for("sony ilce-7rm3a"),
            Some("Sony a7R IIIA")
        );
    }

    #[test]
    fn custom_override_replaces_builtin() {
        let custom = build_custom_overrides([
            ("SONY ILCE-7RM3", "My a7riii"),
        ]);
        assert_eq!(
            marketing_name_for_with_custom("SONY ILCE-7RM3", &custom),
            Some("My a7riii".to_string()),
            "custom override should win over the curated built-in"
        );
    }

    #[test]
    fn custom_falls_through_to_builtin() {
        // Custom map has *no* override for this body — built-in wins.
        let custom = build_custom_overrides(std::iter::empty::<(&str, &str)>());
        assert_eq!(
            marketing_name_for_with_custom("SONY ILCE-7RM3", &custom),
            Some("Sony a7R III".to_string())
        );
    }

    #[test]
    fn custom_can_introduce_brand_new_camera() {
        let custom = build_custom_overrides([
            ("HASSELBLAD X2D 100C", "Hasselblad X2D"),
        ]);
        assert_eq!(
            marketing_name_for_with_custom("hasselblad  X2D 100c", &custom),
            Some("Hasselblad X2D".to_string()),
            "custom map should normalise its keys the same way \
             lookup normalises inputs, so users don't have to \
             match whitespace / case exactly"
        );
    }

    #[test]
    fn empty_marketing_in_custom_drops_the_entry() {
        // A blank marketing value means "no override" — i.e. it
        // should not silently shadow the built-in.
        let custom = build_custom_overrides([
            ("SONY ILCE-7RM3", "   "),
        ]);
        assert_eq!(
            marketing_name_for_with_custom("SONY ILCE-7RM3", &custom),
            Some("Sony a7R III".to_string())
        );
    }
}
