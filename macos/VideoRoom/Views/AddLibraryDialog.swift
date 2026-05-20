import SwiftUI
import AppKit

/// Sheet for adding a library location. Lets the user enter a path manually
/// or pick one via NSOpenPanel, and choose whether to auto-group variants.
struct AddLibraryDialog: View {
    @Binding var isPresented: Bool
    let onConfirm: (_ path: String, _ autoGroup: Bool) -> Void

    @State private var path: String = ""
    @State private var autoGroup: Bool = true

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Add Library Location")
                .font(.headline)

            Text("Choose a directory containing videos. It will be scanned recursively.")
                .font(.caption)
                .foregroundColor(.secondary)

            HStack {
                TextField("/Users/you/Videos", text: $path)
                    .textFieldStyle(.roundedBorder)
                Button("Choose…") {
                    chooseDirectory()
                }
            }

            Toggle(isOn: $autoGroup) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Auto-group similar variants")
                        .font(.body)
                    Text("Stack videos that share a base name, duration, and frame rate.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }

            HStack {
                Spacer()
                Button("Cancel") {
                    isPresented = false
                }
                .keyboardShortcut(.cancelAction)

                Button("Add & Scan") {
                    if !path.trimmingCharacters(in: .whitespaces).isEmpty {
                        onConfirm(path.trimmingCharacters(in: .whitespaces), autoGroup)
                        isPresented = false
                    }
                }
                .keyboardShortcut(.defaultAction)
                .disabled(path.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(20)
        .frame(width: 480)
    }

    private func chooseDirectory() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.allowsMultipleSelection = false
        panel.prompt = "Choose"
        if panel.runModal() == .OK, let url = panel.url {
            path = url.path
        }
    }
}
