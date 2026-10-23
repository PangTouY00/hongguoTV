// SPDX-License-Identifier: GPL-3.0-only
import Foundation
@main struct MacUpdateTests {
    static func main() throws {
        let valid: [String: Any] = ["schema": 1, "bundleID": "com.hongguotv.mac", "build": 3, "version": "0.2.0", "architecture": "arm64", "minMacOS": 13, "fileName": "HongguoTV-mac-arm64-0.2.0-test.zip", "size": 1024, "sha256": String(repeating: "a", count: 64), "notes": "测试"]
        func manifest(_ data: [String: Any]) throws -> MacManifest { try JSONDecoder().decode(MacManifest.self, from: JSONSerialization.data(withJSONObject: data)) }
        try manifest(valid).validate()
        var renamed = valid; renamed["fileName"] = "hongguoMac-arm64-0.2.3-test.zip"
        try manifest(renamed).validate()
        for (key, value): (String, Any) in [("schema", 2), ("bundleID", "com.hongguotv.nativeapp"), ("build", 0), ("fileName", "../bad.zip"), ("architecture", "x86_64"), ("size", 400 * 1024 * 1024), ("sha256", "invalid")] {
            var invalid = valid; invalid[key] = value
            do { try manifest(invalid).validate(); fatalError("Accepted invalid update: \(key)") } catch { }
        }
        print("Mac manifest identity, filename, compatibility and integrity bounds: PASS")
    }
}
