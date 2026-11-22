// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation

/// Snapshot of the filters a client can evaluate locally against a
/// `VideoSummary`. Mirrors Kotlin's `LocalFilterState` (shared/); both are
/// driven by `testdata/local_filter.json`.
public struct LocalFilterState: Sendable {
    public var hasLocation: AttributeFilterState = .any
    public var hasKeywords: AttributeFilterState = .any
    public var hasProxies: AttributeFilterState = .any
    public var fullResolution: AttributeFilterState = .any
    public var hasAudio: AttributeFilterState = .any
    public var orientation: OrientationFilterState = .any
    public var minRating: Int32 = 0
    public var colorLabel: String = ""
    public var location: GeoFilter? = nil

    public init(
        hasLocation: AttributeFilterState = .any, hasKeywords: AttributeFilterState = .any,
        hasProxies: AttributeFilterState = .any, fullResolution: AttributeFilterState = .any,
        hasAudio: AttributeFilterState = .any, orientation: OrientationFilterState = .any,
        minRating: Int32 = 0, colorLabel: String = "", location: GeoFilter? = nil
    ) {
        self.hasLocation = hasLocation; self.hasKeywords = hasKeywords
        self.hasProxies = hasProxies; self.fullResolution = fullResolution
        self.hasAudio = hasAudio; self.orientation = orientation
        self.minRating = minRating; self.colorLabel = colorLabel; self.location = location
    }
}

extension VideoSummary {
    /// Does this summary satisfy the attribute + Lightroom-mark filters we can
    /// evaluate client-side? Mirrors the daemon's `build_filter_clauses`
    /// (core/src/db.rs) for exactly those filters. `true` means "not filtered
    /// out by anything we can see locally".
    public func matchesLocalFilters(_ f: LocalFilterState) -> Bool {
        let s = self
        switch f.hasLocation {
        case .yes: if !s.hasLocation { return false }
        case .no:  if s.hasLocation  { return false }
        case .any: break
        }
        switch f.hasKeywords {
        case .yes: if s.tags.isEmpty  { return false }
        case .no:  if !s.tags.isEmpty { return false }
        case .any: break
        }
        switch f.hasProxies {
        case .yes: if !s.hasProxies { return false }
        case .no:  if s.hasProxies  { return false }
        case .any: break
        }
        // The daemon classifies "full resolution" via a (camera, w, h) tuple
        // set; the summary's already-classified status is the client-side
        // mirror, so `.full` ⇔ in that set and anything else (incl.
        // `.unspecified`) counts as "not full".
        switch f.fullResolution {
        case .yes: if s.fullResolution != .full { return false }
        case .no:  if s.fullResolution == .full { return false }
        case .any: break
        }
        switch f.hasAudio {
        case .yes: if !s.hasAudio { return false }
        case .no:  if s.hasAudio  { return false }
        case .any: break
        }
        // portrait ⇔ height > width; landscape ⇔ has real dims and width ≥ height.
        switch f.orientation {
        case .portrait:  if !s.isPortrait  { return false }
        case .landscape: if !s.isLandscape { return false }
        case .any: break
        }
        if f.minRating > 0 && Int32(s.rating) < f.minRating { return false }
        if !f.colorLabel.isEmpty && s.colorLabel != f.colorLabel { return false }
        // Geo/area filter: the daemon's bounding-box test.
        // 1° lat ≈ 111 km; 1° lon ≈ 111·cos(lat) km.
        if let geo = f.location {
            guard s.hasLocation else { return false }
            let latDelta = abs(geo.radiusKm / 111.0)
            let cosLat = max(0.01, abs(cos(geo.latitude * .pi / 180.0)))
            let lonDelta = abs(geo.radiusKm / (111.0 * cosLat))
            if s.gpsLatitude < geo.latitude - latDelta || s.gpsLatitude > geo.latitude + latDelta { return false }
            if s.gpsLongitude < geo.longitude - lonDelta || s.gpsLongitude > geo.longitude + lonDelta { return false }
        }
        return true
    }
}

/// The wire metadata filters that encode the has-audio and orientation
/// attribute choices. Appended to the column-derived filters in every
/// ListVideos request path so the grid and smart-collection counts agree.
public func derivedAttributeMetadataFilters(
    hasAudio: AttributeFilterState, orientation: OrientationFilterState
) -> [(key: String, value: String)] {
    var out: [(key: String, value: String)] = []
    switch hasAudio {
    case .yes: out.append((key: "has_audio", value: "yes"))
    case .no:  out.append((key: "has_audio", value: "no"))
    case .any: break
    }
    switch orientation {
    case .portrait:  out.append((key: "orientation", value: "portrait"))
    case .landscape: out.append((key: "orientation", value: "landscape"))
    case .any: break
    }
    return out
}

/// The wire `MetadataFilter.value` for a column's selected values: joined by
/// `metadataValueSeparator`, prefixed with `metadataNegatePrefix` when negated.
public func metadataWireValue(values: [String], negate: Bool) -> String {
    let joined = values.joined(separator: metadataValueSeparator)
    return negate ? metadataNegatePrefix + joined : joined
}

extension MetadataColumn {
    /// Wire encoding of this column (values sorted for a stable order, as the
    /// view-model does when building requests).
    public var wireValue: String { metadataWireValue(values: values.sorted(), negate: negate) }
}

extension SmartCollectionColumn {
    public var wireValue: String { metadataWireValue(values: values, negate: negate) }
}
