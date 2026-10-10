// OpenMinis Desktop — which local addresses the remote MCP server may listen on (MCP-SERVER.md, "只绑局域网网卡").
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.remote

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Only private IPv4 (10/8, 172.16/12, 192.168/16) on interfaces that are up and not loopback.
 * Never the wildcard (0.0.0.0 / ::), never loopback, never a public, link-local, CGNAT or IPv6 address.
 * Each selected address gets its own listening socket, so nothing else on the machine is reachable.
 */
object LanAddresses {
    data class Nic(val name: String, val up: Boolean, val loopback: Boolean, val addresses: List<InetAddress>)

    /** 10/8, 172.16/12, 192.168/16 only (Inet4Address.isSiteLocalAddress is exactly these three ranges). */
    fun isPrivateV4(a: InetAddress): Boolean = a is Inet4Address && a.isSiteLocalAddress &&
        !a.isAnyLocalAddress && !a.isLoopbackAddress && !a.isLinkLocalAddress && !a.isMulticastAddress

    /** Pure selection, testable with fake interfaces. Result: (interface name, address), de-duplicated, stable order. */
    fun select(nics: List<Nic>): List<Pair<String, Inet4Address>> =
        nics.filter { it.up && !it.loopback }
            .flatMap { n -> n.addresses.filter(::isPrivateV4).map { n.name to (it as Inet4Address) } }
            .distinctBy { it.second }

    fun current(): List<Pair<String, Inet4Address>> = select(
        runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList()).map { ni ->
            Nic(
                name = ni.displayName ?: ni.name,
                up = runCatching { ni.isUp }.getOrDefault(false),
                loopback = runCatching { ni.isLoopback }.getOrDefault(true),
                addresses = ni.inetAddresses.toList(),
            )
        }
    )
}
