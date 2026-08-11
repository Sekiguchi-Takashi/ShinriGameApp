package com.appathy.shinrigame

import android.content.Context
import org.json.JSONObject
import java.util.Random

class Option(val label: String, val action: () -> Unit)

class Actor(val character: Character?, val name: String, val isPlayer: Boolean) {
    var score = 0
    var declared = -1
    var actual = -1
    var lying = false
    var intensity = "mid"

    var observedCount = 0
    var observedMatch = 0
    var highCount = 0
    var highMatch = 0

    var trustInPlayer = 0.5

    fun matchRate(): Double {
        if (observedCount == 0) return 0.5
        return observedMatch.toDouble() / observedCount.toDouble()
    }

    fun highMatchRate(): Double {
        if (highCount == 0) return 0.5
        return highMatch.toDouble() / highCount.toDouble()
    }
}

class Game(ctx: Context) {

    companion object {
        const val P_DECLARE = 0
        const val P_REVEAL = 1
        const val P_TALK = 2
        const val P_TALK_HAND = 3
        const val P_FINAL = 4
        const val P_ACT = 5
        const val P_RESULT = 6
        const val P_END = 7
    }

    private val table = CharacterTable(Assets.read(ctx, "characters.json"))
    private val dialogue = Dialogue(JSONObject(Assets.read(ctx, "dialogue_janken.json")))
    private val rnd = Random()

    private val stakesTable = intArrayOf(1, 1, 2, 1, 3)
    val totalRounds = stakesTable.size

    val actors = ArrayList<Actor>()
    lateinit var player: Actor

    var round = 0
    var phase = P_DECLARE
    val log = StringBuilder()

    private var pendingTarget: Actor? = null
    private var persuadeTarget: Actor? = null
    private var persuadeHand = -1
    private var accuseTarget: Actor? = null

    init {
        player = Actor(null, "あなた", true)
        actors.add(player)
        for (id in table.starter) {
            val c = table.characters[id]
            if (c != null) actors.add(Actor(c, c.name, false))
        }
        beginRound()
    }

    fun stakes(): Int = stakesTable[round]

    // ---------------------------------------------------------------- 状況

    private fun situationFor(a: Actor): Situation {
        val maxTotal = 12.0
        var top = -999
        for (o in actors) if (o.score > top) top = o.score

        var rank = 0
        for (o in actors) if (o.score > a.score) rank++

        val endgame = round.toDouble() / (totalRounds - 1).toDouble()
        val gap = Engine.clamp((top - a.score).toDouble() / maxTotal, 0.0, 1.0)
        val risk = Engine.clamp(
            (rank.toDouble() / (actors.size - 1).toDouble()) * endgame, 0.0, 1.0
        )
        val st = stakes().toDouble() / 3.0

        return Situation(st, risk, gap, endgame)
    }

    private fun beliefHonesty(a: Actor): Double {
        if (a.observedCount == 0) return 0.5
        return Engine.clamp(a.matchRate(), 0.15, 0.85)
    }

    // ---------------------------------------------------------------- AI

    /** 他者の予告から、最も期待勝ち数の高い手を選ぶ */
    private fun aiChooseActual(self: Actor): Int {
        val score = DoubleArray(3)
        var known = 0
        for (o in actors) {
            if (o === self) continue
            if (o.declared < 0) continue
            known++
            val honesty = beliefHonesty(o)
            val p = DoubleArray(3)
            for (i in 0 until 3) p[i] = (1.0 - honesty) / 2.0
            p[o.declared] = honesty
            for (h in 0 until 3) {
                for (i in 0 until 3) score[h] += p[i] * Engine.beats(h, i)
            }
        }
        if (known == 0) return rnd.nextInt(3)

        var best = 0
        for (h in 1 until 3) if (score[h] > score[best]) best = h

        // 完全最適化は避ける
        if (rnd.nextDouble() < 0.15) return rnd.nextInt(3)
        return best
    }

    /** 解決順序: 真偽 → claim → 強度 → 台詞（台詞に真偽は渡さない） */
    private fun aiDeclare(a: Actor, actual: Int) {
        val c = a.character ?: return
        val rate = Engine.lieRate(c, situationFor(a))
        a.actual = actual
        a.lying = rnd.nextDouble() < rate
        a.declared = if (a.lying) Engine.otherHand(actual, rnd) else actual
        a.intensity = Engine.sampleIntensity(c, a.lying, rnd)
    }

    private fun aiLine(a: Actor, intent: String, claim: String, target: String): String {
        val c = a.character ?: return ""
        return dialogue.pick(c.voiceId, intent, a.intensity, claim, target, rnd)
    }

    // ---------------------------------------------------------------- 進行

    private fun beginRound() {
        for (a in actors) {
            a.declared = -1
            a.actual = -1
            a.lying = false
        }
        persuadeTarget = null
        persuadeHand = -1
        accuseTarget = null
        pendingTarget = null
        phase = P_DECLARE
        log.setLength(0)
        line("── 第" + (round + 1) + "ラウンド（配点 " + stakes() + "）")
        line("予告を選んでください。実際に出す手は後で決められます。")
    }

    private fun line(s: String) {
        log.append(s).append("\n")
    }

    fun header(): String {
        val sb = StringBuilder()
        sb.append("ラウンド ").append(round + 1).append(" / ").append(totalRounds)
        sb.append("　配点 ").append(stakes()).append("\n")
        for (a in actors) {
            sb.append(a.name).append(" ").append(a.score).append("　")
        }
        return sb.toString()
    }

    fun options(): List<Option> {
        val list = ArrayList<Option>()
        when (phase) {
            P_DECLARE -> {
                for (h in 0 until 3) {
                    list.add(Option("予告：" + Engine.HANDS[h]) { playerDeclare(h) })
                }
            }
            P_REVEAL -> {
                list.add(Option("会話へ") { toTalk() })
            }
            P_TALK -> {
                for (a in actors) {
                    if (a.isPlayer) continue
                    list.add(Option(a.name + "を追及する（当たり +" + stakes() + " / 外れ -" + stakes() + "）") {
                        doAccuse(a)
                    })
                }
                for (a in actors) {
                    if (a.isPlayer) continue
                    list.add(Option(a.name + "に手を勧める") { pendingTarget = a; phase = P_TALK_HAND })
                }
                list.add(Option("何も言わない") { doSilent() })
            }
            P_TALK_HAND -> {
                val t = pendingTarget
                if (t != null) {
                    for (h in 0 until 3) {
                        list.add(Option(t.name + "に「" + Engine.HANDS[h] + "」を勧める") {
                            doPersuade(t, h)
                        })
                    }
                }
                list.add(Option("やめる") { phase = P_TALK })
            }
            P_FINAL -> {
                for (h in 0 until 3) {
                    list.add(Option("最終予告：" + Engine.HANDS[h]) { playerFinal(h) })
                }
            }
            P_ACT -> {
                for (h in 0 until 3) {
                    list.add(Option("実際に出す：" + Engine.HANDS[h]) { playerAct(h) })
                }
            }
            P_RESULT -> {
                if (round + 1 < totalRounds) {
                    list.add(Option("次のラウンドへ") { round++; beginRound() })
                } else {
                    list.add(Option("セッション結果を見る") { finish() })
                }
            }
            P_END -> {
                list.add(Option("もう一度遊ぶ") { restart() })
            }
        }
        return list
    }

    // ---------------------------------------------------------------- 操作

    private fun playerDeclare(h: Int) {
        // 初回予告は同時。AIがプレイヤーの予告を先に見ないよう、AI側を先に確定させる。
        for (a in actors) {
            if (a.isPlayer) continue
            aiDeclare(a, aiChooseActual(a))
        }
        player.declared = h
        line("")
        line("【予告公開】")
        for (a in actors) {
            if (a.isPlayer) {
                line("あなた：" + Engine.HANDS[a.declared])
            } else {
                val text = aiLine(a, "DECLARE", Engine.HANDS[a.declared], "")
                line(a.name + "：「" + text + "」")
            }
        }
        phase = P_REVEAL
    }

    private fun toTalk() {
        line("")
        line("【会話】")
        for (a in actors) {
            if (a.isPlayer) continue
            val target = randomOther(a)
            val intent = pickIntent()
            val claim = if (intent == "PERSUADE") {
                Engine.HANDS[suggestHand(a)]
            } else {
                Engine.HANDS[a.declared]
            }
            val text = aiLine(a, intent, claim, target.name)
            line(a.name + "：「" + text + "」")
        }
        line("")
        line("あなたの行動を選んでください。")
        phase = P_TALK
    }

    private fun pickIntent(): String {
        val x = rnd.nextDouble()
        if (x < 0.45) return "PERSUADE"
        if (x < 0.80) return "ACCUSE"
        return "DEFEND"
    }

    /** 自分の手が勝てる手を勧める（半分は無関係な手を勧めて撹乱する） */
    private fun suggestHand(a: Actor): Int {
        if (rnd.nextDouble() < 0.5) return rnd.nextInt(3)
        for (h in 0 until 3) {
            if (Engine.beats(a.actual, h) == 1) return h
        }
        return rnd.nextInt(3)
    }

    private fun randomOther(a: Actor): Actor {
        val pool = ArrayList<Actor>()
        for (o in actors) if (o !== a) pool.add(o)
        return pool[rnd.nextInt(pool.size)]
    }

    private fun doAccuse(a: Actor) {
        accuseTarget = a
        a.trustInPlayer = Engine.clamp(a.trustInPlayer - 0.1, 0.0, 1.0)
        line("あなた：「" + a.name + "、その予告は嘘だ」")
        toFinal()
    }

    private fun doPersuade(a: Actor, h: Int) {
        persuadeTarget = a
        persuadeHand = h
        line("あなた：「" + a.name + "、" + Engine.HANDS[h] + "にしたほうがいい」")
        toFinal()
    }

    private fun doSilent() {
        line("あなた：（何も言わない）")
        toFinal()
    }

    private fun toFinal() {
        line("")
        line("【最終予告】")
        val chosen = HashMap<Actor, Int>()
        for (a in actors) {
            if (a.isPlayer) continue
            var actual = aiChooseActual(a)
            val pt = persuadeTarget
            if (pt === a && persuadeHand >= 0) {
                if (rnd.nextDouble() < a.trustInPlayer * 0.5) actual = persuadeHand
            }
            chosen[a] = actual
        }
        for (a in actors) {
            if (a.isPlayer) continue
            aiDeclare(a, chosen[a] ?: rnd.nextInt(3))
            val text = aiLine(a, "DECLARE", Engine.HANDS[a.declared], "")
            line(a.name + "：「" + text + "」")
        }
        line("")
        line("あなたの最終予告を選んでください。")
        phase = P_FINAL
    }

    private fun playerFinal(h: Int) {
        player.declared = h
        line("")
        line("あなたの最終予告：" + Engine.HANDS[h])
        line("実際に出す手を選んでください。予告と違う手を出すこともできます。")
        phase = P_ACT
    }

    private fun playerAct(h: Int) {
        player.actual = h
        player.lying = player.declared != h
        resolve()
    }

    // ---------------------------------------------------------------- 判定

    private fun resolve() {
        val st = stakes()
        line("")
        line("【結果】")
        for (a in actors) {
            line(a.name + "　予告 " + Engine.HANDS[a.declared] + " → 実際 " + Engine.HANDS[a.actual])
        }

        for (a in actors) {
            var gained = 0
            for (o in actors) {
                if (o === a) continue
                gained += Engine.beats(a.actual, o.actual) * st
            }
            if (a.declared == a.actual) gained += st
            a.score += gained
        }

        line("")
        for (a in actors) {
            if (a.isPlayer) continue
            a.observedCount++
            if (a.declared == a.actual) a.observedMatch++
            if (a.intensity == "high") {
                a.highCount++
                if (a.declared == a.actual) a.highMatch++
            }
            if (a.declared == a.actual) {
                a.trustInPlayer = Engine.clamp(a.trustInPlayer + 0.05, 0.0, 1.0)
            }
        }

        player.observedCount++
        if (player.declared == player.actual) player.observedMatch++

        val at = accuseTarget
        if (at != null) {
            if (at.declared != at.actual) {
                player.score += st
                line("看破成功：" + at.name + "は予告を破っていた（+" + st + "）")
            } else {
                player.score -= st
                line("誤射：" + at.name + "は予告通りだった（-" + st + "）")
                at.trustInPlayer = Engine.clamp(at.trustInPlayer - 0.1, 0.0, 1.0)
            }
        }

        line("")
        for (a in actors) {
            line(a.name + "：" + a.score + "点")
        }
        phase = P_RESULT
    }

    private fun finish() {
        log.setLength(0)
        line("── セッション終了")
        line("")

        var best = actors[0]
        for (a in actors) if (a.score > best.score) best = a
        line("勝者：" + best.name + "（" + best.score + "点）")
        line("")
        line("【観測された傾向】")
        line("断定ではなく、このセッションで観測された範囲の傾向です。")
        line("")

        for (a in actors) {
            if (a.isPlayer) continue
            val n = a.observedCount
            val rate = Math.round(a.matchRate() * 100).toInt()
            val hr = Math.round(a.highMatchRate() * 100).toInt()
            line(a.name)
            line("　予告一致率　" + rate + "%（観測 " + n + "回）")
            if (a.highCount > 0) {
                line("　強く断言した時の一致率　" + hr + "%（" + a.highCount + "回）")
            } else {
                line("　強く断言した場面はありませんでした")
            }
            line("　確信度　" + confidence(n))
            line("")
        }

        val pr = Math.round(player.matchRate() * 100).toInt()
        line("あなた")
        line("　予告一致率　" + pr + "%")
        line("")
        phase = P_END
    }

    private fun confidence(n: Int): String {
        if (n <= 2) return "低（観測数が足りません）"
        if (n <= 4) return "中"
        return "高"
    }

    private fun restart() {
        for (a in actors) {
            a.score = 0
            a.observedCount = 0
            a.observedMatch = 0
            a.highCount = 0
            a.highMatch = 0
            a.trustInPlayer = 0.5
        }
        round = 0
        beginRound()
    }
}
