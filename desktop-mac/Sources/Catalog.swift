// SPDX-License-Identifier: GPL-3.0-only
import Foundation

struct Topic: Decodable, Identifiable { let id: String; let label: String }
struct MacRank: Decodable { let rank: Int?; let heat: String }
struct CatalogResult: Decodable { let items: [Series]; let hasMore: Bool; let topics: [Topic]; let positions: [String: MacRank]? }
struct RelatedEntry: Decodable, Identifiable {
    var id: String { series.id }
    let series: Series
    let season: String
    let upcoming: Bool
}
struct RelatedResult: Decodable { let items: [RelatedEntry] }

extension Model {
    func loadSection() {
        if section == "账户" { loadCloud(); return }
        if ["找剧", "榜单", "推荐"].contains(section) {
            if section != "推荐" && category == "ALL" { category = "COMIC" }
            topic = ""; topics = []; loadCatalog()
        }
    }
    func loadCatalog(more: Bool = false, refresh: Bool = false) {
        let kind = section
        guard ["找剧", "榜单", "推荐"].contains(kind), !busy else { return }
        let endpoint = kind == "找剧" ? "catalog" : kind == "榜单" ? "rank" : "feed"
        let selectedCategory = category, selectedTopic = topic, selectedBoard = board
        perform {
            let next = more ? self.catalogPage + 1 : 1
            if !more { self.catalogRows = [] }
            self.message = "正在加载\(kind)…"
            let result: CatalogResult = try await self.request(endpoint, ["category": selectedCategory, "topic": selectedTopic, "board": selectedBoard, "page": String(next), "refresh": String(refresh)])
            guard self.section == kind else { return }
            if more {
                let existing = Set(self.catalogRows.map(\.id))
                self.catalogRows += result.items.filter { !existing.contains($0.id) }
            } else { self.catalogRows = result.items }
            self.catalogMore = result.hasMore; self.catalogPage = next; self.catalogKind = kind
            self.topics = result.topics; self.rankPositions = result.positions ?? [:]
            self.message = "已加载 \(self.catalogRows.count) 部剧"
        }
    }
}
