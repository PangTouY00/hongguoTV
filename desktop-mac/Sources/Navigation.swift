// SPDX-License-Identifier: GPL-3.0-only
import Foundation

struct NavigationItem: Identifiable {
    let title: String
    let icon: String
    var id: String { title }
}
let discoveryNavigation = [NavigationItem(title: "首页", icon: "play.rectangle.fill"), NavigationItem(title: "搜索", icon: "magnifyingglass"), NavigationItem(title: "找剧", icon: "square.grid.2x2"), NavigationItem(title: "榜单", icon: "chart.bar"), NavigationItem(title: "我的", icon: "person.crop.circle")]
let localNavigation = [NavigationItem(title: "本机收藏", icon: "star"), NavigationItem(title: "本机历史", icon: "clock")]
let accountNavigation = [NavigationItem(title: "账户历史", icon: "clock.arrow.circlepath"), NavigationItem(title: "账户收藏", icon: "star.circle"), NavigationItem(title: "已上线预约", icon: "play.rectangle"), NavigationItem(title: "待上线预约", icon: "calendar"), NavigationItem(title: "账户登录", icon: "person.crop.circle")]

extension Model {
    func navigate(_ destination: String) {
        if showDetail { saveProgress(); player.pause() }
        homeGeneration = UUID(); homeWarmTask?.cancel(); homeWarmDetail = nil
        showDetail = false
        navigation = destination
        switch destination {
        case "本机收藏": section = "收藏"
        case "本机历史": section = "历史"
        case "账户历史", "账户收藏": section = "账户"; cloudType = destination
        case "已上线预约": section = "账户"; cloudType = "已上线"
        case "待上线预约": section = "账户"; cloudType = "待上线"
        case "账户登录": section = "登录"
        default: section = destination
        }
        if destination == "首页" { loadHome(); return }
        loadSection()
    }
    func backToLibrary() { saveProgress(); player.pause(); showDetail = false }
}
