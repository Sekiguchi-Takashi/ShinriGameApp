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
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * 同じ Wi-Fi 内で 1 対 1 をつなぐだけの、行区切りのごく単純な通信層。
 *
 * 片方が待ち受け（ホスト）、もう片方が IP を入れてつなぐ（ゲスト）。
 * 勝敗はどちらの端末でも同じ入力から同じ結果になるので、
 * 審判役は置かず、両者が同じ手順で計算する。
 */
class Net {

    companion object {
        const val PORT = 47821

        /** 同じ Wi-Fi 内でこのアプリ同士を見つけるための名前 */
        const val SERVICE_TYPE = "_shinri._tcp."
        const val SERVICE_NAME = "ShinriGame"

        /** 画面に出すための自分の IPv4 アドレス */
        fun localIp(): String {
            try {
                val ifs = NetworkInterface.getNetworkInterfaces()
                while (ifs.hasMoreElements()) {
                    val ni = ifs.nextElement()
                    if (ni.isLoopback || !ni.isUp) continue
                    val addrs = ni.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val a = addrs.nextElement()
                        if (a.isLoopbackAddress) continue
                        val h = a.hostAddress ?: continue
                        if (h.indexOf(':') >= 0) continue
                        return h
                    }
                }
            } catch (e: Exception) {
                return "取得できません"
            }
            return "取得できません"
        }
    }

    private val main = Handler(Looper.getMainLooper())

    private var nsd: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null
    private var resolving = false
    private val pending = ArrayList<NsdServiceInfo>()

    /** 見つけた相手（表示名 → IP） */
    var onFound: ((String, String) -> Unit)? = null

    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null

    @Volatile
    private var closed = false

    var onConnected: (() -> Unit)? = null
    var onMessage: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    val connected: Boolean
        get() = socket != null && !closed

    /**
     * ホストの居場所を Wi-Fi 上に知らせる。
     * IP を読み上げなくても、ゲスト側の一覧に出るようにするため。
     */
    fun advertise(ctx: Context) {
        try {
            val m = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
            nsd = m
            val info = NsdServiceInfo()
            info.serviceName = SERVICE_NAME
            info.serviceType = SERVICE_TYPE
            info.port = PORT

            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {}
                override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {}
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
            }
            regListener = l
            m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            // 見つけてもらえないだけで、IP の手入力は使える
        }
    }

    /** 待ち受けている端末を探す */
    fun discover(ctx: Context) {
        try {
            val m = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
            nsd = m
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) {}
                override fun onDiscoveryStopped(type: String) {}
                override fun onStartDiscoveryFailed(type: String, code: Int) {
                    fail("端末を探せませんでした。IP を入れてつないでください")
                }

                override fun onStopDiscoveryFailed(type: String, code: Int) {}

                override fun onServiceFound(info: NsdServiceInfo) {
                    if (info.serviceType.contains("shinri")) queueResolve(m, info)
                }

                override fun onServiceLost(info: NsdServiceInfo) {}
            }
            discListener = l
            m.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            fail("端末を探せませんでした。IP を入れてつないでください")
        }
    }

    /** 解決は1件ずつしか通らない端末があるので順番に処理する */
    private fun queueResolve(m: NsdManager, info: NsdServiceInfo) {
        synchronized(pending) {
            pending.add(info)
            if (resolving) return
            resolving = true
        }
        resolveNext(m)
    }

    private fun resolveNext(m: NsdManager) {
        var next: NsdServiceInfo? = null
        synchronized(pending) {
            if (pending.isEmpty()) {
                resolving = false
            } else {
                next = pending.removeAt(0)
            }
        }
        val info = next ?: return
        try {
            m.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                    resolveNext(m)
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val ip = info.host?.hostAddress
                    if (ip != null && ip.indexOf(':') < 0) {
                        main.post { onFound?.invoke(info.serviceName ?: SERVICE_NAME, ip) }
                    }
                    resolveNext(m)
                }
            })
        } catch (e: Exception) {
            resolving = false
        }
    }

    private fun stopNsd() {
        val m = nsd ?: return
        try {
            regListener?.let { m.unregisterService(it) }
        } catch (e: Exception) {
        }
        try {
            discListener?.let { m.stopServiceDiscovery(it) }
        } catch (e: Exception) {
        }
        regListener = null
        discListener = null
    }

    /** ホストとして待ち受ける */
    fun host() {
        Thread {
            try {
                val s = ServerSocket(PORT)
                server = s
                val sock = s.accept()
                setup(sock)
            } catch (e: Exception) {
                fail("接続を待てませんでした")
            }
        }.start()
    }

    /** ゲストとしてつなぐ */
    fun join(ip: String) {
        Thread {
            try {
                val sock = Socket()
                sock.connect(java.net.InetSocketAddress(InetAddress.getByName(ip), PORT), 6000)
                setup(sock)
            } catch (e: Exception) {
                fail("つながりませんでした。IP と Wi-Fi を確認してください")
            }
        }.start()
    }

    private fun setup(sock: Socket) {
        try {
            sock.tcpNoDelay = true
            socket = sock
            writer = PrintWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8), true)
            reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
        } catch (e: Exception) {
            fail("接続の準備に失敗しました")
            return
        }

        main.post { onConnected?.invoke() }

        Thread {
            try {
                while (!closed) {
                    val line = reader?.readLine() ?: break
                    main.post { onMessage?.invoke(line) }
                }
                if (!closed) fail("相手との接続が切れました")
            } catch (e: Exception) {
                if (!closed) fail("相手との接続が切れました")
            }
        }.start()
    }

    fun send(line: String) {
        val w = writer ?: return
        Thread {
            try {
                w.println(line)
            } catch (e: Exception) {
                fail("送信できませんでした")
            }
        }.start()
    }

    private fun fail(msg: String) {
        main.post { onError?.invoke(msg) }
    }

    fun close() {
        closed = true
        stopNsd()
        try {
            socket?.close()
        } catch (e: Exception) {
        }
        try {
            server?.close()
        } catch (e: Exception) {
        }
        socket = null
        server = null
    }
}
