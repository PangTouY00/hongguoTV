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
    @Published var navigation = "搜索"
    @Published var showDetail = false
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
    @Published var cloudLoading = false
    @Published var cloudError: String?
    @Published var cloudLoadedKey: String?
    var cloudTask: Task<Void, Never>?
    var cloudRequestID = UUID()
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
    private var pendingResume = false
    private var cloudResume: (Int, Double)?
    private var helper: Process?
    private var input: Pipe?
    private var base: URL?
    private var page = 1
    private var searched = ""
    private var action: Task<Void, Never>?
    private var playerObservation: NSKeyValueObservation?
    private let requestSession: URLSession

    init(resourcesURL: URL? = Bundle.main.resourceURL, library: LocalLibrary? = nil, restoreSession: Bool = true, bridgeURL: URL? = nil, session: URLSession = .shared) {
        self.base = bridgeURL; self.requestSession = session
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
        if base != nil { return }
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
        let (data, response) = try await requestSession.data(for: request)
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
                else if pendingResume && !stopping { pendingResume = false; resume() }
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
            if self.showDetail { self.saveProgress(); self.player.pause() }
            self.section = "搜索"
            self.navigation = "搜索"
            self.showDetail = false
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
            self.detail = detail; self.showDetail = true; self.episode = nil; self.preview = trial; self.pendingTrial = trial; self.pendingResume = !trial
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
        stopping = true; pendingNext = false; pendingTrial = false; pendingResume = false; relatedTask?.cancel(); loginTask?.cancel(); uploadTask?.cancel(); cloudTask?.cancel()
        saveProgress(); action?.cancel()
        if let progressObserver { player.removeTimeObserver(progressObserver); self.progressObserver = nil }
        if let endObserver { NotificationCenter.default.removeObserver(endObserver); self.endObserver = nil }
        player.pause(); player.replaceCurrentItem(with: nil)
        try? input?.fileHandleForWriting.close()
        helper?.terminate(); helper = nil
    }
}
