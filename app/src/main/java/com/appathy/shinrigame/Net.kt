package com.appathy.shinrigame

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
