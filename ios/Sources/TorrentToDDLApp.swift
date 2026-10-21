import SwiftUI
import UniformTypeIdentifiers
import QuickLook

@main
@MainActor
struct TorrentToDDLApp: App {
    @StateObject private var model = GatewayModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            GatewayView(model: model)
                .preferredColorScheme(.dark)
                .onChange(of: scenePhase, initial: true) { _, phase in model.sceneChanged(phase) }
                .onOpenURL { model.open(url: $0) }
        }
    }
}

private let graphite = Color(red: 11 / 255, green: 15 / 255, blue: 19 / 255)
private let panel = Color(red: 21 / 255, green: 29 / 255, blue: 35 / 255)
private let lime = Color(red: 206 / 255, green: 250 / 255, blue: 103 / 255)
private let cyan = Color(red: 131 / 255, green: 219 / 255, blue: 232 / 255)

@MainActor
private struct GatewayView: View {
    @ObservedObject var model: GatewayModel
    @State private var magnet = ""
    @State private var pickingTorrent = false
    @State private var preview: URL?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                HStack(spacing: 14) {
                    Image("AppMark").resizable().scaledToFit().frame(width: 64, height: 64)
                    VStack(alignment: .leading, spacing: 5) {
                        Text("YOUR DEVICE. YOUR GATEWAY.").font(.caption2.weight(.bold)).foregroundStyle(cyan)
                        Text("TorrentToDDL").font(.largeTitle.weight(.bold))
                        Text("iPhone & iPad · Preview").font(.caption).foregroundStyle(.secondary)
                    }
                }
                card {
                    HStack {
                        Circle().fill(model.running ? lime : .gray).frame(width: 8, height: 8)
                        Text(model.running ? "GATEWAY ONLINE" : "GATEWAY OFF")
                            .font(.caption.weight(.bold)).foregroundStyle(model.running ? lime : cyan)
                        Spacer()
                        if model.busy { ProgressView().tint(lime) }
                    }
                    Button {
                        if model.running { model.stop() } else { model.start() }
                    } label: {
                        Label(model.running ? "Stop Gateway" : "Start Gateway",
                              systemImage: model.running ? "stop.fill" : "power")
                            .frame(maxWidth: .infinity).padding(.vertical, 7)
                    }
                    .buttonStyle(.borderedProminent).tint(lime).foregroundStyle(graphite).disabled(model.busy)
                    TextField("Paste a magnet link", text: $magnet, axis: .vertical)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .font(.body.monospaced()).lineLimit(2...4).padding(14)
                        .background(graphite, in: RoundedRectangle(cornerRadius: 12))
                        .disabled(model.busy)
                    HStack(spacing: 12) {
                        Button("Resolve magnet") { model.load(magnet: magnet) }
                            .disabled(magnet.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.busy || model.saving)
                        Spacer()
                        Button { pickingTorrent = true } label: { Label("Torrent file", systemImage: "doc.badge.plus") }
                            .disabled(model.busy || model.saving)
                    }.font(.subheadline.weight(.semibold)).tint(lime)
                    if model.running && !model.lanAddress.isEmpty {
                        Text("http://\(model.lanAddress):8080").font(.caption.monospaced()).foregroundStyle(cyan).textSelection(.enabled)
                    }
                }
                card {
                    Text("TORRENT CACHE").font(.caption.weight(.bold)).foregroundStyle(cyan)
                    Text(model.snapshot.name.isEmpty ? "Waiting for a torrent" : model.snapshot.name)
                        .font(.title3.weight(.semibold))
                    Text(model.snapshot.state).font(.subheadline).foregroundStyle(.secondary)
                    ProgressView(value: model.snapshot.progress).tint(cyan)
                    HStack {
                        Text("\(bytes(model.snapshot.cachedBytes)) / \(bytes(model.snapshot.totalBytes))")
                        Spacer()
                        Text("\(model.snapshot.peers) peers")
                    }.font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                    Text("\(bytes(model.snapshot.rate))/s from torrent peers").font(.caption).foregroundStyle(cyan)
                }
                if !model.saveName.isEmpty {
                    card {
                        Text("SAVE TO APP STORAGE").font(.caption.weight(.bold)).foregroundStyle(lime)
                        Text(model.saveName).font(.headline)
                        ProgressView(value: model.saveTotal > 0 ? min(1, Double(model.savedBytes) / Double(model.saveTotal)) : 0)
                            .tint(lime)
                        Text("\(bytes(model.savedBytes)) / \(bytes(model.saveTotal)) · \(bytes(model.saveRate))/s")
                            .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                        Text(model.saveState).font(.caption).foregroundStyle(.secondary)
                        if model.saving {
                            Button("Cancel save", role: .destructive) { model.cancelSave() }
                        }
                        if let url = model.savedURL {
                            HStack {
                                ShareLink(item: url) { Label("Save to Files / Share", systemImage: "square.and.arrow.up") }
                                Spacer()
                                Button("Preview") { preview = url }
                            }.font(.subheadline.weight(.semibold)).tint(lime)
                        }
                    }
                }
                ForEach(model.snapshot.files) { file in
                    card {
                        Text(file.name).font(.headline).lineLimit(3)
                        Text(bytes(file.size)).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                        HStack {
                            Button { model.save(file) } label: { Label("Save file", systemImage: "arrow.down.to.line") }
                                .disabled(!model.running || model.busy || model.saving)
                            Spacer()
                            Button { model.copyLANLink(file) } label: { Label("LAN link", systemImage: "link") }
                                .disabled(!model.running || model.busy)
                        }.font(.subheadline.weight(.semibold)).tint(lime)
                    }
                }
                VStack(alignment: .leading, spacing: 9) {
                    Label("Keep this app open", systemImage: "iphone")
                        .font(.subheadline.weight(.semibold)).foregroundStyle(cyan)
                    Text("This iOS preview stops transfers and the gateway when backgrounded. Keep TorrentToDDL visible while downloading or sharing LAN links.")
                    Text("Cache progress counts torrent bytes; save progress counts bytes written to app storage. Cached files can save at disk speed.")
                    Text("LAN links have no authentication. Share only on a trusted network.")
                }.font(.caption).foregroundStyle(.secondary).padding(.horizontal, 4)
            }
            .frame(maxWidth: 850).padding(22).frame(maxWidth: .infinity)
        }
        .background(graphite.ignoresSafeArea())
        .safeAreaInset(edge: .bottom) {
            if let notice = model.notice {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: "info.circle.fill").foregroundStyle(cyan)
                    Text(notice.text).font(.subheadline).frame(maxWidth: .infinity, alignment: .leading)
                    Button { model.notice = nil } label: { Image(systemName: "xmark") }
                        .accessibilityLabel("Dismiss message").tint(lime)
                }
                .padding(18).background(panel, in: RoundedRectangle(cornerRadius: 16))
                .padding(.horizontal, 16).padding(.bottom, 8)
            }
        }
        .fileImporter(isPresented: $pickingTorrent,
                      allowedContentTypes: [UTType(filenameExtension: "torrent") ?? .data]) { result in
            switch result {
            case .success(let url): model.load(url: url)
            case .failure(let error): model.report(error.localizedDescription)
            }
        }
        .quickLookPreview($preview)
    }

    private func card<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 15, content: content)
            .frame(maxWidth: .infinity, alignment: .leading).padding(20)
            .background(panel, in: RoundedRectangle(cornerRadius: 20))
    }

    private func bytes(_ count: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: max(0, count), countStyle: .file)
    }
}
