package com.appathy.shinrigame

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 同じ Wi-Fi 内の複数台をつなぐ中継。
 *
 * ホストが1台、あとは全員そこへつなぐ。
 * ホストは受け取った行を、送ってきた相手以外の全員へ流す。
 *
 * 行は「種類|差出人|中身」の3つに区切る。中身に | が入ってもよいように、
 * 区切りは最初の2つだけを見る。
 *
 * 種類を分けてあるのは、あとで AI の発言やじゃんけんの誘いを
 * 同じ経路に流せるようにするため。
 */
class Hub {

    companion object {
        const val PORT = 47822
        const val SERVICE_TYPE = "_shinrichat._tcp."
        const val SERVICE_NAME = "ShinriChat"

        const val KIND_JOIN = "JOIN"
        const val KIND_LEAVE = "LEAVE"
        const val KIND_MSG = "MSG"
        const val KIND_SYS = "SYS"

        fun pack(kind: String, from: String, body: String): String {
            return kind + "|" + from + "|" + body.replace("\n", " ")
        }

        /** 種類・差出人・中身 の3つに分ける */
        fun unpack(line: String): Triple<String, String, String> {
            val p = line.split("|", limit = 3)
            if (p.size < 3) return Triple("SYS", "", line)
            return Triple(p[0], p[1], p[2])
        }

        fun localIp(): String = Net.localIp()
    }

    private val main = Handler(Looper.getMainLooper())

    private var server: ServerSocket? = null
    private val peers = ArrayList<Peer>()
    private var client: Peer? = null

    private var nsd: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null
    private var resolving = false
    private val pendingResolve = ArrayList<NsdServiceInfo>()

    @Volatile
    private var closed = false

    var isHost = false
        private set

    var onLine: ((String) -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null
    var onFound: ((String, String) -> Unit)? = null

    private class Peer(val socket: Socket) {
        val writer: PrintWriter =
            PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
        val reader: BufferedReader =
            BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
    }

    // ------------------------------------------------------------ 見つける

    fun advertise(ctx: Context) {
        try {
            val m = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
            nsd = m
            val info = NsdServiceInfo()
            info.serviceName = SERVICE_NAME
            info.serviceType = SERVICE_TYPE
            info.port = PORT
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {}
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
            }
            regListener = l
            m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            status("この端末を知らせられませんでした。IP を伝えてください")
        }
    }

    fun discover(ctx: Context) {
        try {
            val m = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
            nsd = m
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(t: String) {}
                override fun onDiscoveryStopped(t: String) {}
                override fun onStartDiscoveryFailed(t: String, code: Int) {
                    status("部屋を探せませんでした。IP を入れてください")
                }

                override fun onStopDiscoveryFailed(t: String, code: Int) {}
                override fun onServiceFound(i: NsdServiceInfo) {
                    if (i.serviceType.contains("shinrichat")) queueResolve(m, i)
                }

                override fun onServiceLost(i: NsdServiceInfo) {}
            }
            discListener = l
            m.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            status("部屋を探せませんでした。IP を入れてください")
        }
    }

    private fun queueResolve(m: NsdManager, info: NsdServiceInfo) {
        synchronized(pendingResolve) {
            pendingResolve.add(info)
            if (resolving) return
            resolving = true
        }
        resolveNext(m)
    }

    private fun resolveNext(m: NsdManager) {
        var next: NsdServiceInfo? = null
        synchronized(pendingResolve) {
            if (pendingResolve.isEmpty()) resolving = false
            else next = pendingResolve.removeAt(0)
        }
        val info = next ?: return
        try {
            m.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, code: Int) {
                    resolveNext(m)
                }

                override fun onServiceResolved(i: NsdServiceInfo) {
                    val ip = i.host?.hostAddress
                    if (ip != null && ip.indexOf(':') < 0) {
                        main.post { onFound?.invoke(i.serviceName ?: SERVICE_NAME, ip) }
                    }
                    resolveNext(m)
                }
            })
        } catch (e: Exception) {
            resolving = false
        }
    }

    // ------------------------------------------------------------ つなぐ

    /** 部屋を開く。何台でも受け付ける。 */
    fun host(ctx: Context) {
        isHost = true
        advertise(ctx)
        Thread {
            try {
                val s = ServerSocket(PORT)
                server = s
                status("部屋を開きました。相手を待っています")
                while (!closed) {
                    val sock = s.accept()
                    sock.tcpNoDelay = true
                    val p = Peer(sock)
                    synchronized(peers) { peers.add(p) }
                    listen(p)
                }
            } catch (e: Exception) {
                if (!closed) status("部屋を開けませんでした")
            }
        }.start()
    }

    /** 部屋に入る */
    fun join(ip: String) {
        isHost = false
        Thread {
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress(InetAddress.getByName(ip), PORT), 6000)
                sock.tcpNoDelay = true
                val p = Peer(sock)
                client = p
                status("部屋に入りました")
                listen(p)
            } catch (e: Exception) {
                status("入れませんでした。IP と Wi-Fi を確かめてください")
            }
        }.start()
    }

    private fun listen(p: Peer) {
        Thread {
            try {
                while (!closed) {
                    val line = p.reader.readLine() ?: break
                    main.post { onLine?.invoke(line) }
                    // ホストは受け取った行を、送り主以外へ配る
                    if (isHost) relay(line, p)
                }
            } catch (e: Exception) {
                // 切断は通常のこと
            }
            if (isHost) {
                synchronized(peers) { peers.remove(p) }
                status("1台が抜けました")
            } else if (!closed) {
                status("部屋との接続が切れました")
            }
        }.start()
    }

    private fun relay(line: String, from: Peer?) {
        synchronized(peers) {
            for (p in peers) {
                if (p === from) continue
                try {
                    p.writer.println(line)
                } catch (e: Exception) {
                }
            }
        }
    }

    /** 自分の発言を流す。ホストは全員へ、参加者はホストへ。 */
    fun send(line: String) {
        Thread {
            if (isHost) {
                relay(line, null)
            } else {
                try {
                    client?.writer?.println(line)
                } catch (e: Exception) {
                    status("送れませんでした")
                }
            }
        }.start()
    }

    val connected: Boolean
        get() = isHost || client != null

    fun peerCount(): Int {
        synchronized(peers) { return peers.size }
    }

    private fun status(msg: String) {
        main.post { onStatus?.invoke(msg) }
    }

    fun close() {
        closed = true
        try {
            val m = nsd
            if (m != null) {
                regListener?.let { m.unregisterService(it) }
                discListener?.let { m.stopServiceDiscovery(it) }
            }
        } catch (e: Exception) {
        }
        regListener = null
        discListener = null
        synchronized(peers) {
            for (p in peers) {
                try {
                    p.socket.close()
                } catch (e: Exception) {
                }
            }
            peers.clear()
        }
        try {
            client?.socket?.close()
        } catch (e: Exception) {
        }
        client = null
        try {
            server?.close()
        } catch (e: Exception) {
        }
        server = null
    }
}
