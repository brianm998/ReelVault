// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// iOS editor for the catalog's lens display-name aliases.
///
/// Unlike cameras there's no built-in table: the rows are the distinct
/// lens strings actually present in the catalog (the values ReelVault
/// extracts from `exifEX:LensModel`), each shown with the name the UI
/// displays for it. Renaming a row stores a custom alias; clearing the
/// alias reverts to the raw string.
///
/// Use this to tidy up verbose third-party names (e.g.
/// "14mm F1.8 DG HSM | Art 018" → "Sigma 14mm F1.8 Art") or fold a
/// leftover variant onto a canonical name.
struct LensNamesView: View {
    @Environment(\.dismiss) private var dismiss

    @State private var mappings: [LensNameMapping] = []
    @State private var isLoading = true
    @State private var errorMessage: String? = nil
    @State private var pendingDelete: LensNameMapping? = nil
    @State private var editingMapping: LensNameMapping? = nil
    @State private var showingAddSheet = false

    var body: some View {
        List {
            if isLoading {
                HStack {
                    Spacer()
                    ProgressView()
                    Spacer()
                }
                .listRowBackground(Color.clear)
            } else if mappings.isEmpty {
                Text("No lenses in the catalog yet.")
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .listRowBackground(Color.clear)
            } else {
                ForEach(mappings) { mapping in
                    LensNameRow(mapping: mapping)
                        .contentShape(Rectangle())
                        .onTapGesture {
                            editingMapping = mapping
                        }
                        .swipeActions(edge: .trailing) {
                            if mapping.isCustom {
                                Button(role: .destructive) {
                                    pendingDelete = mapping
                                } label: {
                                    Label("Remove", systemImage: "trash")
                                }
                            }
                        }
                        .swipeActions(edge: .leading) {
                            Button {
                                editingMapping = mapping
                            } label: {
                                Label("Rename", systemImage: "pencil")
                            }
                            .tint(.accentColor)
                        }
                }
            }
        }
        .navigationTitle("Lens Names")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showingAddSheet = true
                } label: {
                    Image(systemName: "plus")
                }
            }
        }
        .task { await reload() }
        .sheet(isPresented: $showingAddSheet) {
            EditLensNameSheet(prefill: nil) { rawName, alias in
                await saveMapping(raw: rawName, alias: alias)
            }
        }
        .sheet(item: $editingMapping) { mapping in
            EditLensNameSheet(prefill: mapping) { rawName, alias in
                await saveMapping(raw: rawName, alias: alias)
            }
        }
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
        .overlay(alignment: .bottom) {
            if let err = errorMessage {
                Text(err)
                    .font(.footnote)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(.red, in: RoundedRectangle(cornerRadius: 10))
                    .padding()
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .onTapGesture { errorMessage = nil }
            }
        }
        .animation(.default, value: errorMessage)
    }

    // MARK: - Networking

    private func reload() async {
        isLoading = true
        errorMessage = nil
        do {
            let list = try await VideoRepository.shared.listLensNameMappings()
            mappings = list
            isLoading = false
        } catch {
            errorMessage = "Failed to load lens names: \(error.localizedDescription)"
            isLoading = false
        }
    }

    private func saveMapping(raw: String, alias: String) async {
        do {
            _ = try await VideoRepository.shared.setLensNameMapping(raw: raw, alias: alias)
            await reload()
        } catch {
            errorMessage = "Failed to save alias: \(error.localizedDescription)"
        }
    }

    private func deleteMapping(_ mapping: LensNameMapping) async {
        do {
            // Empty alias → server deletes the custom override.
            _ = try await VideoRepository.shared.setLensNameMapping(raw: mapping.rawName, alias: "")
            await reload()
        } catch {
            errorMessage = "Failed to remove alias: \(error.localizedDescription)"
        }
    }
}

// MARK: - Row

private struct LensNameRow: View {
    let mapping: LensNameMapping

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .center, spacing: 8) {
                if mapping.isCustom {
                    Text(mapping.alias)
                        .font(.body)
                } else {
                    Text(mapping.rawName)
                        .font(.body)
                }
                Spacer()
                sourceBadge
            }
            if mapping.isCustom {
                // Show the raw name as the subtitle when a custom alias is set.
                Text(mapping.rawName)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fontDesign(.monospaced)
            }
        }
        .padding(.vertical, 2)
    }

    @ViewBuilder
    private var sourceBadge: some View {
        if mapping.isCustom && !mapping.inCatalog {
            badge("Unused", color: .orange)
        } else if mapping.isCustom {
            badge("Custom", color: .accentColor)
        } else {
            badge("From catalog", color: .secondary)
        }
    }

    private func badge(_ label: String, color: Color) -> some View {
        Text(label)
            .font(.system(size: 10, weight: .medium))
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(color.opacity(0.18))
            .foregroundStyle(color)
            .clipShape(Capsule())
    }
}

// MARK: - Edit / Add sheet

/// Sheet for adding a new lens alias or editing an existing one.
/// When `prefill` is non-nil the raw-name field is pre-filled and
/// locked so the user edits only the display name.
private struct EditLensNameSheet: View {
    /// Non-nil when editing an existing mapping.
    var prefill: LensNameMapping?
    var onSave: (String, String) async -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var rawName: String = ""
    @State private var alias: String = ""
    @State private var isSaving = false
    @FocusState private var focusedField: Field?

    private enum Field { case rawName, alias }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledContent {
                        if prefill != nil {
                            // Locked when editing: the raw name is the key.
                            Text(rawName)
                                .foregroundStyle(.secondary)
                                .font(.system(.body, design: .monospaced))
                                .frame(maxWidth: .infinity, alignment: .leading)
                        } else {
                            TextField("e.g. 14mm F1.8 DG HSM | Art 018", text: $rawName)
                                .autocorrectionDisabled()
                                .font(.system(.body, design: .monospaced))
                                .focused($focusedField, equals: .rawName)
                                .submitLabel(.next)
                                .onSubmit { focusedField = .alias }
                        }
                    } label: {
                        Text("Recorded name")
                    }

                    LabeledContent {
                        TextField("e.g. Sigma 14mm F1.8 Art", text: $alias)
                            .autocorrectionDisabled()
                            .focused($focusedField, equals: .alias)
                            .submitLabel(.done)
                            .onSubmit {
                                if canSave { Task { await save() } }
                            }
                    } label: {
                        Text("Display name")
                    }
                } header: {
                    Text(prefill != nil ? "Rename lens" : "Add lens alias")
                } footer: {
                    if prefill != nil {
                        Text("The recorded name is the string ReelVault extracts from the file's metadata. Tap swipe-left → trash to remove the alias.")
                    } else {
                        Text("The recorded name must match the string ReelVault stores in the catalog exactly. Tap a row in the list to edit it.")
                    }
                }
            }
            .navigationTitle(prefill != nil ? "Rename Lens" : "Add Lens Alias")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        Task { await save() }
                    }
                    .disabled(!canSave || isSaving)
                }
            }
            .onAppear {
                if let p = prefill {
                    rawName = p.rawName
                    alias = p.isCustom ? p.alias : ""
                    focusedField = .alias
                } else {
                    focusedField = .rawName
                }
            }
        }
    }

    private var canSave: Bool {
        !rawName.trimmingCharacters(in: .whitespaces).isEmpty
            && !alias.trimmingCharacters(in: .whitespaces).isEmpty
    }

    private func save() async {
        isSaving = true
        await onSave(
            rawName.trimmingCharacters(in: .whitespaces),
            alias.trimmingCharacters(in: .whitespaces)
        )
        isSaving = false
        dismiss()
    }
}
