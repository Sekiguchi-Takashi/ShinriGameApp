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

    val HANDS = arrayOf("グー", "チョキ", "パー")

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

    /**
     * z = logit(base) + Σ(weight × x)
     * ロジット空間で加算するため、重みを強くしても 0〜1 を必ず保つ。
     */
    fun lieRate(c: Character, s: Situation): Double {
        var z = logit(c.baseLieRate)
        z += w(c, "stakes") * s.stakes
        z += w(c, "elimination_risk") * s.eliminationRisk
        z += w(c, "score_gap") * s.scoreGap
        z += w(c, "endgame") * s.endgame
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

    /** 予告と異なる手を1つ選ぶ */
    fun otherHand(actual: Int, rnd: Random): Int {
        var h = rnd.nextInt(3)
        while (h == actual) h = rnd.nextInt(3)
        return h
    }
}
