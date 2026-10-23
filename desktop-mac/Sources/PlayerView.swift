// SPDX-License-Identifier: GPL-3.0-only
import SwiftUI
import AVKit

private final class PlaybackWindow: NSWindow {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { true }
}

@MainActor final class FullscreenPlayback: ObservableObject {
    let player: AVPlayer
    @Published var controlsVisible = true
    @Published var position: Double = 0
    @Published var duration: Double = 0
    private var timer: Timer?
    private var observer: Any?
    private var monitor: Any?
    private var window: NSWindow?
    init(player: AVPlayer) { self.player = player }
    func show() {
        guard window == nil else { window?.makeKeyAndOrderFront(nil); return }
        let screen = NSScreen.main ?? NSScreen.screens[0]
        let window = PlaybackWindow(contentRect: screen.frame, styleMask: [.borderless], backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        window.backgroundColor = .black
        window.level = .normal
        window.acceptsMouseMovedEvents = true
        window.contentView = NSHostingView(rootView: FullscreenSurface(playback: self))
        self.window = window
        window.makeKeyAndOrderFront(nil)
        window.toggleFullScreen(nil)
        monitor = NSEvent.addLocalMonitorForEvents(matching: [.mouseMoved, .leftMouseDown, .leftMouseDragged, .keyDown]) { [weak self] event in
            guard let self, event.window === self.window else { return event }
            if event.type == .keyDown && event.keyCode == 53 { self.close(); return nil }
            if event.type == .keyDown && event.keyCode == 49 { self.togglePlayback(); return nil }
            self.revealControls()
            return event
        }
        observer = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.5, preferredTimescale: 600), queue: .main) { [weak self] time in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.position = time.seconds.isFinite ? time.seconds : 0
                let seconds = self.player.currentItem?.duration.seconds ?? 0
                self.duration = seconds.isFinite ? max(0, seconds) : 0
            }
        }
        revealControls()
    }
    func revealControls() {
        controlsVisible = true; timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 2.5, repeats: false) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self else { return }
                if NSEvent.pressedMouseButtons != 0 { self.revealControls() }
                else { self.controlsVisible = false }
            }
        }
    }
    func togglePlayback() {
        if player.rate > 0 { player.pause() } else { player.play() }
        revealControls()
    }
    func close() {
        timer?.invalidate(); timer = nil
        if let observer { player.removeTimeObserver(observer); self.observer = nil }
        if let monitor { NSEvent.removeMonitor(monitor); self.monitor = nil }
        window?.close(); window?.contentView = nil; window = nil
    }
}

private struct CleanVideo: NSViewRepresentable {
    let player: AVPlayer
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
        ZStack(alignment: .bottom) {
            Color.black
            CleanVideo(player: playback.player)
            if playback.controlsVisible {
                HStack(spacing: 18) {
                    Button("播放 / 暂停") { playback.togglePlayback() }
                    Slider(value: Binding(get: { min(playback.position, max(1, playback.duration)) }, set: { value in
                        playback.player.seek(to: CMTime(seconds: value, preferredTimescale: 600))
                        playback.revealControls()
                    }), in: 0...max(1, playback.duration))
                    Text("\(Int(playback.position)) / \(Int(playback.duration)) 秒").monospacedDigit()
                    Button("退出全屏") { playback.close() }
                }
                .padding(20).background(.black.opacity(0.7)).padding(24)
            }
        }.ignoresSafeArea().preferredColorScheme(.dark)
    }
}

@MainActor final class AutoHidingPlayerView: AVPlayerView {
    private var fullscreenPlayback: FullscreenPlayback?
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
        if fullscreenPlayback == nil { fullscreenPlayback = FullscreenPlayback(player: player) }
        fullscreenPlayback?.show()
    }
    func detach() { fullscreenPlayback?.close(); fullscreenPlayback = nil; player = nil }
}
struct NativePlayer: NSViewRepresentable {
    let player: AVPlayer
    func makeNSView(context: Context) -> AutoHidingPlayerView {
        let view = AutoHidingPlayerView(frame: .zero); view.attach(player); return view
    }
    func updateNSView(_ view: AutoHidingPlayerView, context: Context) { view.attach(player) }
    static func dismantleNSView(_ view: AutoHidingPlayerView, coordinator: ()) { view.detach() }
}
