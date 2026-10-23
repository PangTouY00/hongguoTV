// SPDX-License-Identifier: GPL-3.0-only
import SwiftUI
import AVKit

@main struct PlayerControlsTests {
    @MainActor static func main() async throws {
        _ = NSApplication.shared
        let playback = FullscreenPlayback(player: AVPlayer())
        playback.revealControls()
        assert(playback.controlsVisible)
        try await Task.sleep(nanoseconds: 2_900_000_000)
        assert(!playback.controlsVisible, "The complete HUD, including its background, must disappear")
        playback.revealControls(); assert(playback.controlsVisible)
        playback.close()
        let view = AutoHidingPlayerView(frame: .zero)
        assert(!view.showsFullScreenToggleButton)
        view.attach(playback.player); view.detach()
        print("Complete full-screen HUD hiding, reveal and cleanup: PASS")
    }
}
