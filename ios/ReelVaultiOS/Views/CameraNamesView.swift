// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// iOS editor for the catalog's camera marketing-name mappings.
///
/// The daemon ships a curated table of internal → marketing mappings
/// (Sony / Nikon / Panasonic / Fujifilm / Blackmagic / …). This view
/// shows that table plus any custom overrides the user has added,
/// and provides a form (shown as a sheet) for adding new entries or
/// overriding built-in ones.
///
/// Setting a row's marketing name to blank removes the custom override
/// and reveals the built-in entry again.
struct CameraNamesView: View {
    @Environment(\.dismiss) private var dismiss

    @State private var mappings: [CameraNameMapping] = []
    @State private var isLoading = true
    @State private var errorMessage: String? = nil
    @State private var showingAddSheet = false
    @State private var pendingDelete: CameraNameMapping? = nil

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
                Text("No mappings yet. Tap + to add one.")
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .listRowBackground(Color.clear)
            } else {
                ForEach(mappings) { mapping in
                    CameraNameRow(mapping: mapping)
                        .swipeActions(edge: .trailing) {
                            if mapping.isCustom {
                                Button(role: .destructive) {
                                    pendingDelete = mapping
                                } label: {
                                    Label("Remove", systemImage: "trash")
                                }
                            }
                        }
                }
            }
        }
        .navigationTitle("Camera Names")
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
            AddCameraNameSheet { internalName, marketingName in
                await saveMapping(internal: internalName, marketing: marketingName)
            }
        }
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
            let list = try await VideoRepository.shared.listCameraNameMappings()
            mappings = list
            isLoading = false
        } catch {
            errorMessage = "Failed to load mappings: \(error.localizedDescription)"
            isLoading = false
        }
    }

    private func saveMapping(internal internalName: String, marketing: String) async {
        do {
            _ = try await VideoRepository.shared.setCameraNameMapping(
                internal: internalName, marketing: marketing
            )
            await reload()
        } catch {
            errorMessage = "Failed to save mapping: \(error.localizedDescription)"
        }
    }

    private func deleteMapping(_ mapping: CameraNameMapping) async {
        do {
            // Empty marketing name → server deletes the custom override.
            _ = try await VideoRepository.shared.setCameraNameMapping(
                internal: mapping.internalName, marketing: ""
            )
            await reload()
        } catch {
            errorMessage = "Failed to remove mapping: \(error.localizedDescription)"
        }
    }
}

// MARK: - Row

private struct CameraNameRow: View {
    let mapping: CameraNameMapping

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .center, spacing: 8) {
                Text(mapping.marketingName)
                    .font(.body)
                Spacer()
                sourceBadge
            }
            Text(mapping.internalName)
                .font(.caption)
                .foregroundStyle(.secondary)
                .fontDesign(.monospaced)
        }
        .padding(.vertical, 2)
    }

    @ViewBuilder
    private var sourceBadge: some View {
        if mapping.isCustom && mapping.isBuiltin {
            badge("Override", color: .orange)
        } else if mapping.isCustom {
            badge("Custom", color: .accentColor)
        } else {
            badge("Built-in", color: .secondary)
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

// MARK: - Add sheet

private struct AddCameraNameSheet: View {
    var onSave: (String, String) async -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var internalName: String = ""
    @State private var marketingName: String = ""
    @State private var isSaving = false
    @FocusState private var focusedField: Field?

    private enum Field { case internalName, marketingName }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledContent {
                        TextField("e.g. SONY ILCE-7RM3A", text: $internalName)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.characters)
                            .font(.system(.body, design: .monospaced))
                            .focused($focusedField, equals: .internalName)
                            .submitLabel(.next)
                            .onSubmit { focusedField = .marketingName }
                    } label: {
                        Text("Internal name")
                    }

                    LabeledContent {
                        TextField("e.g. Sony a7R IIIA", text: $marketingName)
                            .autocorrectionDisabled()
                            .focused($focusedField, equals: .marketingName)
                            .submitLabel(.done)
                            .onSubmit {
                                if canSave { Task { await save() } }
                            }
                    } label: {
                        Text("Marketing name")
                    }
                } header: {
                    Text("Add or override a mapping")
                } footer: {
                    Text("Copy the internal name from the camera field in the video detail panel. Leave marketing name blank to remove a custom override.")
                }
            }
            .navigationTitle("Add Camera Mapping")
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
            .onAppear { focusedField = .internalName }
        }
    }

    private var canSave: Bool {
        !internalName.trimmingCharacters(in: .whitespaces).isEmpty
            && !marketingName.trimmingCharacters(in: .whitespaces).isEmpty
    }

    private func save() async {
        isSaving = true
        await onSave(
            internalName.trimmingCharacters(in: .whitespaces),
            marketingName.trimmingCharacters(in: .whitespaces)
        )
        isSaving = false
        dismiss()
    }
}
