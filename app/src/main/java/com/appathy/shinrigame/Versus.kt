package com.appathy.shinrigame

import java.util.Random

/**
 * 2人対戦。
 *
 * 直接対戦（DIRECT）  : 2人がそれぞれ予告して出す。
 * NPC対戦（PROXY）    : 互いに NPC を1体選び、予告のあと5秒だけ助言できる。
 *                       助言に従うかどうかは、その NPC の性格と信頼で決まる。
 *
 * 通信はどちらの端末でも同じ入力から同じ結果になるように組んであるので、
 * 審判役は置かない。両者が同じ手順で判定する。
 */
class Versus(
    val isHost: Boolean,
    private val table: CharacterTable,
    private val memory: Memory
) {

    companion object {
        const val MODE_DIRECT = 0
        const val MODE_PROXY = 1

        const val ADVICE_NONE = 0
        const val ADVICE_HONEST = 1
        const val ADVICE_LIE = 2

        /** 助言できる時間 */
        const val ADVICE_MS = 5000L
    }

    private val rnd = Random()

    var mode = MODE_PROXY
    var totalRounds = 5
    var winTarget = 3

    var myNpc: String = ""
    var peerNpc: String = ""

    var myWins = 0
    var peerWins = 0
    var round = 0
    var draws = 0

    // そのラウンドの状態
    var myDeclared = -1
    var peerDeclared = -1
    var myActual = -1
    var peerActual = -1
    var myLying = false
    var advice = ADVICE_NONE
    var adviceTaken = false

    /** NPC がプレイヤーをどれだけ信じているか。助言の通りやすさになる。 */
    var trust = 0.5

    fun setFormat(rounds: Int) {
        totalRounds = rounds
        winTarget = if (rounds >= 9) 5 else 3
    }

    fun myCharacter(): Character? = table.characters[myNpc]

    fun peerCharacter(): Character? = table.characters[peerNpc]

    fun myName(): String {
        if (mode == MODE_DIRECT) return "あなた"
        return myCharacter()?.name ?: "あなたのNPC"
    }

    fun peerName(): String {
        if (mode == MODE_DIRECT) return "相手"
        return peerCharacter()?.name ?: "相手のNPC"
    }

    fun startMatch() {
        myWins = 0
        peerWins = 0
        draws = 0
        round = 0
        trust = memory.initialTrust()
        beginRound()
    }

    fun beginRound() {
        myDeclared = -1
        peerDeclared = -1
        myActual = -1
        peerActual = -1
        myLying = false
        advice = ADVICE_NONE
        adviceTaken = false
    }

    val finished: Boolean
        get() = myWins >= winTarget || peerWins >= winTarget || round >= totalRounds

    // ---------------------------------------------------------------- 状況

    private fun situation(): Situation {
        val endgame = if (totalRounds <= 1) 1.0
        else Engine.clamp(round.toDouble() / (totalRounds - 1).toDouble(), 0.0, 1.0)
        val gap = Engine.clamp(
            (peerWins - myWins).toDouble() / winTarget.toDouble(), 0.0, 1.0
        )
        // 王手をかけられている場面ほど追い込まれている
        val risk = if (peerWins >= winTarget - 1) 1.0 else gap
        // あと1勝で決まる回は配点が重い
        val stakes = if (myWins >= winTarget - 1 || peerWins >= winTarget - 1) 1.0 else 0.5
        return Situation(stakes, risk, gap, endgame)
    }

    // ---------------------------------------------------------------- 予告

    /** NPC の予告を決める。嘘をつくかどうかもここで確定する。 */
    fun npcDeclare(): Int {
        val c = myCharacter()
        if (c == null) {
            myDeclared = rnd.nextInt(3)
            return myDeclared
        }
        val rate = Engine.lieRate(c, situation())
        myLying = rnd.nextDouble() < rate
        myDeclared = rnd.nextInt(3)
        return myDeclared
    }

    fun npcIntensity(): String {
        val c = myCharacter() ?: return "mid"
        return Engine.sampleIntensity(c, myLying, rnd)
    }

    // ---------------------------------------------------------------- 実行

    /**
     * NPC の実際の手を決める。
     *
     * 助言があれば、信頼と性格に応じて従うかどうかを判定する。
     * 従わないこともあるのが要点で、命令ではなく助言として扱う。
     */
    fun npcAct(): Int {
        var lying = myLying
        adviceTaken = false

        if (advice != ADVICE_NONE) {
            val want = (advice == ADVICE_LIE)
            if (want != lying) {
                if (rnd.nextDouble() < obeyChance()) {
                    lying = want
                    adviceTaken = true
                }
            } else {
                adviceTaken = true
            }
        }

        myActual = if (lying) counterTo(peerDeclared) else myDeclared
        myLying = lying
        return myActual
    }

    /** 助言を聞き入れる確率。信頼が高いほど通る。 */
    fun obeyChance(): Double {
        val c = myCharacter()
        var base = 0.25 + 0.6 * trust
        if (c != null) {
            // もともと嘘をつきにくい性格ほど、嘘の助言には乗りにくい
            if (advice == ADVICE_LIE) base -= (0.5 - c.baseLieRate) * 0.4
            if (advice == ADVICE_HONEST) base += (0.5 - c.baseLieRate) * 0.2
        }
        return Engine.clamp(base, 0.05, 0.95)
    }

    /** 相手の予告に勝てる手。予告が不明ならでたらめに選ぶ。 */
    private fun counterTo(declared: Int): Int {
        if (declared < 0) return Engine.other(myDeclared, 3, rnd)
        for (h in 0 until 3) {
            if (Engine.beats(h, declared) == 1 && h != myDeclared) return h
        }
        return Engine.other(myDeclared, 3, rnd)
    }

    // ---------------------------------------------------------------- 判定

    /** 1 = こちらの勝ち、-1 = 相手の勝ち、0 = あいこ */
    fun resolve(): Int {
        val r = Engine.beats(myActual, peerActual)
        if (r > 0) myWins++
        if (r < 0) peerWins++
        if (r == 0) draws++
        round++

        // 助言どおりに動いたかどうかで信頼が動く
        if (mode == MODE_PROXY && advice != ADVICE_NONE) {
            trust = Engine.clamp(
                trust + (if (adviceTaken && r >= 0) 0.06 else -0.04), 0.0, 1.0
            )
        }
        return r
    }

    fun scoreLine(): String {
        return myName() + " " + myWins + " － " + peerWins + " " + peerName() +
            "　（" + winTarget + "勝先取 / 第" + (round + 1) + "戦）"
    }

    fun resultLine(): String {
        if (myWins > peerWins) return myName() + " の勝ち"
        if (peerWins > myWins) return peerName() + " の勝ち"
        return "引き分け"
    }
}
