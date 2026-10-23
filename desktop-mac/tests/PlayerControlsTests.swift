// SPDX-License-Identifier: GPL-3.0-only
import SwiftUI
import AVKit

final class PlayingFixture: AVPlayer {
    var fixtureRate: Float = 1
    override var rate: Float { get { fixtureRate } set { fixtureRate = newValue } }
}
@main struct PlayerControlsTests {
    @MainActor static func main() async throws {
        _ = NSApplication.shared
        let player = PlayingFixture()
        let view = AutoHidingPlayerView(frame: NSRect(x: 0, y: 0, width: 640, height: 360))
        view.attach(player)
        view.playerViewDidEnterFullScreen(view)
        assert(view.controlsStyle != .none)
        try await Task.sleep(nanoseconds: 2_900_000_000)
        assert(view.controlsStyle == .none)
        view.revealControls(); assert(view.controlsStyle != .none)
        view.playerViewWillExitFullScreen(view)
        try await Task.sleep(nanoseconds: 2_900_000_000)
        assert(view.controlsStyle == .inline)
        player.fixtureRate = 0
        view.playerViewDidEnterFullScreen(view)
        try await Task.sleep(nanoseconds: 2_900_000_000)
        assert(view.controlsStyle != .none)
        view.detach()
        print("Full-screen idle hiding, reveal, exit and paused controls: PASS")
    }
}
