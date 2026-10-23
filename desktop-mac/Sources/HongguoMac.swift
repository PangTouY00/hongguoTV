// SPDX-License-Identifier: GPL-3.0-only
import SwiftUI
import AVKit

@MainActor final class Model: ObservableObject {
    @Published var query = ""
    @Published var rows: [Series] = []
    @Published var detail: Detail?
    @Published var busy = true
    @Published var message = "正在启动…"
    @Published var episode: Int?
    @Published var hasMore = false
    let player = AVPlayer()
    let library: LocalLibrary
    let updates = MacUpdates()
    @Published var section = "搜索"
    @Published var catalogRows: [Series] = []
    @Published var rankPositions: [String: MacRank] = [:]
    @Published var category = "COMIC"
    @Published var topic = ""
    @Published var topics: [Topic] = []
    @Published var board = "COMIC"
    @Published var catalogMore = false
    @Published var related: [RelatedEntry] = []
    @Published var relatedMessage = ""
    @Published var account: AccountResult?
    @Published var accountMessage = "登录后可查看账号片单和同步进度"
    @Published var accountBusy = false
    @Published var qrImage: NSImage?
    @Published var cloudType = "账户历史"
    @Published var cloudRows: [CloudEntry] = []
    var accountGeneration = 0
    var pendingLoginID: String?
    var loginTask: Task<Void, Never>?
    var uploadTask: Task<Void, Never>?
    var lastUpload = Date.distantPast
    var catalogPage = 1
    var catalogKind = ""
    var relatedTask: Task<Void, Never>?
    @Published var speed: Float = UserDefaults.standard.object(forKey: "playbackSpeed") as? Float ?? 1
    @Published var quality: Int = UserDefaults.standard.object(forKey: "playbackQuality") as? Int ?? 1080
    @Published var autoNext = UserDefaults.standard.object(forKey: "autoNext") as? Bool ?? true
    @Published var actualQuality = ""
    private var activeSeries: Series?
    private var activeEpisodeID: String?
    private var activeEpisodeIndex: Int?
    private var progressObserver: Any?
    private var endObserver: NSObjectProtocol?
    private var pendingNext = false
    var stopping = false
    @Published var preview = false
    private var activeTrial = false
    private var pendingTrial = false
    private var cloudResume: (Int, Double)?
    private var helper: Process?
    private var input: Pipe?
    private var base: URL?
    private var page = 1
    private var searched = ""
    private var action: Task<Void, Never>?
    private var playerObservation: NSKeyValueObservation?

    init(resourcesURL: URL? = Bundle.main.resourceURL, library: LocalLibrary? = nil, restoreSession: Bool = true) {
        self.library = library ?? LocalLibrary()
        playerObservation = player.observe(\.currentItem?.status, options: [.new]) { [weak self] player, _ in
            guard player.currentItem?.status == .failed else { return }
            Task { @MainActor [weak self] in self?.message = "此视频暂时无法播放，请尝试其他集数或剧集。" }
        }
        progressObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 5, preferredTimescale: 600), queue: .main) { [weak self] _ in
            Task { @MainActor [weak self] in self?.saveProgress() }
        }
        endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: nil, queue: .main) { [weak self] note in
            guard let item = note.object as? AVPlayerItem else { return }
            Task { @MainActor [weak self] in
                guard let self, item === self.player.currentItem else { return }
                self.saveProgress(completed: true)
                if !self.activeTrial && self.autoNext, let index = self.episode, let detail = self.detail, index + 1 < detail.episodes.count {
                    if self.busy { self.pendingNext = true } else { self.play(index + 1) }
                } else { self.message = "本集播放结束" }
            }
        }
        Task { await start(resources: resourcesURL, restoreSession: restoreSession) }
    }
    private func start(resources: URL?, restoreSession: Bool) async {
        defer { busy = false }
        guard let resources else { return }
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
            if restoreSession { Task { await restoreAccount() } }
        } catch { message = "客户端启动失败，请重新打开应用。"; shutdown() }
    }
    func request<T: Decodable>(_ path: String, _ parameters: [String: String], body: [String: Any]? = nil) async throws -> T {
        guard let base else { throw URLError(.cannotConnectToHost) }
        var url = URLComponents(url: base.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        url.queryItems = parameters.map { URLQueryItem(name: $0.key, value: $0.value) }
        var request = URLRequest(url: url.url!)
        request.timeoutInterval = 90
        if let body {
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        let (data, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else {
            throw NSError(domain: "Hongguo", code: 1, userInfo: [NSLocalizedDescriptionKey: (try? JSONDecoder().decode(BridgeError.self, from: data).error) ?? "请求失败，请重试。"])
        }
        return try JSONDecoder().decode(T.self, from: data)
    }
    func perform(_ body: @escaping @MainActor () async throws -> Void) {
        guard !busy else { return }
        busy = true
        action = Task {
            defer {
                busy = false
                if pendingTrial && !stopping { pendingTrial = false; play(0, trial: true) }
                else if pendingNext && !stopping {
                    pendingNext = false
                    if let episode { play(episode + 1) }
                }
            }
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
            self.section = "搜索"
            self.searched = keyword; self.page = next; self.hasMore = result.hasMore
            self.message = self.rows.isEmpty ? "没有找到相关剧集" : "已加载 \(self.rows.count) 部剧"
        }
    }
    func open(_ series: Series, trial: Bool = false) {
        perform {
            self.message = "正在读取剧集…"
            let detail: Detail = try await self.request("detail", ["id": series.id])
            self.saveProgress()
            self.player.pause(); self.player.replaceCurrentItem(with: nil)
            self.activeSeries = nil; self.activeEpisodeID = nil; self.activeEpisodeIndex = nil
            self.pendingNext = false
            self.detail = detail; self.episode = nil; self.preview = trial; self.pendingTrial = trial
            if self.section == "账户" && self.cloudType == "账户历史", let cloud = self.cloudRows.first(where: { $0.id == series.id }), let id = cloud.episodeID, let index = detail.episodes.firstIndex(of: id) {
                self.cloudResume = (index, cloud.position ?? 0)
            } else { self.cloudResume = nil }
            self.related = []; self.relatedMessage = "正在读取相关作品…"
            self.relatedTask?.cancel()
            self.relatedTask = Task {
                do {
                    let result: RelatedResult = try await self.request("related", ["id": detail.series.id])
                    if !Task.isCancelled && self.detail?.series.id == detail.series.id { self.related = result.items; self.relatedMessage = result.items.isEmpty ? "暂无相关作品" : "" }
                } catch { if !Task.isCancelled { self.relatedMessage = "相关作品暂时无法加载" } }
            }
            self.message = "选择集数开始播放 · 共 \(detail.episodes.count) 集"
        }
    }
    func play(_ index: Int, position: Double = 0, autoplay: Bool = true, trial: Bool = false) {
        guard let detail, detail.episodes.indices.contains(index) else { return }
        let selectedQuality = quality
        perform {
            self.saveProgress()
            self.message = "正在准备第 \(index + 1) 集…"
            let result: PlayResult = try await self.request("play", ["id": detail.episodes[index], "quality": String(selectedQuality)])
            guard !Task.isCancelled else { return }
            guard let url = URL(string: result.url), url.host == "127.0.0.1" else { throw URLError(.badURL) }
            self.player.pause()
            self.player.replaceCurrentItem(with: AVPlayerItem(url: url))
            self.activeSeries = detail.series; self.activeEpisodeID = detail.episodes[index]; self.activeEpisodeIndex = index
            self.activeTrial = trial; self.preview = trial
            self.episode = index; self.actualQuality = result.quality
            if position.isFinite && position > 0 {
                _ = await self.player.seek(to: CMTime(seconds: position, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
            }
            if autoplay { self.player.playImmediately(atRate: self.speed) }
            self.saveProgress()
            self.message = "第 \(index + 1) 集 · \(result.quality)"
        }
    }
    func watchFull() {
        preview = false; activeTrial = false; saveProgress()
        message = "已进入正式观看，可选择集数"
    }
    func moveRecommendation(_ delta: Int) {
        guard let id = detail?.series.id, let index = catalogRows.firstIndex(where: { $0.id == id }), catalogRows.indices.contains(index + delta) else { message = "当前批次已到边界，可换一批或加载更多"; return }
        open(catalogRows[index + delta], trial: true)
    }
    var resumeLabel: String {
        if let cloudResume { return "继续账号第 \(cloudResume.0 + 1) 集" }
        guard let detail, let saved = library.history(detail.series.id) else { return "从第 1 集播放" }
        let index = saved.completed && saved.episodeIndex + 1 < detail.episodes.count ? saved.episodeIndex + 1 : saved.episodeIndex
        return "继续第 \(index + 1) 集"
    }
    func resume() {
        guard let detail else { return }
        if let cloudResume { play(cloudResume.0, position: cloudResume.1); self.cloudResume = nil; return }
        guard let saved = library.history(detail.series.id), let index = detail.episodes.firstIndex(of: saved.episodeID) else { play(0); return }
        if saved.completed && index + 1 < detail.episodes.count { play(index + 1) }
        else { play(index, position: saved.completed ? 0 : saved.position) }
    }
    func saveProgress(completed: Bool = false) {
        guard !activeTrial, let series = activeSeries, let id = activeEpisodeID, let index = activeEpisodeIndex, player.currentItem != nil else { return }
        let position = CMTimeGetSeconds(player.currentTime()), duration = CMTimeGetSeconds(player.currentItem!.duration)
        guard position.isFinite else { return }
        let record = WatchRecord(series: series, episodeID: id, episodeIndex: index, position: max(0, position), duration: duration.isFinite ? max(0, duration) : 0, completed: completed || (duration.isFinite && duration > 0 && position >= duration - 0.5), updatedAt: Date())
        library.record(record)
        uploadProgress(record, count: detail?.episodes.count ?? 0)
    }
    func changeSpeed(_ value: Float) {
        guard [0.5, 0.75, 1, 1.25, 1.5, 2].contains(value) else { return }
        speed = value; UserDefaults.standard.set(value, forKey: "playbackSpeed")
        player.defaultRate = value
        if player.rate > 0 { player.rate = value }
    }
    func changeQuality(_ value: Int) {
        guard [480, 720, 1080].contains(value) else { return }
        quality = value; UserDefaults.standard.set(value, forKey: "playbackQuality")
        if let episode { play(episode, position: CMTimeGetSeconds(player.currentTime()), autoplay: player.rate > 0, trial: activeTrial) }
    }

    func shutdown() {
        stopping = true; pendingNext = false; pendingTrial = false; relatedTask?.cancel(); loginTask?.cancel(); uploadTask?.cancel()
        saveProgress(); action?.cancel()
        if let progressObserver { player.removeTimeObserver(progressObserver); self.progressObserver = nil }
        if let endObserver { NotificationCenter.default.removeObserver(endObserver); self.endObserver = nil }
        player.pause(); player.replaceCurrentItem(with: nil)
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
    @ObservedObject var library: LocalLibrary
    @ObservedObject var updates: MacUpdates
    init(model: Model) { self.model = model; self.library = model.library; self.updates = model.updates }
    private var displayedRows: [Series] {
        switch model.section {
        case "收藏": return library.contents.favorites
        case "历史": return library.contents.history.map(\.series)
        case "找剧", "榜单", "推荐": return model.catalogRows
        case "账户": return model.cloudRows.map(\.series)
        case "更新": return []
        default: return model.rows
        }
    }
    var body: some View {
        HSplitView {
            VStack(alignment: .leading, spacing: 16) {
                Text("红果 · Mac").font(.largeTitle.bold())
                Text("追剧与账号同步 · 试验版").foregroundStyle(.secondary)
                HStack {
                    TextField("输入剧名", text: $model.query).textFieldStyle(.roundedBorder)
                        .onSubmit { model.search() }
                    Button("搜索") { model.search() }.disabled(model.busy)
                }
                Picker("功能", selection: $model.section) {
                    ForEach(["搜索", "找剧", "榜单", "推荐", "收藏", "历史", "账户", "更新"], id: \.self) { Text($0).tag($0) }
                }.pickerStyle(.menu).disabled(model.busy)
                    .onChange(of: model.section) { _ in model.loadSection() }
                if model.section == "找剧" || model.section == "推荐" {
                    Picker("类型", selection: $model.category) {
                        if model.section == "推荐" { Text("综合推荐").tag("ALL") }
                        Text("漫剧").tag("COMIC"); Text("真人剧").tag("REAL")
                        if model.section == "找剧" { Text("AI 剧").tag("AI") }
                    }.disabled(model.busy).onChange(of: model.category) { _ in model.topic = ""; model.loadCatalog() }
                }
                if model.section == "榜单" {
                    Picker("热播榜", selection: $model.board) {
                        Text("综合").tag("ALL"); Text("漫剧").tag("COMIC"); Text("真人剧").tag("REAL"); Text("AI 剧").tag("AI")
                    }.disabled(model.busy).onChange(of: model.board) { _ in model.topic = ""; model.loadCatalog() }
                }
                if model.section == "找剧" || model.section == "榜单" {
                    Picker("题材", selection: $model.topic) {
                        Text("全部题材").tag("")
                        ForEach(model.topics) { Text($0.label).tag($0.id) }
                    }.disabled(model.busy).onChange(of: model.topic) { _ in model.loadCatalog() }
                }
                if model.section == "推荐" { Button("换一批") { model.loadCatalog(refresh: true) }.disabled(model.busy) }
                if model.section == "账户" { AccountPanel(model: model) }
                if model.section == "更新" {
                    Text(updates.status).font(.callout).foregroundStyle(.secondary)
                    Button("检查 Mac 更新") { updates.check() }.disabled(updates.busy)
                    if updates.available != nil { Button("下载并校验更新包") { updates.download() }.disabled(updates.busy) }
                    if updates.busy { ProgressView().controlSize(.small) }
                }
                List {
                    ForEach(displayedRows) { series in
                        Button {
                            if model.section == "账户" && model.cloudRows.first(where: { $0.id == series.id })?.online == false { model.message = "该剧待上线，暂时无法播放" }
                            else { model.open(series, trial: model.section == "推荐") }
                        } label: {
                            HStack(alignment: .top, spacing: 12) {
                                AsyncImage(url: URL(string: series.cover)) { image in image.resizable().scaledToFill() }
                                    placeholder: { Rectangle().fill(.gray.opacity(0.2)) }
                                    .frame(width: 62, height: 86).clipped().cornerRadius(6)
                                VStack(alignment: .leading, spacing: 8) {
                                    Text(series.title).font(.headline).lineLimit(3)
                                    Text(series.badge).font(.caption).foregroundStyle(.secondary)
                                    if model.section == "榜单", let rank = model.rankPositions[series.id] {
                                        Text([rank.rank.map { "第 \($0) 名" } ?? "", rank.heat].filter { !$0.isEmpty }.joined(separator: " · ")).font(.caption).foregroundStyle(.orange)
                                    }
                                    if let record = library.history(series.id) { Text(record.label).font(.caption).foregroundStyle(.orange) }
                                    if model.section == "账户", let cloud = model.cloudRows.first(where: { $0.id == series.id }) {
                                        if let index = cloud.episodeIndex { Text("账号观看至第 \(index + 1) 集").font(.caption).foregroundStyle(.orange) }
                                        if let publish = cloud.publishText { Text(publish).font(.caption).foregroundStyle(.secondary) }
                                    }
                                }
                            }.padding(.vertical, 6).frame(maxWidth: .infinity, alignment: .leading)
                        }.buttonStyle(.plain).disabled(model.busy)
                        .contextMenu {
                            Button(library.favorite(series.id) ? "取消本机收藏" : "加入本机收藏") { library.toggleFavorite(series) }
                            if model.account?.loggedIn == true {
                                Button("加入账户收藏") { model.cloudFavorite(series, add: true) }
                                if model.section == "账户" && model.cloudType == "账户收藏" { Button("取消账户收藏") { model.cloudFavorite(series, add: false) } }
                            }
                            if model.section == "历史" { Button("删除本机观看记录") { library.removeHistory(series.id) } }
                        }
                    }
                    if model.section == "搜索" && model.hasMore { Button("加载更多") { model.search(more: true) }.disabled(model.busy) }
                    if ["找剧", "推荐"].contains(model.section) && model.catalogMore { Button("加载更多") { model.loadCatalog(more: true) }.disabled(model.busy) }
                }.listStyle(.plain)
            }.padding(24).frame(minWidth: 300, idealWidth: 350, maxWidth: 430)
            VStack(alignment: .leading, spacing: 16) {
                if let detail = model.detail {
                    ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                    HStack {
                        Text(detail.series.title).font(.title2.bold()).lineLimit(2)
                        Spacer()
                        if model.preview {
                            Button("上一部") { model.moveRecommendation(-1) }.disabled(model.busy)
                            Button("下一部") { model.moveRecommendation(1) }.disabled(model.busy)
                            Button("观看全集") { model.watchFull() }.disabled(model.busy)
                        } else if let episode = model.episode {
                            Button("下一集") { model.play(episode + 1) }
                                .disabled(model.busy || episode + 1 >= detail.episodes.count)
                        }
                    }
                    HStack {
                        Button(model.resumeLabel) { model.resume() }.disabled(model.busy)
                        Menu("收藏") {
                            Button(library.favorite(detail.series.id) ? "取消本机收藏" : "加入本机收藏") { library.toggleFavorite(detail.series) }
                            if model.account?.loggedIn == true { Button("加入账户收藏") { model.cloudFavorite(detail.series, add: true) } }
                        }
                        Spacer()
                        Picker("倍速", selection: Binding(get: { model.speed }, set: { model.changeSpeed($0) })) {
                            ForEach([Float(0.5), 0.75, 1, 1.25, 1.5, 2], id: \.self) { Text("\($0, specifier: "%g")×").tag($0) }
                        }.frame(width: 130)
                        Picker("清晰度", selection: Binding(get: { model.quality }, set: { model.changeQuality($0) })) {
                            ForEach([480, 720, 1080], id: \.self) { Text("\(String($0))P").tag($0) }
                        }.frame(width: 150).disabled(model.busy)
                    }
                    NativePlayer(player: model.player).frame(height: 300)
                        .background(.black).cornerRadius(12)
                    Text(detail.series.description).foregroundStyle(.secondary).lineLimit(3)
                    HStack {
                        Text("\(model.preview ? "试播不记历史 · 选集进入正式观看" : "选集") · 共 \(detail.episodes.count) 集").font(.headline)
                        Spacer()
                        Toggle("自动下一集", isOn: $model.autoNext)
                            .onChange(of: model.autoNext) { value in UserDefaults.standard.set(value, forKey: "autoNext") }
                    }
                    ScrollView {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 56))], spacing: 10) {
                            ForEach(detail.episodes.indices, id: \.self) { index in
                                Button("\(index + 1)") { model.play(index) }
                                    .buttonStyle(.bordered)
                                    .tint(model.episode == index ? .orange : .gray)
                                    .disabled(model.busy)
                            }
                        }.padding(4)
                    }.frame(minHeight: 100, maxHeight: 180)
                    if !model.related.isEmpty {
                        Text("相关作品").font(.headline)
                        ScrollView(.horizontal) {
                            HStack(spacing: 12) {
                                ForEach(model.related) { work in
                                    Button { if work.upcoming { model.reserve(work) } else { model.open(work.series) } } label: {
                                        VStack(alignment: .leading, spacing: 4) {
                                            Text(work.series.title).lineLimit(2)
                                            Text(work.upcoming ? "待上线 · 预约" : work.season).font(.caption).foregroundStyle(.secondary)
                                        }.frame(width: 150, alignment: .leading)
                                    }.disabled(model.busy || (work.upcoming && model.account?.loggedIn != true))
                                }
                            }
                        }.frame(height: 65)
                    } else if !model.relatedMessage.isEmpty { Text(model.relatedMessage).font(.caption).foregroundStyle(.secondary) }
                    }.padding(.vertical, 4)
                    }
                } else {
                    Spacer()
                    Image(systemName: "play.rectangle").font(.system(size: 64)).foregroundStyle(.orange)
                    Text("找一部想看的剧").font(.title)
                    Text("从左侧搜索结果打开剧集，再选择集数播放。").foregroundStyle(.secondary)
                    Spacer()
                }
                HStack(spacing: 10) {
                    if model.busy { ProgressView().controlSize(.small) }
                    Text(library.error ?? model.message).font(.callout).foregroundStyle(.secondary).textSelection(.enabled)
                }
            }.padding(24).frame(minWidth: 540, maxWidth: .infinity, maxHeight: .infinity)
        }.frame(minWidth: 940, minHeight: 700)
            .tint(.orange).preferredColorScheme(.dark)
    }
}
