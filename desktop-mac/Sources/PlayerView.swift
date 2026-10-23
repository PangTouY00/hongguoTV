// SPDX-License-Identifier: GPL-3.0-only
import SwiftUI
import AVKit

/** AVPlayerView avoids the SwiftUI VideoPlayer initialization crash on this host. */
@MainActor final class AutoHidingPlayerView: AVPlayerView, @preconcurrency AVPlayerViewDelegate {
    private var fullscreen = false
    private var idleTimer: Timer?
    private var eventMonitor: Any?
    private var rateObserver: NSKeyValueObservation?

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        controlsStyle = .inline
        showsFullScreenToggleButton = true
        videoGravity = .resizeAspect
        delegate = self
        eventMonitor = NSEvent.addLocalMonitorForEvents(matching: [.mouseMoved, .leftMouseDown, .leftMouseDragged, .scrollWheel, .keyDown]) { [weak self] event in
            Task { @MainActor [weak self] in
                guard let self, self.fullscreen else { return }
                self.revealControls()
            }
            return event
        }
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }
    func attach(_ player: AVPlayer) {
        guard self.player !== player else { return }
        rateObserver?.invalidate()
        self.player = player
        rateObserver = player.observe(\.rate, options: [.new]) { [weak self] _, _ in
            Task { @MainActor [weak self] in self?.revealControls() }
        }
    }
    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        window?.acceptsMouseMovedEvents = true
    }
    func revealControls() {
        idleTimer?.invalidate(); idleTimer = nil
        if controlsStyle == .none { controlsStyle = .inline }
        guard fullscreen, (player?.rate ?? 0) > 0 else { return }
        idleTimer = Timer.scheduledTimer(withTimeInterval: 2.5, repeats: false) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, self.fullscreen, (self.player?.rate ?? 0) > 0 else { return }
                if NSEvent.pressedMouseButtons != 0 { self.revealControls(); return }
                self.controlsStyle = .none
            }
        }
    }
    func playerViewDidEnterFullScreen(_ playerView: AVPlayerView) {
        fullscreen = true
        window?.acceptsMouseMovedEvents = true
        revealControls()
    }
    func playerViewWillExitFullScreen(_ playerView: AVPlayerView) {
        fullscreen = false; idleTimer?.invalidate(); idleTimer = nil
        controlsStyle = .inline
    }
    func detach() {
        idleTimer?.invalidate(); idleTimer = nil
        rateObserver?.invalidate(); rateObserver = nil
        if let eventMonitor { NSEvent.removeMonitor(eventMonitor); self.eventMonitor = nil }
        player = nil; delegate = nil
    }
    deinit {
        idleTimer?.invalidate()
        if let eventMonitor { NSEvent.removeMonitor(eventMonitor) }
    }
}

struct NativePlayer: NSViewRepresentable {
    let player: AVPlayer
    func makeNSView(context: Context) -> AutoHidingPlayerView {
        let view = AutoHidingPlayerView(frame: .zero)
        view.attach(player)
        return view
    }
    func updateNSView(_ view: AutoHidingPlayerView, context: Context) { view.attach(player) }
    static func dismantleNSView(_ view: AutoHidingPlayerView, coordinator: ()) { view.detach() }
}
