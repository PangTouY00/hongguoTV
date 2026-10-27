// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

internal data class LanInputAddress(val address: String,val label: String)

/**
 * ConnectivityManager.Network／NetworkCapabilities／LinkProperties 都是 API 21 起的东西，
 * 4.4 上拿不到「当前网络走的是 Wi-Fi 还是网线」。这里直接枚举本机网卡：这台电视能给出的
 * 最准确信息就是网卡名，wlan 开头的记作 Wi-Fi，eth／en 开头的记作网线。
 */
internal object LanInputAddresses {
    fun privateV4(addresses: List<InetAddress>): List<String> = addresses.filter {
        it is Inet4Address && it.isSiteLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress
    }.mapNotNull { it.hostAddress }.distinct()

    private fun label(name: String) = if (name.startsWith("eth") || name.startsWith("en")) "网线网络" else "Wi-Fi"

    fun find(connectivity: android.net.ConnectivityManager): List<LanInputAddress> =
        interfaces().flatMap { iface -> privateV4(addressesOf(iface)).map { LanInputAddress(it,label(iface.name)) } }.distinctBy { it.address }

    private fun interfaces(): List<NetworkInterface> =
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { iface ->
            iface.isUp && !iface.isLoopback && Regex("^(wlan|wifi|wlp|eth|en)[0-9a-z_.-]*$").matches(iface.name)
        }

    private fun addressesOf(iface: NetworkInterface): List<InetAddress> =
        Collections.list(iface.inetAddresses)
}
