import SwiftUI

struct ConnectionErrorView: View {
    let errorMessage: String

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
                Text("Make sure the VideoRoom backend is running:")
                    .font(.body)
                    .foregroundColor(.secondary)

                VStack(alignment: .leading, spacing: 4) {
                    Text("cd core && cargo run --bin videoroom-core")
                        .font(.system(.body, design: .monospaced))
                        .padding(12)
                        .background(Color(.controlBackgroundColor))
                        .border(Color(.separatorColor), width: 1)
                        .cornerRadius(4)

                    Button(action: {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(
                            "cd core && cargo run --bin videoroom-core",
                            forType: .string
                        )
                    }) {
                        Text("Copy Command")
                            .font(.caption)
                    }
                    .buttonStyle(.bordered)
                }
            }
            .frame(maxWidth: 400)

            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(32)
        .background(Color(.windowBackgroundColor))
    }
}

#Preview {
    ConnectionErrorView(errorMessage: "Failed to connect to VideoRoom backend on localhost:50051")
}
