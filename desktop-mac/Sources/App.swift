// SPDX-License-Identifier: GPL-3.0-only
import SwiftUI
import AVKit

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
            .defaultSize(width: 1200, height: 820)
            .commands { CommandGroup(replacing: .newItem) {} }
    }
    init() {
        NSApplication.shared.setActivationPolicy(.regular)
    }
}
