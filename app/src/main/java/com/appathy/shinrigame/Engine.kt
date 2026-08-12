package com.appathy.shinrigame

import java.util.Random

data class Situation(
    val stakes: Double,
    val eliminationRisk: Double,
    val scoreGap: Double,
    val endgame: Double
)

object Engine {

    const val ROCK = 0
    const val SCISSORS = 1
    const val PAPER = 2

    /** a が b に勝てば 1、負ければ -1、あいこは 0 */
    fun beats(a: Int, b: Int): Int {
        if (a == b) return 0
        val win = (a == ROCK && b == SCISSORS) ||
            (a == SCISSORS && b == PAPER) ||
            (a == PAPER && b == ROCK)
        return if (win) 1 else -1
    }

    private fun logit(p: Double): Double {
        val q = clamp(p, 0.001, 0.999)
        return Math.log(q / (1.0 - q))
    }

    private fun sigmoid(z: Double): Double {
        return 1.0 / (1.0 + Math.exp(-z))
    }

    fun clamp(v: Double, lo: Double, hi: Double): Double {
        if (v < lo) return lo
        if (v > hi) return hi
        return v
    }

    /** 状況変数の中心。ここを基準に上下させる。 */
    const val CENTER = 0.5

    /**
     * z = logit(base) + Σ(weight × (x - CENTER))
     *
     * ロジット空間で加算するため、重みを強くしても 0〜1 を必ず保つ。
     * 中心化しないと重みが平均そのものをずらしてしまい、
     * base_lie_rate が「平均の嘘率」として機能しなくなる。
     */
    fun lieRate(c: Character, s: Situation): Double {
        var z = logit(c.baseLieRate)
        z += w(c, "stakes") * (s.stakes - CENTER)
        z += w(c, "elimination_risk") * (s.eliminationRisk - CENTER)
        z += w(c, "score_gap") * (s.scoreGap - CENTER)
        z += w(c, "endgame") * (s.endgame - CENTER)
        return sigmoid(z)
    }

    private fun w(c: Character, key: String): Double {
        val v = c.weights[key]
        return v ?: 0.0
    }

    /** 真偽が確定したあとに、その条件付き分布から断言強度を抽選する */
    fun sampleIntensity(c: Character, lying: Boolean, rnd: Random): String {
        val p = if (lying) c.lie else c.honest
        val x = rnd.nextDouble()
        if (x < p.low) return "low"
        if (x < p.low + p.mid) return "mid"
        return "high"
    }

    /** 指定と異なる選択肢を1つ選ぶ */
    fun other(exclude: Int, size: Int, rnd: Random): Int {
        if (size <= 1) return 0
        var h = rnd.nextInt(size)
        while (h == exclude) h = rnd.nextInt(size)
        return h
    }
}
