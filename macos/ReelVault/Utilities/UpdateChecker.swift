// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation

struct ReleaseInfo {
    let version: String
    let releaseUrl: String
    let tagName: String
}

enum UpdateChecker {

    static func checkLatestRelease(owner: String, repo: String) async -> ReleaseInfo? {
        guard let url = URL(string: "https://api.github.com/repos/\(owner)/\(repo)/releases/latest")
        else { return nil }

        var request = URLRequest(url: url, timeoutInterval: 10)
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        request.setValue("ReelVault/\(AppVersion.current)", forHTTPHeaderField: "User-Agent")

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse, http.statusCode == 200 else { return nil }
            guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let tagName  = json["tag_name"]  as? String,
                  let htmlUrl  = json["html_url"]  as? String
            else { return nil }
            let version = tagName.hasPrefix("v") ? String(tagName.dropFirst()) : tagName
            return ReleaseInfo(version: version, releaseUrl: htmlUrl, tagName: tagName)
        } catch {
            return nil
        }
    }

    /// Returns true when `latest` is strictly newer than `current` (semver comparison).
    static func isNewer(_ latest: String, than current: String) -> Bool {
        func parts(_ v: String) -> [Int] { v.split(separator: ".").map { Int($0) ?? 0 } }
        let l = parts(latest), c = parts(current)
        let len = max(l.count, c.count)
        for i in 0..<len {
            let lv = i < l.count ? l[i] : 0
            let cv = i < c.count ? c[i] : 0
            if lv > cv { return true }
            if lv < cv { return false }
        }
        return false
    }
}
