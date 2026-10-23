// SPDX-License-Identifier: GPL-3.0-only
import Foundation

struct Series: Codable, Identifiable {
    let id: String
    let title: String
    let cover: String
    let description: String
    let badge: String
}
struct SearchResult: Decodable { let items: [Series]; let hasMore: Bool }
struct Detail: Decodable { let series: Series; let episodes: [String] }
struct PlayResult: Decodable { let url: String; let quality: String }
struct BridgeError: Decodable { let error: String }

