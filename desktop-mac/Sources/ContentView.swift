// SPDX-License-Identifier: GPL-3.0-only
import SwiftUI
import AVKit

struct ContentView: View {
    @ObservedObject var model: Model
    @ObservedObject var library: LocalLibrary
    @ObservedObject var updates: MacUpdates
    init(model: Model) { self.model = model; library = model.library; updates = model.updates }
    private var items: [Series] {
        switch model.section {
        case "收藏": return library.contents.favorites
        case "历史": return library.contents.history.map(\.series)
        case "找剧", "榜单", "推荐": return model.catalogRows
        case "账户": return model.cloudRows.map(\.series)
        default: return model.rows
        }
    }
    var body: some View {
        NavigationSplitView {
            List(selection: Binding<String?>(get: { model.navigation }, set: { if let value = $0 { model.navigate(value) } })) {
                Section("发现") { ForEach(discoveryNavigation) { Label($0.title, systemImage: $0.icon).tag($0.title) } }
                Section("本机片单") { ForEach(localNavigation) { Label($0.title, systemImage: $0.icon).tag($0.title) } }
                Section("红果账号") { ForEach(accountNavigation) { Label($0.title, systemImage: $0.icon).tag($0.title) } }
                Section { Label("Mac 更新", systemImage: "arrow.down.circle").tag("更新") }
            }.listStyle(.sidebar).disabled(model.busy)
                .navigationTitle("红果")
                .navigationSplitViewColumnWidth(min: 170, ideal: 190, max: 240)
        } detail: {
            VStack(spacing: 0) {
                if model.showDetail, let detail = model.detail {
                    EpisodeView(model: model, library: library, detail: detail)
                } else if model.section == "登录" {
                    VStack(alignment: .leading, spacing: 24) {
                        Text("红果账号").font(.largeTitle.bold())
                        Text("扫码登录后，可查看账号片单和同步正式观看进度。登录态保存在 Mac 钥匙串。")
                            .foregroundStyle(.secondary)
                        AccountPanel(model: model)
                        Spacer()
                    }.padding(32).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                } else if model.section == "更新" {
                    VStack(alignment: .leading, spacing: 20) {
                        Text("Mac 更新").font(.largeTitle.bold())
                        Text(updates.status).foregroundStyle(.secondary)
                        Button("检查 Mac 更新") { updates.check() }.disabled(updates.busy)
                        if updates.available != nil { Button("下载并校验更新包") { updates.download() }.disabled(updates.busy) }
                        if updates.busy { ProgressView().controlSize(.small) }
                        Spacer()
                    }.padding(32).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                } else { libraryView }
                Divider()
                HStack(spacing: 10) {
                    if model.busy || model.cloudLoading { ProgressView().controlSize(.small) }
                    Text(library.error ?? model.message).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                    Spacer()
                    if let account = model.account { Label(account.nickname ?? "已登录", systemImage: "person.crop.circle").font(.caption).foregroundStyle(.secondary) }
                }.padding(.horizontal, 20).padding(.vertical, 10)
            }.navigationTitle(model.showDetail ? (model.detail?.series.title ?? "播放") : model.navigation)
                .toolbar {
                    if model.showDetail {
                        ToolbarItem(placement: .navigation) {
                            Button { model.backToLibrary() } label: { Label("返回片单", systemImage: "chevron.left") }
                        }
                    } else if model.detail != nil {
                        ToolbarItem { Button("返回播放") { model.showDetail = true } }
                    }
                }
                .searchable(text: $model.query, placement: .toolbar, prompt: "搜索全部剧集")
                .onSubmit(of: .search) { model.search() }
        }.frame(minWidth: 1000, minHeight: 720).tint(.orange).preferredColorScheme(.dark)
    }
    private var libraryView: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                VStack(alignment: .leading, spacing: 6) {
                    Text(model.navigation).font(.largeTitle.bold())
                    Text(model.section == "账户" ? "红果账号片单 · 本机记录独立保留" : "\(items.count) 部剧").font(.callout).foregroundStyle(.secondary)
                }
                Spacer()
                if model.section == "账户", model.account != nil {
                    Button { model.loadCloud() } label: { Label("刷新", systemImage: "arrow.clockwise") }.disabled(model.cloudLoading)
                }
                if model.section == "推荐" { Button("换一批") { model.loadCatalog(refresh: true) }.disabled(model.busy) }
            }
            filters
            if model.section == "账户", model.account == nil {
                emptyState("登录后查看账户片单", symbol: "person.crop.circle", detail: "账户历史、收藏与预约会随登录账号切换。") {
                    Button("扫码登录") { model.navigate("账户登录") }
                }
            } else if model.section == "账户", model.cloudLoading {
                VStack(spacing: 16) { ProgressView(); Text("正在读取\(model.cloudType)…").foregroundStyle(.secondary) }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if model.section == "账户", let error = model.cloudError {
                emptyState("账户片单读取失败", symbol: "exclamationmark.arrow.triangle.2.circlepath", detail: error) {
                    Button("重新读取") { model.loadCloud() }
                }
            } else if items.isEmpty {
                emptyState(emptyTitle, symbol: model.section == "搜索" ? "magnifyingglass" : "rectangle.stack", detail: model.section == "搜索" ? "在窗口右上角输入剧名，按回车搜索。" : "可刷新账号片单，或浏览找剧与排行榜。") { EmptyView() }
            } else {
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 155, maximum: 230), spacing: 20)], alignment: .leading, spacing: 24) {
                        ForEach(items) { series in card(series) }
                    }.padding(.top, 4)
                    if model.section == "搜索" && model.hasMore { Button("加载更多") { model.search(more: true) }.disabled(model.busy).padding() }
                    if ["找剧", "推荐"].contains(model.section) && model.catalogMore { Button("加载更多") { model.loadCatalog(more: true) }.disabled(model.busy).padding() }
                }
            }
        }.padding(24).frame(maxWidth: .infinity, maxHeight: .infinity)
    }
    private var emptyTitle: String {
        switch model.section {
        case "账户": return model.cloudLoadedKey == nil ? "准备读取账户片单" : "账号暂无此类记录"
        case "收藏": return "还没有本机收藏"
        case "历史": return "还没有本机观看记录"
        case "搜索": return model.query.isEmpty ? "找一部想看的剧" : "暂无搜索结果"
        default: return model.busy ? "正在加载…" : "暂无内容"
        }
    }
    @ViewBuilder private var filters: some View {
        HStack(spacing: 20) {
            if model.section == "找剧" || model.section == "推荐" {
                Picker("类型", selection: $model.category) {
                    if model.section == "推荐" { Text("综合推荐").tag("ALL") }
                    Text("漫剧").tag("COMIC"); Text("真人剧").tag("REAL")
                    if model.section == "找剧" { Text("AI 剧").tag("AI") }
                }.frame(width: 200).disabled(model.busy).onChange(of: model.category) { _ in model.topic = ""; model.loadCatalog() }
            }
            if model.section == "榜单" {
                Picker("热播榜", selection: $model.board) {
                    Text("综合").tag("ALL"); Text("漫剧").tag("COMIC"); Text("真人剧").tag("REAL"); Text("AI 剧").tag("AI")
                }.frame(width: 200).disabled(model.busy).onChange(of: model.board) { _ in model.topic = ""; model.loadCatalog() }
            }
            if model.section == "找剧" || model.section == "榜单" {
                Picker("题材", selection: $model.topic) {
                    Text("全部题材").tag(""); ForEach(model.topics) { Text($0.label).tag($0.id) }
                }.frame(width: 220).disabled(model.busy).onChange(of: model.topic) { _ in model.loadCatalog() }
            }
            Spacer(minLength: 0)
        }
    }
    private func card(_ series: Series) -> some View {
        let cloud = model.section == "账户" ? model.cloudRows.first(where: { $0.id == series.id }) : nil
        return Button {
            if cloud?.online == false { model.message = "该剧待上线，暂时无法播放" }
            else { model.open(series, trial: model.section == "推荐") }
        } label: {
            VStack(alignment: .leading, spacing: 8) {
                AsyncImage(url: URL(string: series.cover)) { image in image.resizable().scaledToFill() }
                    placeholder: { Rectangle().fill(.gray.opacity(0.18)).overlay(Image(systemName: "film").foregroundStyle(.secondary)) }
                    .frame(height: 225).frame(maxWidth: .infinity).clipped().cornerRadius(10)
                Text(series.title).font(.headline).lineLimit(2).frame(height: 40, alignment: .topLeading)
                Text(series.badge).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                if let cloud, let index = cloud.episodeIndex { Text("账号看到第 \(index + 1) 集").font(.caption).foregroundStyle(.orange) }
                else if let record = library.history(series.id) { Text(record.label).font(.caption).foregroundStyle(.orange) }
                if let publish = cloud?.publishText { Text(publish).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
                if model.section == "榜单", let rank = model.rankPositions[series.id] {
                    Text([rank.rank.map { "第 \($0) 名" } ?? "", rank.heat].filter { !$0.isEmpty }.joined(separator: " · ")).font(.caption).foregroundStyle(.orange)
                }
            }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
        }.buttonStyle(.plain).disabled(model.busy).accessibilityLabel(series.title)
            .contextMenu {
                Button(library.favorite(series.id) ? "取消本机收藏" : "加入本机收藏") { library.toggleFavorite(series) }
                if model.account?.loggedIn == true {
                    Button("加入账户收藏") { model.cloudFavorite(series, add: true) }
                    if model.section == "账户" && model.cloudType == "账户收藏" { Button("取消账户收藏") { model.cloudFavorite(series, add: false) } }
                }
                if model.section == "历史" { Button("删除本机观看记录") { library.removeHistory(series.id) } }
            }
    }
    private func emptyState<Actions: View>(_ title: String, symbol: String, detail: String, @ViewBuilder actions: () -> Actions) -> some View {
        VStack(spacing: 16) {
            Image(systemName: symbol).font(.system(size: 42)).foregroundStyle(.orange)
            Text(title).font(.title2.bold())
            Text(detail).foregroundStyle(.secondary).multilineTextAlignment(.center).frame(maxWidth: 460)
            actions()
        }.frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct EpisodeView: View {
    @ObservedObject var model: Model
    @ObservedObject var library: LocalLibrary
    let detail: Detail
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack(alignment: .top) {
                    Text(detail.series.title).font(.title.bold()).textSelection(.enabled)
                    Spacer()
                    if model.preview {
                        Button("上一部") { model.moveRecommendation(-1) }.disabled(model.busy)
                        Button("下一部") { model.moveRecommendation(1) }.disabled(model.busy)
                        Button("观看全集") { model.watchFull() }.disabled(model.busy)
                    } else if let episode = model.episode {
                        Button("下一集") { model.play(episode + 1) }.disabled(model.busy || episode + 1 >= detail.episodes.count)
                    }
                }
                NativePlayer(player: model.player).frame(height: 380).background(.black).cornerRadius(12)
                HStack(spacing: 16) {
                    Button(model.resumeLabel) { model.resume() }.disabled(model.busy)
                    Menu("收藏") {
                        Button(library.favorite(detail.series.id) ? "取消本机收藏" : "加入本机收藏") { library.toggleFavorite(detail.series) }
                        if model.account?.loggedIn == true { Button("加入账户收藏") { model.cloudFavorite(detail.series, add: true) } }
                    }
                    Spacer()
                    Picker("倍速", selection: Binding(get: { model.speed }, set: { model.changeSpeed($0) })) {
                        ForEach([Float(0.5), 0.75, 1, 1.25, 1.5, 2], id: \.self) { Text("\($0, specifier: "%g")×").tag($0) }
                    }.frame(width: 130)
                    Picker("清晰度", selection: Binding(get: { model.quality }, set: { model.changeQuality($0) })) {
                        ForEach([480, 720, 1080], id: \.self) { Text("\(String($0))P").tag($0) }
                    }.frame(width: 150).disabled(model.busy)
                }
                Text(detail.series.description).foregroundStyle(.secondary).lineLimit(4).textSelection(.enabled)
                HStack {
                    Text("\(model.preview ? "首集试播 · 选集进入正式观看" : "选集") · 共 \(detail.episodes.count) 集").font(.headline)
                    Spacer()
                    Toggle("自动下一集", isOn: $model.autoNext).onChange(of: model.autoNext) { UserDefaults.standard.set($0, forKey: "autoNext") }
                }
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 62))], spacing: 10) {
                        ForEach(detail.episodes.indices, id: \.self) { index in
                            Button("\(index + 1)") { model.play(index) }.buttonStyle(.bordered)
                                .tint(model.episode == index ? .orange : .gray).disabled(model.busy)
                        }
                    }.padding(4)
                }.frame(height: 170)
                if !model.related.isEmpty {
                    Text("相关作品").font(.headline)
                    ScrollView(.horizontal) {
                        HStack(spacing: 12) {
                            ForEach(model.related) { work in
                                Button { if work.upcoming { model.reserve(work) } else { model.open(work.series) } } label: {
                                    VStack(alignment: .leading, spacing: 6) {
                                        Text(work.series.title).lineLimit(2)
                                        Text(work.upcoming ? "待上线 · 预约" : work.season).font(.caption).foregroundStyle(.secondary)
                                    }.frame(width: 180, height: 60, alignment: .leading)
                                }.disabled(model.busy || (work.upcoming && model.account?.loggedIn != true))
                            }
                        }
                    }
                } else if !model.relatedMessage.isEmpty { Text(model.relatedMessage).font(.caption).foregroundStyle(.secondary) }
            }.padding(24)
        }
    }
}
