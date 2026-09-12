package com.sshtab.pad.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.sshtab.pad.SshPadApp
import com.sshtab.pad.log.SessionLog
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress

/**
 * Bind the SSH/Telnet socket to Wi‑Fi/Ethernet **before connect**, so an
 * always-on VPN cannot steal or reset a LAN session when the app is
 * backgrounded. Also apply TCP keepalive at 5s (the kernel default is 2h).
 */
object ProtectedSocket {
    private const val TAG = "ProtectedSocket"

    fun open(host: String, port: Int, timeoutMs: Int): Socket {
        val socket = Socket()
        prepare(socket, host)
        socket.connect(InetSocketAddress(host, port) as SocketAddress, timeoutMs)
        applyTcpKeepalive(socket)
        return socket
    }

    fun prepare(socket: Socket, host: String) {
        socket.keepAlive = true
        socket.tcpNoDelay = true
        socket.soTimeout = 0
        val network = pickNetwork(host)
        if (network != null) {
            try {
                network.bindSocket(socket)
                SessionLog.event("socket bound to $network host=$host")
            } catch (t: Throwable) {
                SessionLog.event("bindSocket failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        } else {
            SessionLog.event("no matching network for $host, using default route")
        }
    }

    fun applyTcpKeepalive(socket: Socket) {
        try {
            socket.keepAlive = true
            socket.tcpNoDelay = true
            socket.soTimeout = 0
        } catch (_: Throwable) {
        }
        try {
            val pfd = ParcelFileDescriptor.fromSocket(socket)
            val fd = pfd.fileDescriptor
            Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_KEEPALIVE, 1)
            val idle = OsConstants.TCP_KEEPIDLE
            val intvl = OsConstants.TCP_KEEPINTVL
            val cnt = OsConstants.TCP_KEEPCNT
            val proto = OsConstants.IPPROTO_TCP
            if (idle != 0) Os.setsockoptInt(fd, proto, idle, 5)
            if (intvl != 0) Os.setsockoptInt(fd, proto, intvl, 3)
            if (cnt != 0) Os.setsockoptInt(fd, proto, cnt, 8)
            SessionLog.event("TCP_KEEPIDLE=5s KEEPINTVL=3s KEEPCNT=8")
            pfd.close()
        } catch (t: Throwable) {
            SessionLog.event("setsockopt keepalive failed: ${t.javaClass.simpleName}: ${t.message}")
            Log.w(TAG, "setsockopt", t)
        }
    }

    fun pickNetwork(host: String): Network? {
        val ctx: Context = try {
            SshPadApp.instance
        } catch (_: Throwable) {
            return null
        }
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networks = cm.allNetworks
        val lan = isLan(host)
        fun caps(n: Network) = cm.getNetworkCapabilities(n)
        fun isVpn(c: NetworkCapabilities) = c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        fun isWifiOrEth(c: NetworkCapabilities) =
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

        val chosen = if (lan) {
            networks.firstOrNull { n -> caps(n)?.let { isWifiOrEth(it) && !isVpn(it) } == true }
                ?: networks.firstOrNull { n -> caps(n)?.let { isWifiOrEth(it) } == true }
        } else {
            networks.firstOrNull { n ->
                caps(n)?.let {
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !isVpn(it)
                } == true
            } ?: cm.activeNetwork
        }
        SessionLog.event("pickNetwork host=$host lan=$lan -> $chosen")
        return chosen
    }

    fun isLan(host: String): Boolean {
        val h = host.trim().lowercase().removePrefix("[").removeSuffix("]")
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan")) return true
        if (h.startsWith("10.") || h.startsWith("192.168.") || h.startsWith("127.")) return true
        if (h.startsWith("172.")) {
            val second = h.split(".").getOrNull(1)?.toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        if (h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80")) return true
        return false
    }

    fun jschFactory(host: String): com.jcraft.jsch.SocketFactory =
        object : com.jcraft.jsch.SocketFactory {
            override fun createSocket(h: String, port: Int): Socket = open(h, port, 20_000)
            override fun getInputStream(socket: Socket) = socket.getInputStream()
            override fun getOutputStream(socket: Socket) = socket.getOutputStream()
        }

    fun javaxFactory(host: String): javax.net.SocketFactory =
        object : javax.net.SocketFactory() {
            override fun createSocket(): Socket {
                val s = Socket()
                prepare(s, host)
                return s
            }
            override fun createSocket(h: String, port: Int): Socket = open(h, port, 20_000)
            override fun createSocket(h: String, port: Int, localHost: java.net.InetAddress, localPort: Int): Socket =
                open(h, port, 20_000)
            override fun createSocket(addr: java.net.InetAddress, port: Int): Socket =
                open(addr.hostAddress, port, 20_000)
            override fun createSocket(
                addr: java.net.InetAddress,
                port: Int,
                local: java.net.InetAddress,
                localPort: Int,
            ): Socket = open(addr.hostAddress, port, 20_000)
        }
}
