// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

import SwiftUI

/// Editor for the catalog's camera marketing-name mappings.
///
/// The daemon ships a curated table of internal → marketing mappings
/// (Sony / Nikon / Panasonic / Fujifilm / Blackmagic / …). This view
/// shows that table plus any custom overrides the user has added,
/// and provides a small form for adding new entries or overriding
/// built-in ones.
///
/// Overrides are stored per-catalog and apply to every metadata
/// surface (right-panel detail row, camera filter dropdown, stat
/// slots that show "Camera model"). Setting an empty marketing
/// name on an existing custom row clears the override and reveals
/// the built-in entry again.
struct CameraNamesView: View {
    @Environment(\.dismiss) private var dismiss

    @State private var mappings: [CameraNameMapping] = []
    @State private var isLoading = true
    @State private var errorMessage: String? = nil

    // Add-new form state
    @State private var newInternal: String = ""
    @State private var newMarketing: String = ""

    /// The row the user has tapped a "delete" button on but not yet
    /// confirmed. When non-nil, an alert is presented.
    @State private var pendingDelete: CameraNameMapping? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Header
            HStack(spacing: 8) {
                Image(systemName: "camera.metering.matrix")
                    .font(.title2)
                    .foregroundColor(.accentColor)
                Text("Camera Names")
                    .font(.title2)
                    .fontWeight(.semibold)
                Spacer()
                Button("Done") { dismiss() }
                    .keyboardShortcut(.defaultAction)
            }
            .padding(.horizontal, 16)
            .padding(.top, 16)
            .padding(.bottom, 8)

            Text("VideoRoom maps internal camera codes (the strings recorded in "
                 + "the file's metadata) to marketing-friendly names that the "
                 + "rest of the UI displays. Built-in entries cover the most "
                 + "common bodies; add custom rows below for anything that's "
                 + "missing or override a built-in entry you disagree with. "
                 + "Set a row's marketing name to blank to remove a custom "
                 + "override.")
                .font(.system(size: 11))
                .foregroundColor(.secondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 16)
                .padding(.bottom, 12)

            Divider()

            // Body — scrolling table of (internal, marketing, source)
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    if isLoading {
                        HStack {
                            Spacer()
                            ProgressView().controlSize(.small)
                            Spacer()
                        }
                        .padding(.vertical, 24)
                    } else if mappings.isEmpty {
                        Text("No mappings yet.")
                            .foregroundColor(.secondary)
                            .padding(.vertical, 24)
                            .frame(maxWidth: .infinity, alignment: .center)
                    } else {
                        ForEach(mappings) { mapping in
                            mappingRow(mapping)
                            Divider().opacity(0.4)
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 4)
            }
            .background(Color(.textBackgroundColor).opacity(0.3))

            Divider()

            // Add-new form
            addNewForm
                .padding(.horizontal, 16)
                .padding(.vertical, 12)

            if let err = errorMessage {
                Text(err)
                    .font(.system(size: 11))
                    .foregroundColor(.red)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 8)
            }
        }
        .frame(width: 620, height: 520)
        .task { await reload() }
        .alert(
            "Remove this custom mapping?",
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { if !$0 { pendingDelete = nil } }
            ),
            presenting: pendingDelete
        ) { row in
            Button("Remove", role: .destructive) {
                Task { await deleteMapping(row) }
                pendingDelete = nil
            }
            Button("Cancel", role: .cancel) { pendingDelete = nil }
        } message: { row in
            if row.isBuiltin {
                Text("\"\(row.internalName)\" will fall back to the built-in mapping after removal.")
            } else {
                Text("\"\(row.internalName)\" will fall back to its raw model name after removal.")
            }
        }
    }

    @ViewBuilder
    private func mappingRow(_ mapping: CameraNameMapping) -> some View {
        HStack(alignment: .center, spacing: 10) {
            // Internal name (left): monospaced so part numbers read clean.
            Text(mapping.internalName)
                .font(.system(size: 11, design: .monospaced))
                .foregroundColor(.secondary)
                .lineLimit(1)
                .truncationMode(.tail)
                .frame(width: 220, alignment: .leading)

            // Marketing name (centre): the friendly label.
            Text(mapping.marketingName)
                .font(.system(size: 12))
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)

            // Source badge — "Built-in" / "Custom" / "Custom (override)".
            sourceBadge(for: mapping)

            // Delete button — only present for rows the user can remove.
            // (Built-in-only rows show no button.)
            if mapping.isCustom {
                Button {
                    pendingDelete = mapping
                } label: {
                    Image(systemName: "trash")
                        .font(.system(size: 12))
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.borderless)
                .help(mapping.isBuiltin
                      ? "Remove this custom override and fall back to the built-in mapping."
                      : "Remove this custom mapping.")
            } else {
                // Reserve the same width so columns stay aligned.
                Color.clear.frame(width: 16, height: 16)
            }
        }
        .padding(.vertical, 6)
    }

    @ViewBuilder
    private func sourceBadge(for mapping: CameraNameMapping) -> some View {
        if mapping.isCustom && mapping.isBuiltin {
            Text("Custom (override)")
                .font(.system(size: 9, weight: .medium))
                .padding(.horizontal, 5)
                .padding(.vertical, 2)
                .background(Color.orange.opacity(0.25))
                .foregroundColor(.orange)
                .cornerRadius(3)
        } else if mapping.isCustom {
            Text("Custom")
                .font(.system(size: 9, weight: .medium))
                .padding(.horizontal, 5)
                .padding(.vertical, 2)
                .background(Color.accentColor.opacity(0.2))
                .foregroundColor(.accentColor)
                .cornerRadius(3)
        } else {
            Text("Built-in")
                .font(.system(size: 9, weight: .medium))
                .padding(.horizontal, 5)
                .padding(.vertical, 2)
                .background(Color.secondary.opacity(0.15))
                .foregroundColor(.secondary)
                .cornerRadius(3)
        }
    }

    private var addNewForm: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Add or override a mapping")
                .font(.system(size: 11, weight: .semibold))
                .foregroundColor(.secondary)
            HStack(spacing: 8) {
                TextField("Internal name (e.g. SONY ILCE-7RM3A)",
                          text: $newInternal)
                    .textFieldStyle(.roundedBorder)
                    .font(.system(size: 12, design: .monospaced))
                Image(systemName: "arrow.right")
                    .foregroundColor(.secondary)
                TextField("Marketing name (e.g. Sony a7R IIIA)",
                          text: $newMarketing)
                    .textFieldStyle(.roundedBorder)
                    .font(.system(size: 12))
                Button("Save") {
                    Task { await saveMapping() }
                }
                .disabled(newInternal.trimmingCharacters(in: .whitespaces).isEmpty
                          || newMarketing.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            Text("Tip: copy the internal name from the ⓘ button next to the camera "
                 + "field in the right details panel.")
                .font(.system(size: 10))
                .foregroundColor(.secondary)
        }
    }

    // MARK: - Networking

    private func reload() async {
        isLoading = true
        errorMessage = nil
        do {
            let list = try await VideoRepository.shared.listCameraNameMappings()
            await MainActor.run {
                self.mappings = list
                self.isLoading = false
            }
        } catch {
            await MainActor.run {
                self.errorMessage = "Failed to load mappings: \(error.localizedDescription)"
                self.isLoading = false
            }
        }
    }

    private func saveMapping() async {
        let internalName = newInternal.trimmingCharacters(in: .whitespaces)
        let marketing = newMarketing.trimmingCharacters(in: .whitespaces)
        guard !internalName.isEmpty, !marketing.isEmpty else { return }
        do {
            _ = try await VideoRepository.shared.setCameraNameMapping(
                internal: internalName, marketing: marketing
            )
            await MainActor.run {
                self.newInternal = ""
                self.newMarketing = ""
            }
            await reload()
        } catch {
            await MainActor.run {
                self.errorMessage = "Failed to save mapping: \(error.localizedDescription)"
            }
        }
    }

    private func deleteMapping(_ mapping: CameraNameMapping) async {
        do {
            // Empty marketing → server deletes the custom override.
            _ = try await VideoRepository.shared.setCameraNameMapping(
                internal: mapping.internalName, marketing: ""
            )
            await reload()
        } catch {
            await MainActor.run {
                self.errorMessage = "Failed to remove mapping: \(error.localizedDescription)"
            }
        }
    }
}

#Preview {
    CameraNamesView()
}
