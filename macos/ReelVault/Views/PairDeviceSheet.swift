// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// "Pair a New Device" sheet. Asks the connected daemon to mint a one-time
/// 6-digit code (over the loopback gRPC connection) and displays it for the
/// user to type on a remote device (the iOS app). The device redeems the code
/// at the daemon's pairing endpoint for a long-lived token.
struct PairDeviceSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var phase: Phase = .loading

    private enum Phase {
        case loading
        case ready(VideoRepository.PairingCode)
        case failed
    }

    var body: some View {
        VStack(spacing: 18) {
            Text("Pair a New Device")
                .font(.title2).bold()

            switch phase {
            case .loading:
                ProgressView("Generating code…")
                    .frame(maxWidth: .infinity, minHeight: 120)

            case .failed:
                VStack(spacing: 10) {
                    Image(systemName: "exclamationmark.triangle")
                        .font(.largeTitle).foregroundStyle(.secondary)
                    Text("Couldn't generate a pairing code. Make sure a ReelVault catalog is open and the daemon is running.")
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.secondary)
                    Button("Try Again") { Task { await load() } }
                }
                .frame(maxWidth: .infinity, minHeight: 120)

            case .ready(let pairing):
                Text("On your iPhone or iPad, open ReelVault, choose this computer's server, then enter:")
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
                Text(spaced(pairing.code))
                    .font(.system(size: 44, weight: .bold, design: .monospaced))
                    .textSelection(.enabled)
                CountdownLabel(expiresAtMs: pairing.expiresAtMs)
            }

            Button("Done") { dismiss() }
                .keyboardShortcut(.defaultAction)
        }
        .padding(28)
        .frame(width: 380)
        .task { await load() }
    }

    private func load() async {
        phase = .loading
        if let code = await VideoRepository.shared.startPairing() {
            phase = .ready(code)
        } else {
            phase = .failed
        }
    }

    /// "123456" → "123 456" for legibility.
    private func spaced(_ code: String) -> String {
        guard code.count == 6 else { return code }
        let i = code.index(code.startIndex, offsetBy: 3)
        return "\(code[..<i]) \(code[i...])"
    }
}

/// Live "Expires in m:ss" countdown driven off the code's wall-clock expiry.
private struct CountdownLabel: View {
    let expiresAtMs: Int64

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let remaining = Int(expiresAtMs / 1000 - Int64(context.date.timeIntervalSince1970))
            if remaining > 0 {
                Text("Expires in \(remaining / 60):\(String(format: "%02d", remaining % 60))")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            } else {
                Text("Code expired — close and try again.")
                    .font(.callout)
                    .foregroundStyle(.red)
            }
        }
    }
}
