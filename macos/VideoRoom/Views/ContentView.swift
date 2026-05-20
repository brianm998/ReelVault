import SwiftUI

struct ContentView: View {
    @StateObject private var gridViewModel = GridViewModel()
    @StateObject private var detailViewModel = DetailViewModel()
    @State private var isConnected = false
    @State private var connectionError: String?
    @State private var isDarkMode = false
    @State private var searchQuery = ""

    var body: some View {
        if isConnected {
            ZStack {
                VStack(spacing: 0) {
                    // Top bar
                    HStack(spacing: 16) {
                        Text("VideoRoom")
                            .font(.system(size: 18, weight: .semibold))

                        TextField("Search videos...", text: $searchQuery)
                            .textFieldStyle(.roundedBorder)
                            .frame(width: 300)
                            .onChange(of: searchQuery) { newValue in
                                gridViewModel.searchQuery = newValue
                            }

                        Spacer()

                        Toggle("", isOn: $isDarkMode)
                            .labelsHidden()
                    }
                    .padding(16)
                    .background(Color(.controlBackgroundColor))
                    .border(Color(.separatorColor), width: 1)

                    // Main content
                    HStack(spacing: 0) {
                        // Grid view (70%)
                        GridView(viewModel: gridViewModel, detailViewModel: detailViewModel)
                            .frame(maxWidth: .infinity)

                        Divider()

                        // Detail panel (30%)
                        DetailView(viewModel: detailViewModel)
                            .frame(maxWidth: .infinity * 0.3)
                    }
                }
            }
            .frame(minWidth: 1200, minHeight: 800)
            .preferredColorScheme(isDarkMode ? .dark : .light)
            .onAppear {
                gridViewModel.loadVideos()
            }
        } else {
            ConnectionErrorView(errorMessage: connectionError ?? "Connection failed")
        }
    }

    private func setupConnection() {
        Task {
            let connected = await VideoRepository.shared.connect()
            await MainActor.run {
                isConnected = connected
                if !connected {
                    connectionError = "Failed to connect to VideoRoom backend on localhost:50051"
                } else {
                    gridViewModel.loadVideos()
                }
            }
        }
    }
}

#Preview {
    ContentView()
}
