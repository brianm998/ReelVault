// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// Editor for the catalog's lens display-name aliases.
///
/// Unlike cameras there's no built-in table: the rows are the distinct
/// lens strings actually present in the catalog (the values ReelVault
/// extracts from `exifEX:LensModel`), each shown with the name the UI
/// displays for it. Renaming a row stores a custom alias; clearing the
/// alias reverts to the raw string.
///
/// Aliases are stored per-catalog and apply to every metadata surface
/// (right-panel detail row, lens filter column, stat slots that show
/// "Lens"). Use this to tidy up verbose third-party names (e.g.
/// "14mm F1.8 DG HSM | Art 018" → "Sigma 14mm F1.8 Art") or fold a
/// leftover variant onto a canonical name.
struct LensNamesView: View {
    @Environment(\.dismiss) private var dismiss

    @State private var mappings: [LensNameMapping] = []
    @State private var isLoading = true
    @State private var errorMessage: String? = nil

    // Add/rename form state
    @State private var newRaw: String = ""
    @State private var newAlias: String = ""

    /// The row the user has tapped a "delete" button on but not yet
    /// confirmed. When non-nil, an alert is presented.
    @State private var pendingDelete: LensNameMapping? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Header
            HStack(spacing: 8) {
                Image(systemName: "camera.aperture")
                    .font(.title2)
                    .foregroundColor(.accentColor)
                Text("Lens Names")
                    .font(.title2)
                    .fontWeight(.semibold)
                Spacer()
                Button("Done") { dismiss() }
                    .keyboardShortcut(.defaultAction)
            }
            .padding(.horizontal, 16)
            .padding(.top, 16)
            .padding(.bottom, 8)

            Text("These are the lens names recorded in your catalog. ReelVault "
                 + "reads the most specific name each file provides, but you can "
                 + "rename any of them here — handy for shortening verbose "
                 + "third-party names or folding a stray variant onto a canonical "
                 + "name. Clear an alias to go back to the raw recorded name.")
                .font(.system(size: 11))
                .foregroundColor(.secondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 16)
                .padding(.bottom, 12)

            Divider()

            // Body — scrolling table of (recorded name, alias, source)
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
                        Text("No lenses in the catalog yet.")
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

            // Add/rename form
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
            "Clear this lens alias?",
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
            Text("\"\(row.rawName)\" will go back to showing its recorded name after removal.")
        }
    }

    @ViewBuilder
    private func mappingRow(_ mapping: LensNameMapping) -> some View {
        HStack(alignment: .center, spacing: 10) {
            // Recorded name (left): monospaced so part numbers read clean.
            Text(mapping.rawName)
                .font(.system(size: 11, design: .monospaced))
                .foregroundColor(.secondary)
                .lineLimit(1)
                .truncationMode(.tail)
                .frame(width: 240, alignment: .leading)

            // Alias (centre): the custom display name, or a dim dash when
            // the lens shows under its recorded name.
            Text(mapping.isCustom ? mapping.alias : "—")
                .font(.system(size: 12))
                .foregroundColor(mapping.isCustom ? .primary : .secondary.opacity(0.6))
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)

            // Source badge — "From catalog" / "Custom" / "Custom (unused)".
            sourceBadge(for: mapping)

            // Trailing action: remove a custom alias, or load a catalog row
            // into the form to rename it.
            if mapping.isCustom {
                Button {
                    pendingDelete = mapping
                } label: {
                    Image(systemName: "trash")
                        .font(.system(size: 12))
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.borderless)
                .help("Remove this custom alias.")
            } else {
                Button {
                    newRaw = mapping.rawName
                    newAlias = mapping.alias
                } label: {
                    Image(systemName: "pencil")
                        .font(.system(size: 12))
                        .foregroundColor(.secondary.opacity(0.7))
                }
                .buttonStyle(.borderless)
                .help("Rename this lens.")
            }
        }
        .padding(.vertical, 6)
    }

    @ViewBuilder
    private func sourceBadge(for mapping: LensNameMapping) -> some View {
        if mapping.isCustom && !mapping.inCatalog {
            Text("Custom (unused)")
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
            Text("From catalog")
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
            Text("Rename a lens")
                .font(.system(size: 11, weight: .semibold))
                .foregroundColor(.secondary)
            HStack(spacing: 8) {
                TextField("Recorded name (e.g. 14mm F1.8 DG HSM | Art 018)",
                          text: $newRaw)
                    .textFieldStyle(.roundedBorder)
                    .font(.system(size: 12, design: .monospaced))
                Image(systemName: "arrow.right")
                    .foregroundColor(.secondary)
                TextField("Display name (e.g. Sigma 14mm F1.8 Art)",
                          text: $newAlias)
                    .textFieldStyle(.roundedBorder)
                    .font(.system(size: 12))
                Button("Save") {
                    Task { await saveMapping() }
                }
                .disabled(newRaw.trimmingCharacters(in: .whitespaces).isEmpty
                          || newAlias.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            Text("Tip: click the pencil next to a lens above to load its recorded "
                 + "name here, then edit the display name.")
                .font(.system(size: 10))
                .foregroundColor(.secondary)
        }
    }

    // MARK: - Networking

    private func reload() async {
        isLoading = true
        errorMessage = nil
        do {
            let list = try await VideoRepository.shared.listLensNameMappings()
            await MainActor.run {
                self.mappings = list
                self.isLoading = false
            }
        } catch {
            await MainActor.run {
                self.errorMessage = String(format: String(localized: "Failed to load lens names: %@"), error.localizedDescription)
                self.isLoading = false
            }
        }
    }

    private func saveMapping() async {
        let raw = newRaw.trimmingCharacters(in: .whitespaces)
        let alias = newAlias.trimmingCharacters(in: .whitespaces)
        guard !raw.isEmpty, !alias.isEmpty else { return }
        do {
            _ = try await VideoRepository.shared.setLensNameMapping(raw: raw, alias: alias)
            await MainActor.run {
                self.newRaw = ""
                self.newAlias = ""
            }
            await reload()
        } catch {
            await MainActor.run {
                self.errorMessage = String(format: String(localized: "Failed to save alias: %@"), error.localizedDescription)
            }
        }
    }

    private func deleteMapping(_ mapping: LensNameMapping) async {
        do {
            // Empty alias → server deletes the custom override.
            _ = try await VideoRepository.shared.setLensNameMapping(raw: mapping.rawName, alias: "")
            await reload()
        } catch {
            await MainActor.run {
                self.errorMessage = String(format: String(localized: "Failed to remove alias: %@"), error.localizedDescription)
            }
        }
    }
}

#Preview {
    LensNamesView()
}
