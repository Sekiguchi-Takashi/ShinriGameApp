package com.appathy.shinrigame

import android.content.Context

/**
 * セッションをまたいで蓄積する観測記録。
 *
 * 記録するのは「観測された事実」のみ。断定的な人格ラベルは保存しない。
 * 設計書§27の LongTermMemory に相当する。
 *
 * プレイヤーの行動も同じ形式で id="player" に貯める。
 * プレイヤーが AI を観測するのと同じだけ、AI もプレイヤーを観測する。
 * この対称性がないと「人格メタゲーム」が片側だけの読み合いになる。
 */
class Memory(ctx: Context) {

    private val sp = ctx.getSharedPreferences("shinri_memory", Context.MODE_PRIVATE)

    fun observed(id: String): Int = sp.getInt(id + ".n", 0)
    fun matched(id: String): Int = sp.getInt(id + ".m", 0)
    fun highObserved(id: String): Int = sp.getInt(id + ".hn", 0)
    fun highMatched(id: String): Int = sp.getInt(id + ".hm", 0)

    fun sessions(): Int = sp.getInt("sessions", 0)

    fun matchRate(id: String): Double {
        val n = observed(id)
        if (n == 0) return -1.0
        return matched(id).toDouble() / n.toDouble()
    }

    fun highMatchRate(id: String): Double {
        val n = highObserved(id)
        if (n == 0) return -1.0
        return highMatched(id).toDouble() / n.toDouble()
    }

    fun record(id: String, match: Boolean, high: Boolean) {
        val e = sp.edit()
        e.putInt(id + ".n", observed(id) + 1)
        if (match) e.putInt(id + ".m", matched(id) + 1)
        if (high) {
            e.putInt(id + ".hn", highObserved(id) + 1)
            if (match) e.putInt(id + ".hm", highMatched(id) + 1)
        }
        e.apply()
    }

    fun finishSession() {
        sp.edit().putInt("sessions", sessions() + 1).apply()
    }

    fun clear() {
        sp.edit().clear().apply()
    }

    /** 過去の観測を、今セッションの観測に混ぜるときの上限。古い記録が支配しないようにする。 */
    fun priorCount(id: String): Int {
        val n = observed(id)
        return if (n > 20) 20 else n
    }

    fun priorMatched(id: String): Int {
        val r = matchRate(id)
        if (r < 0.0) return 0
        return Math.round(r * priorCount(id)).toInt()
    }

    /**
     * 過去の記録から見た、そのキャラのプレイヤーへの初期信頼。
     * 予告を守ってきた相手ほど、勧めを聞き入れやすくなる。
     */
    fun initialTrust(): Double {
        val r = matchRate("player")
        if (r < 0.0) return 0.5
        val n = observed("player")
        if (n < 3) return 0.5
        val v = 0.2 + 0.6 * r
        return if (v < 0.0) 0.0 else if (v > 1.0) 1.0 else v
    }

    /** 観測数に応じた確信度。断定を避けるための表示用。 */
    fun confidence(id: String): String {
        val n = observed(id)
        if (n == 0) return "未観測"
        if (n <= 3) return "低"
        if (n <= 9) return "中"
        return "高"
    }
}
