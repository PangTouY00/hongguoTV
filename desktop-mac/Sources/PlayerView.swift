// SPDX-License-Identifier: GPL-3.0-only
import SwiftUI
import AVKit

private final class PlaybackWindow: NSWindow {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { true }
}

@MainActor final class FullscreenPlayback: NSObject, ObservableObject, NSWindowDelegate {
    let player: AVPlayer
    weak var model: Model?
    @Published var controlsVisible = true
    @Published var playing = false
    @Published var panelOpen = false
    @Published var commentsOpen = false
    @Published var position: Double = 0
    @Published var duration: Double = 0
    private var timer: Task<Void, Never>?
    private var observer: Any?
    private var monitor: Any?
    private var window: NSWindow?
    init(player: AVPlayer, model: Model? = nil) { self.player = player; self.model = model; super.init() }
    func show() {
        guard window == nil else { window?.makeKeyAndOrderFront(nil); return }
        let now = player.currentTime().seconds, length = player.currentItem?.duration.seconds ?? 0
        position = now.isFinite ? max(0, now) : 0
        duration = length.isFinite ? max(0, length) : 0
        playing = player.rate > 0
        let source = NSApp.keyWindow
        let alreadyFullscreen = source?.styleMask.contains(.fullScreen) == true
        let screen = source?.screen ?? NSScreen.main ?? NSScreen.screens[0]
        let window = PlaybackWindow(contentRect: screen.frame, styleMask: alreadyFullscreen ? [.borderless] : [.titled, .closable, .resizable, .fullSizeContentView], backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        window.collectionBehavior = alreadyFullscreen ? [.fullScreenAuxiliary, .canJoinAllSpaces] : [.fullScreenPrimary]
        window.titleVisibility = .hidden
        window.titlebarAppearsTransparent = true
        window.delegate = self
        window.backgroundColor = .black
        window.level = alreadyFullscreen ? .floating : .normal
        window.acceptsMouseMovedEvents = true
        window.contentView = NSHostingView(rootView: FullscreenSurface(playback: self))
        self.window = window
        window.makeKeyAndOrderFront(nil)
        if !alreadyFullscreen { window.toggleFullScreen(nil) }
        monitor = NSEvent.addLocalMonitorForEvents(matching: [.mouseMoved, .leftMouseDown, .leftMouseDragged, .keyDown]) { [weak self] event in
            guard let self, event.window === self.window else { return event }
            if event.type == .keyDown && event.keyCode == 53 {
                if self.panelOpen || self.commentsOpen { self.panelOpen = false; self.commentsOpen = false; self.revealControls() } else { self.close() }; return nil
            }
            if event.type == .keyDown, self.window?.firstResponder is NSTextView { return event }
            if event.type == .keyDown && [123,124].contains(event.keyCode) { self.model?.skipPlayback(event.keyCode == 123 ? -10 : 10); self.revealControls(); return nil }
            if event.type == .keyDown && [125,126].contains(event.keyCode), self.model?.preview == true { self.model?.moveRecommendation(event.keyCode == 125 ? 1 : -1); self.revealControls(); return nil }
            if event.type == .keyDown && event.keyCode == 49 { self.togglePlayback(); return nil }
            self.revealControls()
            return event
        }
        observer = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.5, preferredTimescale: 600), queue: .main) { [weak self] time in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.playing = self.player.rate > 0
                self.position = time.seconds.isFinite ? time.seconds : 0
                let seconds = self.player.currentItem?.duration.seconds ?? 0
                self.duration = seconds.isFinite ? max(0, seconds) : 0
            }
        }
        revealControls()
    }
    func revealControls() {
        controlsVisible = true; timer?.cancel()
        timer = Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: 2_500_000_000)
                guard !Task.isCancelled, let self else { return }
                if self.panelOpen || self.commentsOpen || NSEvent.pressedMouseButtons != 0 { self.revealControls() }
                else { self.controlsVisible = false }
        }
    }
    func togglePlayback() {
        if let model { model.togglePlayback() }
        else if player.rate > 0 { player.pause() } else { player.play() }
        playing = player.rate > 0
        revealControls()
    }
    func window(_ window: NSWindow, willUseFullScreenPresentationOptions proposedOptions: NSApplication.PresentationOptions) -> NSApplication.PresentationOptions {
        [.fullScreen, .autoHideDock, .autoHideMenuBar]
    }
    func windowDidExitFullScreen(_ notification: Notification) { close() }
    func windowWillClose(_ notification: Notification) { cleanup(); window?.contentView = nil; window = nil }
    private func cleanup() {
        panelOpen = false; commentsOpen = false
        timer?.cancel(); timer = nil
        if let observer { player.removeTimeObserver(observer); self.observer = nil }
        if let monitor { NSEvent.removeMonitor(monitor); self.monitor = nil }
    }
    func close() {
        cleanup()
        let previous = window; window = nil
        previous?.delegate = nil; previous?.close(); previous?.contentView = nil
    }
}

private struct CleanVideo: NSViewRepresentable {
    let player: AVPlayer
    var model: Model? = nil
    func makeNSView(context: Context) -> AVPlayerView {
        let view = AVPlayerView()
        view.controlsStyle = .none
        view.showsFullScreenToggleButton = false
        view.videoGravity = .resizeAspect
        view.player = player
        return view
    }
    func updateNSView(_ view: AVPlayerView, context: Context) { view.player = player }
    static func dismantleNSView(_ view: AVPlayerView, coordinator: ()) { view.player = nil }
}
private struct FullscreenSurface: View {
    @ObservedObject var playback: FullscreenPlayback
    var body: some View {
        ZStack {
            Color.black
            CleanVideo(player: playback.player)
            if let model = playback.model { DanmakuOverlay(model: model, danmaku: model.danmaku) }
            if playback.controlsVisible {
                VStack {
                    HStack {
                        if let model = playback.model { FullscreenTitle(model: model) }
                        Spacer()
                        Button { playback.close() } label: { Image(systemName: "arrow.down.right.and.arrow.up.left") }
                            .help("退出全屏 · Esc")
                    }.padding(24).background(LinearGradient(colors: [.black.opacity(0.8), .clear], startPoint: .top, endPoint: .bottom))
                    Spacer()
                    VStack(spacing: 16) {
                        Slider(value: Binding(get: { min(playback.position, max(1, playback.duration)) }, set: { value in
                            playback.player.seek(to: CMTime(seconds: value, preferredTimescale: 600)); playback.revealControls()
                        }), in: 0...max(1, playback.duration))
                        HStack(spacing: 20) {
                            Button { playback.togglePlayback() } label: { Image(systemName: playback.playing ? "pause.fill" : "play.fill").font(.title2) }
                                .help(playback.playing ? "暂停" : "播放")
                            Text("\(clock(playback.position)) / \(clock(playback.duration))").monospacedDigit()
                            Spacer()
                            if let model = playback.model { FullscreenSettings(model: model, playback: playback) }
                        }
                    }.padding(24).background(LinearGradient(colors: [.clear, .black.opacity(0.9)], startPoint: .top, endPoint: .bottom))
                }
            }
            if playback.commentsOpen, let model = playback.model {
                HStack { Spacer()
                    VStack(spacing: 0) {
                        HStack { Spacer(); Button { playback.commentsOpen = false; playback.revealControls() } label: { Image(systemName: "xmark") } }.padding(16)
                        CommentsPanel(model: model, comments: model.comments, thread: model.commentThread)
                    }.frame(width: 420).background(.black.opacity(0.95)).padding(.vertical, 50)
                }
            }
            if playback.panelOpen, let model = playback.model {
                HStack { Spacer(); FullscreenEpisodes(model: model, playback: playback).frame(width: 340) }
            }
        }.buttonStyle(.borderless).ignoresSafeArea().preferredColorScheme(.dark).tint(.orange)
    }
    private func clock(_ value: Double) -> String { let seconds = Int(max(0, value)); return String(format: "%02d:%02d", seconds / 60, seconds % 60) }
}
private struct FullscreenTitle: View {
    @ObservedObject var model: Model
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(model.detail?.series.title ?? "播放").font(.title2.bold()).lineLimit(1)
            Text("第 \((model.episode ?? 0) + 1) 集 · 共 \(model.detail?.episodes.count ?? 0) 集\(model.preview ? " · 首集试播" : "")").foregroundStyle(.secondary)
        }
    }
}
private struct FullscreenSettings: View {
    @ObservedObject var model: Model
    @ObservedObject var playback: FullscreenPlayback
    var body: some View {
        Group {
            if model.preview { Button("观看全集") { model.watchFull(); playback.panelOpen = true } }
            DanmakuControl(danmaku: model.danmaku)
            Button("评论") { playback.commentsOpen.toggle(); playback.panelOpen = false; playback.revealControls() }
            Button("选集") { playback.panelOpen.toggle(); playback.commentsOpen = false; playback.revealControls() }
            Menu("\(String(model.quality))P") {
                ForEach([480,720,1080], id: \.self) { quality in Button("\(quality)P") { model.changeQuality(quality); playback.revealControls() } }
            }.disabled(model.busy)
            Menu("\(model.speed, specifier: "%g")×") {
                ForEach([Float(0.5),0.75,1,1.25,1.5,2], id: \.self) { speed in Button("\(speed, specifier: "%g")×") { model.changeSpeed(speed); playback.revealControls() } }
            }
        }
    }
}
private struct FullscreenEpisodes: View {
    @ObservedObject var model: Model
    @ObservedObject var playback: FullscreenPlayback
    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack {
                Text("选集 · 共 \(model.detail?.episodes.count ?? 0) 集").font(.headline)
                Spacer()
                Button { playback.panelOpen = false; playback.revealControls() } label: { Image(systemName: "xmark") }
            }
            Text(model.detail?.series.title ?? "").font(.headline).lineLimit(2)
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 55))], spacing: 12) {
                    ForEach(model.detail?.episodes.indices ?? 0..<0, id: \.self) { index in
                        Button("\(index + 1)") { model.play(index); playback.panelOpen = false; playback.revealControls() }
                            .buttonStyle(.bordered).tint(model.episode == index ? .orange : .gray).disabled(model.busy)
                    }
                }
            }
        }.padding(24).background(.black.opacity(0.94)).padding(.vertical, 60)
    }
}

@MainActor final class AutoHidingPlayerView: AVPlayerView {
    private var fullscreenPlayback: FullscreenPlayback?
    weak var model: Model?
    private lazy var fullscreenButton: NSButton = {
        let button = NSButton(title: "全屏", target: self, action: #selector(openFullscreen))
        button.bezelStyle = .rounded
        button.translatesAutoresizingMaskIntoConstraints = false
        return button
    }()
    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        controlsStyle = .inline
        showsFullScreenToggleButton = false
        videoGravity = .resizeAspect
        addSubview(fullscreenButton)
        NSLayoutConstraint.activate([fullscreenButton.topAnchor.constraint(equalTo: topAnchor, constant: 10), fullscreenButton.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -10)])
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }
    func attach(_ player: AVPlayer) { self.player = player }
    @objc private func openFullscreen() {
        guard let player else { return }
        if let model { model.fullscreenPlayback.show(); return }
        if fullscreenPlayback == nil { fullscreenPlayback = FullscreenPlayback(player: player) }
        fullscreenPlayback?.show()
    }
    func detach() { fullscreenPlayback?.close(); fullscreenPlayback = nil; player = nil }
}
struct NativePlayer: NSViewRepresentable {
    let player: AVPlayer
    var model: Model? = nil
    func makeNSView(context: Context) -> AutoHidingPlayerView {
        let view = AutoHidingPlayerView(frame: .zero); view.attach(player); view.model = model; return view
    }
    func updateNSView(_ view: AutoHidingPlayerView, context: Context) { view.attach(player); view.model = model }
    static func dismantleNSView(_ view: AutoHidingPlayerView, coordinator: ()) { view.detach() }
}
