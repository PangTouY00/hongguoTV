// SPDX-License-Identifier: GPL-3.0-only
import SwiftUI
import AVKit

extension Model {
    func loadHome(more: Bool = false) {
        guard !busy else { return }
        homeGeneration = UUID(); homeWarmTask?.cancel(); homeWarmDetail = nil
        let selected = homeCategory
        let generation = homeGeneration
        perform {
            self.message = "正在读取推荐作品…"
            let page = more ? self.catalogPage + 1 : 1
            let result: CatalogResult = try await self.request("feed", ["category": selected, "page": String(page), "refresh": String(!more)])
            guard self.section == "首页", self.homeGeneration == generation else { return }
            let old = more ? self.catalogRows : []
            let ids = Set(old.map(\.id))
            let added = result.items.filter { !ids.contains($0.id) }
            self.catalogRows = old + added
            self.catalogMore = result.hasMore; self.catalogPage = page
            if let first = added.first { self.pendingHomeSeries = first }
            else { self.message = "暂时没有更多作品，请换一批重试" }
        }
    }
    func warmNextHome() {
        homeWarmTask?.cancel(); homeWarmDetail = nil
        guard section == "首页", preview, let id = detail?.series.id,
              let index = catalogRows.firstIndex(where: { $0.id == id }), catalogRows.indices.contains(index + 1) else { return }
        let series = catalogRows[index + 1], selectedQuality = quality, generation = homeGeneration
        homeWarmTask = Task {
            do {
                let next: Detail = try await request("warm", ["id": series.id, "quality": String(selectedQuality)])
                guard !Task.isCancelled, homeGeneration == generation, quality == selectedQuality else { return }
                homeWarmDetail = next; homeWarmQuality = selectedQuality
            } catch { /* Optional prefetch failure must not interrupt current playback. */ }
        }
    }
    func togglePlayback() {
        if player.rate > 0 { player.pause() } else { player.playImmediately(atRate: speed) }
    }
    func skipPlayback(_ seconds: Double) {
        let now = player.currentTime().seconds
        guard now.isFinite else { return }
        player.seek(to: CMTime(seconds: max(0, now + seconds), preferredTimescale: 600))
    }
}

struct HomeFeedView: View {
    @ObservedObject var model: Model
    @ObservedObject var library: LocalLibrary
    var body: some View {
        VStack(spacing: 18) {
            HStack {
                Text("发现下一部好剧").font(.title2.bold())
                Spacer()
                Picker("推荐频道", selection: $model.homeCategory) {
                    Text("推荐").tag("ALL"); Text("漫剧").tag("COMIC"); Text("真人剧").tag("REAL")
                }.pickerStyle(.segmented).frame(width: 300).disabled(model.busy)
                    .onChange(of: model.homeCategory) { _ in model.loadHome() }
                Button { model.loadHome() } label: { Label("换一批", systemImage: "arrow.clockwise") }.disabled(model.busy)
            }
            if let detail = model.detail, model.showDetail {
                HStack(alignment: .top, spacing: 24) {
                    NativePlayer(player: model.player, model: model).overlay { DanmakuOverlay(model: model, danmaku: model.danmaku) }.background(.black).cornerRadius(16)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                    ScrollView {
                        VStack(alignment: .leading, spacing: 20) {
                            Text("首集试播").font(.caption.bold()).foregroundStyle(.orange)
                            Text(detail.series.title).font(.title2.bold()).textSelection(.enabled)
                            Text(detail.series.badge).foregroundStyle(.secondary)
                            Text(detail.series.description).foregroundStyle(.secondary).lineLimit(8)
                            Button { model.watchFull() } label: {
                                Label("观看全集 · 共 \(detail.episodes.count) 集", systemImage: "play.fill").frame(maxWidth: .infinity)
                            }.buttonStyle(.borderedProminent).controlSize(.large).disabled(model.busy)
                            Menu("收藏作品") {
                                Button(library.favorite(detail.series.id) ? "取消本机收藏" : "加入本机收藏") { library.toggleFavorite(detail.series) }
                                if model.account?.loggedIn == true { Button("加入账户收藏") { model.cloudFavorite(detail.series, add: true) } }
                            }
                            DanmakuControl(danmaku: model.danmaku)
                    Button { model.showComments = true } label: { Label("评论与剧评", systemImage: "text.bubble") }.disabled(model.episode == nil)
                            Divider()
                            HStack {
                                Button { model.moveRecommendation(-1) } label: { Label("上一部", systemImage: "chevron.up") }
                                Button { model.moveRecommendation(1) } label: { Label("下一部", systemImage: "chevron.down") }
                            }.disabled(model.busy)
                            Text("↑ ↓ 切换作品\n空格播放 / 暂停\n← → 快退 / 快进 10 秒").font(.caption).foregroundStyle(.secondary)
                            Text("试播不写入观看历史；观看全集后开始记录进度。")
                                .font(.caption).foregroundStyle(.secondary)
                        }.frame(maxWidth: .infinity, alignment: .leading)
                    }.frame(width: 265)
                }
            } else {
                VStack(spacing: 18) {
                    Image(systemName: "play.rectangle").font(.system(size: 52)).foregroundStyle(.orange)
                    Text(model.busy ? "正在准备试播…" : "看看下一部好故事").font(.title2)
                    Button("开始刷剧") { model.loadHome() }.disabled(model.busy)
                }.frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }.padding(24)
    }
}

struct MyAccountView: View {
    @ObservedObject var model: Model
    @ObservedObject var library: LocalLibrary
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                Text("我的").font(.largeTitle.bold())
                AccountPanel(model: model)
                Text("本机片单").font(.title2.bold())
                HStack(spacing: 16) {
                    tile("本机收藏", icon: "star.fill", subtitle: "\(library.contents.favorites.count) 部作品")
                    tile("本机历史", icon: "clock.fill", subtitle: "\(library.contents.history.count) 部作品")
                }
                Text("红果账号").font(.title2.bold())
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 16) {
                    ForEach(accountNavigation.filter { $0.title != "账户登录" }) { item in
                        tile(item.title, icon: item.icon, subtitle: model.account == nil ? "登录后查看" : "查看账号片单")
                    }
                }
                Text("播放与应用").font(.title2.bold())
                Toggle("正式观看时自动播放下一集", isOn: $model.autoNext)
                    .onChange(of: model.autoNext) { UserDefaults.standard.set($0, forKey: "autoNext") }
                Button("检查 Mac 更新") { model.navigate("更新"); model.updates.check() }
                Text("hongguoMac · \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "测试版")")
                    .font(.caption).foregroundStyle(.secondary)
            }.padding(32).frame(maxWidth: 900, alignment: .leading)
        }.frame(maxWidth: .infinity, maxHeight: .infinity)
    }
    private func tile(_ title: String, icon: String, subtitle: String) -> some View {
        Button { model.navigate(model.account == nil && title.hasPrefix("账户") ? "账户登录" : title) } label: {
            VStack(alignment: .leading, spacing: 10) {
                Label(title, systemImage: icon).font(.headline)
                Text(subtitle).font(.caption).foregroundStyle(.secondary)
            }.padding(20).frame(maxWidth: .infinity, alignment: .leading)
        }.buttonStyle(.bordered).disabled(model.busy)
    }
}
