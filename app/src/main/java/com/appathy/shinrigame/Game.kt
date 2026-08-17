package com.appathy.shinrigame

import android.content.Context
import java.util.Random

/** color は "#RRGGBB"。空ならフェーズごとの既定色を使う。 */
class Option(val label: String, val color: String = "", val action: () -> Unit)

class LogLine(val text: String, val emphasis: Boolean)

class Seat(
    val name: String,
    val color: String,
    val portrait: String,
    val cardHand: Int,
    val cardAsset: String?,
    val cardLabel: String,
    val note: String,
    val isPlayer: Boolean,
    val dim: Boolean
)

class Actor(
    val character: Character?,
    val id: String,
    val name: String,
    val isPlayer: Boolean,
    val portrait: String,
    val color: String
) {
    /** 予告。あとで変えてよい。人柄の判定には使わない。 */
    var yokoku = -1

    /** 宣告。-1 は黙っていたということ。 */
    var sengoku = -1

    /** 本番 */
    var actual = -1

    var isOni = false

    var honest = 0
    var liar = 0
    var timid = 0

    fun labelTotal(): Int = honest + liar + timid

    fun dominant(): Int {
        if (labelTotal() == 0) return Labels.NONE
        if (honest >= liar && honest >= timid) return Labels.HONEST
        if (liar >= timid) return Labels.LIAR
        return Labels.TIMID
    }
}

class Game(ctx: Context) {

    companion object {
        const val P_GAME = 0
        const val P_RULE = 1
        const val P_COUNT = 2
        const val P_YOKOKU = 3
        const val P_SENGOKU = 4
        const val P_ACT = 5
        const val P_RESULT = 6
        const val P_END = 7
    }

    val memory = Memory(ctx)
    private val table = CharacterTable(Assets.read(ctx, "characters.json"))
    private val rnd = Random()

    var def: GameDef = Games.all()[0]
        private set
    var rule = Rules.SURVIVAL
        private set

    val actors = ArrayList<Actor>()
    lateinit var player: Actor
    private var match: Match? = null

    var phase = P_GAME
    val log = ArrayList<LogLine>()

    /** 画面側が拾って対戦画面へ移るための合図 */
    var wantVersus = false

    private var oniIndex = 0

    /** 直前の判定で負けた者。結果画面で泣かせる。 */
    private val lostNow = HashSet<String>()

    /** だまされて負けた者。次のラウンドのあいだ怒った顔になる。 */
    private val angryNow = HashSet<String>()
    private val angryNext = HashSet<String>()

    init {
        toGameSelect()
    }

    // ---------------------------------------------------------------- 準備

    private fun line(s: String, emphasis: Boolean = false) {
        log.add(LogLine(s, emphasis))
    }

    private fun toGameSelect() {
        phase = P_GAME
        log.clear()
        line("遊ぶゲームを選んでください。")
        line("")
        line("流れはどちらも同じです。")
        line("　予告　→　宣告　→　本番")
        line("")
        line("予告は言い換えてもかまいません。")
        line("宣告どおりに出せば正直者、違えば嘘つき、")
        line("黙っていれば小心者として数えます。")
    }

    private fun toRuleSelect(g: GameDef) {
        def = g
        phase = P_RULE
        log.clear()
        line(def.displayName)
        line("")
        line("勝ち方を選んでください。")
        line("")
        for (r in intArrayOf(Rules.SURVIVAL, Rules.TOURNAMENT, Rules.OPEN)) {
            if (!def.supports(r)) continue
            line("【" + Rules.name(r) + "】")
            line("　" + Rules.summary(r))
        }
    }

    private fun toCountSelect(r: Int) {
        rule = r
        phase = P_COUNT
        log.clear()
        line(def.displayName + "　" + Rules.name(rule))
        line("")
        line(Rules.summary(rule))
        line("")
        line("参加人数を選んでください。あなたを含めた数です。")
    }

    private fun start(total: Int) {
        actors.clear()
        player = Actor(null, "player", "あなた", true, "char_player", "#6E7684")
        actors.add(player)
        for (id in table.roster(total - 1, memory.sessions())) {
            val c = table.characters[id]
            if (c != null) actors.add(Actor(c, c.id, c.name, false, c.portrait, c.color))
        }
        match = Match(rule, actors)
        oniIndex = 0
        log.clear()
        line("── " + def.displayName + "　" + Rules.name(rule))
        line(Rules.summary(rule))
        beginRound()
    }

    private fun beginRound() {
        val m = match ?: return
        for (a in actors) {
            a.yokoku = -1
            a.sengoku = -1
            a.actual = -1
            a.isOni = false
        }

        val ps = m.participants()
        if (def.id == "daruma" && ps.isNotEmpty()) {
            ps[oniIndex % ps.size].isOni = true
            oniIndex++
        }

        angryNow.clear()
        angryNow.addAll(angryNext)
        angryNext.clear()
        lostNow.clear()

        line("")
        line("── 第" + (m.round + 1) + "戦")
        if (rule == Rules.TOURNAMENT) line(m.bracketLine())
        for (a in ps) {
            if (a.isOni) line(a.name + " が鬼です")
        }
        line(def.prompt("yokoku"))
        phase = P_YOKOKU
    }

    private fun participants(): List<Actor> {
        val m = match ?: return emptyList()
        return m.participants()
    }

    private fun playerIn(): Boolean {
        for (a in participants()) if (a.isPlayer) return true
        return false
    }

    // ---------------------------------------------------------------- 進行

    private fun doYokoku(h: Int) {
        val ps = participants()
        for (a in ps) {
            if (a.isPlayer) continue
            a.yokoku = def.aiPick(a, others(ps, a), "yokoku", rnd)
        }
        if (playerIn()) player.yokoku = h

        line("")
        line("【予告】")
        for (a in ps) line("　" + a.name + "　" + claimText(a.yokoku))
        line("")
        line(def.prompt("sengoku"))
        phase = P_SENGOKU
    }

    private fun doSengoku(h: Int) {
        val ps = participants()
        for (a in ps) {
            if (a.isPlayer) continue
            a.sengoku = def.aiPick(a, others(ps, a), "sengoku", rnd)
        }
        if (playerIn()) player.sengoku = h

        line("")
        line("【宣告】")
        for (a in ps) {
            val t = if (a.sengoku < 0) "黙っている" else claimText(a.sengoku)
            line("　" + a.name + "　" + t, a.sengoku >= 0)
        }
        line("")
        line(def.prompt("act"))
        phase = P_ACT
    }

    private fun doAct(h: Int) {
        val ps = participants()
        for (a in ps) {
            if (a.isPlayer) continue
            a.actual = def.aiPick(a, others(ps, a), "act", rnd)
        }
        if (playerIn()) player.actual = h
        resolve()
    }

    private fun others(ps: List<Actor>, self: Actor): List<Actor> {
        val l = ArrayList<Actor>()
        for (a in ps) if (a !== self) l.add(a)
        return l
    }

    /**
     * 表情。画像名は portrait + 接尾辞。
     * 負けた直後は泣き、だまされて負けた次の回は怒り、劣勢だと不安になる。
     */
    private fun faceOf(a: Actor): String {
        if (a.isPlayer) return a.portrait
        if (phase == P_RESULT && lostNow.contains(a.id)) return a.portrait + "_cry"
        if (angryNow.contains(a.id)) return a.portrait + "_angry"
        if ((phase == P_YOKOKU || phase == P_SENGOKU || phase == P_ACT) && isBehind(a)) {
            return a.portrait + "_anxious"
        }
        return a.portrait
    }

    /** 追い込まれているか。ルールによって意味が変わる。 */
    private fun isBehind(a: Actor): Boolean {
        val m = match ?: return false
        return when (rule) {
            Rules.SURVIVAL -> (m.strikes[a.id] ?: 0) >= Rules.STRIKES_OUT - 1
            Rules.TOURNAMENT -> false
            else -> {
                if (m.round == 0) return false
                var best = 0
                for (o in actors) {
                    val w = m.wins[o.id] ?: 0
                    if (w > best) best = w
                }
                best - (m.wins[a.id] ?: 0) >= 2
            }
        }
    }

    private fun claimText(v: Int): String {
        if (v < 0) return "－"
        return def.claims[v]
    }

    private fun resolve() {
        val m = match ?: return
        val ps = participants()

        line("")
        line("【本番】")
        for (a in ps) line("　" + a.name + "　" + claimText(a.actual))
        line("")

        val result = def.resolve(ps)

        // 負けた者は泣く。嘘つきがいた回に負けた者は、次の回で怒る。
        lostNow.clear()
        for (a in ps) if ((result.status[a.id] ?: 0) < 0) lostNow.add(a.id)

        var deceived = false
        for (a in ps) if (def.label(a, ps) == Labels.LIAR) deceived = true
        angryNext.clear()
        if (deceived) {
            for (a in ps) {
                if (lostNow.contains(a.id) && def.label(a, ps) != Labels.LIAR) {
                    angryNext.add(a.id)
                }
            }
        }

        val ok = m.apply(result)

        // 人柄を数える
        for (a in ps) {
            val lb = def.label(a, ps)
            when (lb) {
                Labels.HONEST -> a.honest++
                Labels.LIAR -> a.liar++
                Labels.TIMID -> a.timid++
            }
            if (lb != Labels.NONE) {
                memory.recordLabel(a.id, lb)
            }
        }

        for (n in m.log) line(n)
        m.log.clear()

        line("")
        line("【この回の人柄】")
        for (a in ps) {
            line("　" + a.name + "　" + Labels.name(def.label(a, ps)))
        }

        if (!ok) {
            line("")
            line("引き分けなので、もう一度やり直します。")
        }

        phase = P_RESULT
    }

    // ---------------------------------------------------------------- 表示

    fun header(): String {
        if (phase <= P_COUNT) return "心理戦ゲーム"
        val m = match ?: return def.displayName
        val sb = StringBuilder()
        sb.append(def.displayName).append("　").append(Rules.name(rule))
        sb.append("　第").append(m.round + 1).append("戦\n")
        for (a in actors) {
            sb.append(a.name).append(" ").append(m.statusLine(a)).append("　")
        }
        return sb.toString()
    }

    fun stageLabel(): String {
        return when (phase) {
            P_GAME -> "ゲーム選択"
            P_RULE -> "勝ち方"
            P_COUNT -> "人数"
            P_YOKOKU -> "予告"
            P_SENGOKU -> "宣告"
            P_ACT -> "本番"
            P_RESULT -> "結果"
            else -> "終了"
        }
    }

    /** 予告と宣告は言葉の場面。赤枠で本番と区別する。 */
    fun isTalkPhase(): Boolean = phase == P_YOKOKU || phase == P_SENGOKU

    fun table(): List<Seat> {
        val out = ArrayList<Seat>()
        val m = match ?: return out
        if (phase <= P_COUNT) return out

        val ps = participants()
        for (a in actors) {
            val inRound = ps.contains(a)
            val hand = when (phase) {
                P_RESULT -> a.actual
                P_ACT -> a.sengoku
                P_SENGOKU -> a.yokoku
                else -> -1
            }
            var note = m.statusLine(a)
            if (phase == P_RESULT && inRound) {
                note = Labels.name(def.label(a, ps))
            }
            if (a.isOni) note = "鬼　" + note
            out.add(
                Seat(
                    a.name,
                    a.color,
                    faceOf(a),
                    hand,
                    if (hand >= 0) def.cardAsset(hand) else null,
                    if (hand >= 0) def.claims[hand] else "",
                    note,
                    a.isPlayer,
                    !inRound
                )
            )
        }
        return out
    }

    fun options(): List<Option> {
        val list = ArrayList<Option>()
        val m = match
        when (phase) {
            P_GAME -> {
                for (g in Games.all()) list.add(Option(g.displayName) { toRuleSelect(g) })
                list.add(Option("2人対戦（同じ Wi-Fi）") { wantVersus = true })
            }
            P_RULE -> {
                for (r in intArrayOf(Rules.SURVIVAL, Rules.TOURNAMENT, Rules.OPEN)) {
                    if (!def.supports(r)) continue
                    list.add(Option(Rules.name(r)) { toCountSelect(r) })
                }
                list.add(Option("ゲームを選び直す") { toGameSelect() })
            }
            P_COUNT -> {
                for (n in Rules.playerCounts(rule)) {
                    list.add(Option(n.toString() + "人で遊ぶ") { start(n) })
                }
                list.add(Option("勝ち方を選び直す") { toRuleSelect(def) })
            }
            P_YOKOKU -> {
                if (playerIn()) {
                    for (h in def.claims.indices) {
                        list.add(Option("予告：" + def.claims[h]) { doYokoku(h) })
                    }
                } else {
                    list.add(Option("見届ける") { doYokoku(-1) })
                }
            }
            P_SENGOKU -> {
                if (playerIn()) {
                    for (h in def.claims.indices) {
                        list.add(Option("宣告：" + def.claims[h]) { doSengoku(h) })
                    }
                    if (def.allowSilence) {
                        list.add(Option("宣告しない（黙る）") { doSengoku(-1) })
                    }
                } else {
                    list.add(Option("見届ける") { doSengoku(-1) })
                }
            }
            P_ACT -> {
                if (playerIn()) {
                    for (h in def.claims.indices) {
                        list.add(Option("本番：" + def.claims[h]) { doAct(h) })
                    }
                } else {
                    list.add(Option("見届ける") { doAct(-1) })
                }
            }
            P_RESULT -> {
                if (m != null && m.finished()) {
                    list.add(Option("結果を見る") { finish() })
                } else {
                    list.add(Option("次の戦いへ") { beginRound() })
                }
            }
            P_END -> {
                list.add(Option("もう一度") { toGameSelect() })
                list.add(Option("人柄の記録を消す") {
                    memory.clear()
                    toGameSelect()
                })
            }
        }
        return list
    }

    // ---------------------------------------------------------------- 終了

    private fun finish() {
        val m = match ?: return
        log.clear()
        line("── " + def.displayName + "　" + Rules.name(rule) + "　終了")
        line("")

        val w = m.winners()
        if (w.isEmpty()) {
            line("勝者なし")
        } else {
            val sb = StringBuilder()
            for (a in w) {
                if (sb.isNotEmpty()) sb.append("、")
                sb.append(a.name)
            }
            line("勝者　" + sb.toString())
        }
        line("")

        line("【このセッションの人柄】")
        line("観測された回数です。決めつけではありません。")
        line("")
        for (a in actors) {
            line(a.name)
            line("　正直者 " + a.honest + "　嘘つき " + a.liar + "　小心者 " + a.timid)
            if (a.labelTotal() > 0) {
                line("　いちばん多かったのは " + Labels.name(a.dominant()))
            }
            line("")
        }

        memory.finishSession()
        line("── 通算（" + memory.sessions() + "セッション）")
        line("")
        for (a in actors) {
            val n = memory.labelTotal(a.id)
            if (n == 0) continue
            line(
                a.name + "　正直 " + memory.labelCount(a.id, Labels.HONEST) +
                    "　嘘 " + memory.labelCount(a.id, Labels.LIAR) +
                    "　小心 " + memory.labelCount(a.id, Labels.TIMID)
            )
        }
        line("")
        phase = P_END
    }
}
