// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit
import AppKit

struct ConnectionErrorView: View {
    let errorMessage: String
    var onRetry: () -> Void = {}
    /// When set, shows a "Choose a different server…" button — clears any saved
    /// default and re-runs discovery, so a stale/unreachable remote default can't
    /// trap the user on this screen.
    var onChooseServer: (() -> Void)? = nil

    var body: some View {
        VStack(spacing: 24) {
            Image(systemName: "exclamationmark.icloud.fill")
                .font(.system(size: 64))
                .foregroundColor(.red)

            VStack(spacing: 8) {
                Text("Connection Error")
                    .font(.title2)
                    .fontWeight(.semibold)

                Text(errorMessage)
                    .font(.body)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            }

            VStack(alignment: .leading, spacing: 16) {
                Text("Make sure the ReelVault backend is running:")
                    .font(.body)
                    .foregroundColor(.secondary)

                VStack(alignment: .leading, spacing: 4) {
                    Text("cd core && cargo run --bin reelvault-core")
                        .font(.system(.body, design: .monospaced))
                        .padding(12)
                        .background(Color(.controlBackgroundColor))
                        .overlay(RoundedRectangle(cornerRadius: 4).stroke(Color(.separatorColor)))
                        .cornerRadius(4)

                    Button(action: {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(
                            "cd core && cargo run --bin reelvault-core",
                            forType: .string
                        )
                    }) {
                        Text("Copy Command")
                            .font(.caption)
                    }
                    .buttonStyle(.bordered)
                    .help("Copy the command to your clipboard so you can paste it into a terminal.")
                }
            }
            .frame(maxWidth: 400)

            HStack(spacing: 12) {
                Button {
                    onRetry()
                } label: {
                    HStack {
                        Image(systemName: "arrow.clockwise")
                        Text("Retry")
                    }
                }
                .buttonStyle(.borderedProminent)
                .help("Try connecting to the ReelVault backend daemon again.")

                if let onChooseServer {
                    Button {
                        onChooseServer()
                    } label: {
                        HStack {
                            Image(systemName: "rectangle.connected.to.line.below")
                            Text("Choose a Different Server…")
                        }
                    }
                    .buttonStyle(.bordered)
                    .help("Forget the saved default and scan for ReelVault servers again.")
                }
            }

            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(32)
        .background(Color(.windowBackgroundColor))
    }
}

#Preview {
    ConnectionErrorView(errorMessage: "Failed to connect to ReelVault backend on localhost:50051")
}
