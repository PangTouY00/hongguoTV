// SPDX-License-Identifier: GPL-3.0-only
import SwiftUI
import AVKit

struct Series: Decodable, Identifiable {
    let id: String
    let title: String
    let cover: String
    let description: String
    let badge: String
}
struct SearchResult: Decodable { let items: [Series]; let hasMore: Bool }
struct Detail: Decodable { let series: Series; let episodes: [String] }
struct PlayResult: Decodable { let url: String }
struct BridgeError: Decodable { let error: String }

@MainActor final class Model: ObservableObject {
    @Published var query = ""
    @Published var rows: [Series] = []
    @Published var detail: Detail?
    @Published var busy = true
    @Published var message = "正在启动…"
    @Published var episode: Int?
    @Published var hasMore = false
    let player = AVPlayer()
    private var helper: Process?
    private var input: Pipe?
    private var base: URL?
    private var page = 1
    private var searched = ""
    private var action: Task<Void, Never>?
    private var playerObservation: NSKeyValueObservation?

    init() {
        playerObservation = player.observe(\.currentItem?.status, options: [.new]) { [weak self] player, _ in
            guard player.currentItem?.status == .failed else { return }
            Task { @MainActor [weak self] in self?.message = "此视频暂时无法播放，请尝试其他集数或剧集。" }
        }
        Task { await start() }
    }
    private func start() async {
        defer { busy = false }
        guard let resources = Bundle.main.resourceURL else { return }
        let process = Process()
        process.executableURL = resources.appendingPathComponent("runtime/bin/java")
        process.arguments = ["--add-modules", "jdk.httpserver", "-cp", resources.appendingPathComponent("lib/*").path, "com.hongguotv.desktop.MainKt"]
        let output = Pipe(), input = Pipe()
        process.standardOutput = output
        process.standardInput = input
        process.standardError = FileHandle.nullDevice
        self.input = input
        do {
            try process.run()
            helper = process
            let line: String = try await Task.detached {
                var data = Data()
                while data.count < 1024 {
                    let byte = try output.fileHandleForReading.read(upToCount: 1) ?? Data()
                    if byte.isEmpty || byte.first == 10 { break }
                    data.append(byte)
                }
                return String(decoding: data, as: UTF8.self)
            }.value
            guard let url = URL(string: line), url.host == "127.0.0.1", url.scheme == "http" else {
                throw URLError(.cannotConnectToHost)
            }
            base = url
            message = "输入剧名，开始找剧"
        } catch { message = "客户端启动失败，请重新打开应用。"; shutdown() }
    }
    private func request<T: Decodable>(_ path: String, _ parameters: [String: String]) async throws -> T {
        guard let base else { throw URLError(.cannotConnectToHost) }
        var url = URLComponents(url: base.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        url.queryItems = parameters.map { URLQueryItem(name: $0.key, value: $0.value) }
        var request = URLRequest(url: url.url!)
        request.timeoutInterval = 90
        let (data, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else {
            throw NSError(domain: "Hongguo", code: 1, userInfo: [NSLocalizedDescriptionKey: (try? JSONDecoder().decode(BridgeError.self, from: data).error) ?? "请求失败，请重试。"])
        }
        return try JSONDecoder().decode(T.self, from: data)
    }
    private func perform(_ body: @escaping @MainActor () async throws -> Void) {
        guard !busy else { return }
        busy = true
        action = Task {
            defer { busy = false }
            do { try await body() }
            catch { if !Task.isCancelled { message = error.localizedDescription } }
        }
    }
    func search(more: Bool = false) {
        let keyword = more ? searched : query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !keyword.isEmpty, keyword.count <= 80 else { message = "请输入 1～80 字的剧名"; return }
        perform {
            let next = more ? self.page + 1 : 1
            self.message = "正在搜索…"
            let result: SearchResult = try await self.request("search", ["q": keyword, "page": String(next)])
            if more {
                let existing = Set(self.rows.map(\.id))
                self.rows += result.items.filter { !existing.contains($0.id) }
            } else { self.rows = result.items }
            self.searched = keyword; self.page = next; self.hasMore = result.hasMore
            self.message = self.rows.isEmpty ? "没有找到相关剧集" : "已加载 \(self.rows.count) 部剧"
        }
    }
    func open(_ series: Series) {
        perform {
            self.message = "正在读取剧集…"
            let detail: Detail = try await self.request("detail", ["id": series.id])
            self.player.pause(); self.player.replaceCurrentItem(with: nil)
            self.detail = detail; self.episode = nil
            self.message = "选择集数开始播放 · 共 \(detail.episodes.count) 集"
        }
    }
    func play(_ index: Int) {
        guard let detail, detail.episodes.indices.contains(index) else { return }
        perform {
            self.message = "正在准备第 \(index + 1) 集…"
            let result: PlayResult = try await self.request("play", ["id": detail.episodes[index]])
            guard let url = URL(string: result.url), url.host == "127.0.0.1" else { throw URLError(.badURL) }
            self.player.replaceCurrentItem(with: AVPlayerItem(url: url))
            self.episode = index; self.player.play()
            self.message = "正在播放第 \(index + 1) 集"
        }
    }
    func shutdown() {
        action?.cancel(); player.pause(); player.replaceCurrentItem(with: nil)
        try? input?.fileHandleForWriting.close()
        helper?.terminate(); helper = nil
    }
}

// Use AppKit directly: SwiftUI VideoPlayer's overlay crashes during metadata
// initialization on the development host before a media item is even loaded.
struct NativePlayer: NSViewRepresentable {
    let player: AVPlayer
    func makeNSView(context: Context) -> AVPlayerView {
        let view = AVPlayerView()
        view.player = player
        view.controlsStyle = .floating
        view.showsFullScreenToggleButton = true
        view.videoGravity = .resizeAspect
        return view
    }
    func updateNSView(_ view: AVPlayerView, context: Context) {
        if view.player !== player { view.player = player }
    }
    static func dismantleNSView(_ view: AVPlayerView, coordinator: ()) { view.player = nil }
}

struct ContentView: View {
    @ObservedObject var model: Model
    var body: some View {
        HSplitView {
            VStack(alignment: .leading, spacing: 16) {
                Text("红果 · Mac").font(.largeTitle.bold())
                Text("剧集搜索与播放 · 试验版").foregroundStyle(.secondary)
                HStack {
                    TextField("输入剧名", text: $model.query).textFieldStyle(.roundedBorder)
                        .onSubmit { model.search() }
                    Button("搜索") { model.search() }.disabled(model.busy)
                }
                List {
                    ForEach(model.rows) { series in
                        Button { model.open(series) } label: {
                            HStack(alignment: .top, spacing: 12) {
                                AsyncImage(url: URL(string: series.cover)) { image in image.resizable().scaledToFill() }
                                    placeholder: { Rectangle().fill(.gray.opacity(0.2)) }
                                    .frame(width: 62, height: 86).clipped().cornerRadius(6)
                                VStack(alignment: .leading, spacing: 8) {
                                    Text(series.title).font(.headline).lineLimit(3)
                                    Text(series.badge).font(.caption).foregroundStyle(.secondary)
                                }
                            }.padding(.vertical, 6).frame(maxWidth: .infinity, alignment: .leading)
                        }.buttonStyle(.plain).disabled(model.busy)
                    }
                    if model.hasMore { Button("加载更多") { model.search(more: true) }.disabled(model.busy) }
                }.listStyle(.plain)
            }.padding(24).frame(minWidth: 300, idealWidth: 350, maxWidth: 430)
            VStack(alignment: .leading, spacing: 16) {
                if let detail = model.detail {
                    HStack {
                        Text(detail.series.title).font(.title2.bold()).lineLimit(2)
                        Spacer()
                        if let episode = model.episode {
                            Button("下一集") { model.play(episode + 1) }
                                .disabled(model.busy || episode + 1 >= detail.episodes.count)
                        }
                    }
                    NativePlayer(player: model.player).frame(minHeight: 250, idealHeight: 380)
                        .background(.black).cornerRadius(12)
                    Text(detail.series.description).foregroundStyle(.secondary).lineLimit(3)
                    Text("选集 · 共 \(detail.episodes.count) 集").font(.headline)
                    ScrollView {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 56))], spacing: 10) {
                            ForEach(detail.episodes.indices, id: \.self) { index in
                                Button("\(index + 1)") { model.play(index) }
                                    .buttonStyle(.bordered)
                                    .tint(model.episode == index ? .orange : .gray)
                                    .disabled(model.busy)
                            }
                        }.padding(4)
                    }.frame(minHeight: 100)
                } else {
                    Spacer()
                    Image(systemName: "play.rectangle").font(.system(size: 64)).foregroundStyle(.orange)
                    Text("找一部想看的剧").font(.title)
                    Text("从左侧搜索结果打开剧集，再选择集数播放。").foregroundStyle(.secondary)
                    Spacer()
                }
                HStack(spacing: 10) {
                    if model.busy { ProgressView().controlSize(.small) }
                    Text(model.message).font(.callout).foregroundStyle(.secondary).textSelection(.enabled)
                }
            }.padding(24).frame(minWidth: 540, maxWidth: .infinity, maxHeight: .infinity)
        }.frame(minWidth: 940, minHeight: 700)
            .tint(.orange).preferredColorScheme(.dark)
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }
}

@main struct HongguoMac: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var model = Model()
    var body: some Scene {
        Window("红果 TV · Mac", id: "main") { ContentView(model: model)
            .onReceive(NotificationCenter.default.publisher(for: NSApplication.willTerminateNotification)) { _ in model.shutdown() }
        }
            .commands { CommandGroup(replacing: .newItem) {} }
    }
    init() {
        NSApplication.shared.setActivationPolicy(.regular)
    }
}
