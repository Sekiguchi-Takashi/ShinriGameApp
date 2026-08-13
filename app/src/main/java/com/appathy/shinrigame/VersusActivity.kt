package com.appathy.shinrigame

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class VersusActivity : Activity() {

    companion object {
        private const val S_ROLE = 0
        private const val S_WAIT = 1
        private const val S_SETUP = 2
        private const val S_PICK = 3
        private const val S_DECLARE = 4
        private const val S_REVEAL = 5
        private const val S_ADVICE = 6
        private const val S_ACT = 7
        private const val S_WAIT_PEER = 8
        private const val S_RESULT = 9
        private const val S_MATCH_END = 10
    }

    private val net = Net()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var table: CharacterTable
    private lateinit var memory: Memory
    private var vs: Versus? = null

    private var state = S_ROLE
    private var status = ""
    private val log = ArrayList<LogLine>()

    /** 自動で見つかった相手（表示名 → IP） */
    private val found = LinkedHashMap<String, String>()

    private var peerReady = false
    private var myReady = false
    private var myIntensity = "mid"
    private var peerIntensity = "mid"
    private var deadline = 0L
    private var ticking = false

    private var root: LinearLayout? = null
    private var headerView: TextView? = null
    private var boardView: LinearLayout? = null
    private var logView: TextView? = null
    private var scroll: ScrollView? = null
    private var buttons: LinearLayout? = null
    private var ipInput: EditText? = null

    private val paper = Color.parseColor("#FBF8F3")
    private val inkMain = Color.parseColor("#2C3E58")
    private val inkSub = Color.parseColor("#5B6472")
    private val declareRed = Color.parseColor("#C8324B")
    private val bgWood = Color.parseColor("#C2AA8E")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        table = CharacterTable(Assets.read(this, "characters.json"))
        memory = Memory(this)

        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL
        outer.setBackgroundColor(paper)
        outer.setPadding(10, 10, 10, 10)

        val r = LinearLayout(this)
        r.orientation = LinearLayout.VERTICAL
        r.setPadding(22, 20, 22, 20)
        outer.addView(
            r,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root = r

        val h = TextView(this)
        h.textSize = 15f
        h.setTextColor(inkMain)
        h.setTypeface(null, Typeface.BOLD)
        h.setPadding(0, 0, 0, 10)
        r.addView(h)
        headerView = h

        val b = LinearLayout(this)
        b.orientation = LinearLayout.HORIZONTAL
        b.setBackgroundColor(bgWood)
        b.setPadding(8, 12, 8, 12)
        r.addView(
            b,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        boardView = b

        val ip = EditText(this)
        ip.hint = "相手の IP（例 192.168.1.5）"
        ip.inputType = InputType.TYPE_CLASS_TEXT
        ip.setTextColor(inkMain)
        ip.visibility = View.GONE
        r.addView(
            ip,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        ipInput = ip

        val sv = ScrollView(this)
        val lv = TextView(this)
        lv.textSize = 15f
        lv.setTextColor(inkSub)
        lv.setLineSpacing(8f, 1f)
        lv.setPadding(0, 14, 0, 0)
        sv.addView(lv)
        r.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        scroll = sv
        logView = lv

        val bl = LinearLayout(this)
        bl.orientation = LinearLayout.VERTICAL
        bl.setPadding(0, 12, 0, 0)
        r.addView(bl)
        buttons = bl

        setContentView(outer)

        net.onConnected = { onConnected() }
        net.onMessage = { onMessage(it) }
        net.onFound = { name, ip ->
            if (!found.containsKey(ip)) {
                found[ip] = name
                line("見つかりました　" + ip)
                render()
            }
        }
        net.onError = { msg ->
            status = msg
            line(msg)
            render()
        }

        status = "2台を同じ Wi-Fi につないでください。"
        line("片方が「待ち受ける」を押すと、もう片方の一覧に出ます。")
        line("出てこないときだけ、IP を直接入れてください。")
        line("")
        line("この端末の IP　" + Net.localIp())
        net.discover(this)
        render()
    }

    override fun onDestroy() {
        super.onDestroy()
        net.close()
    }

    // ---------------------------------------------------------------- 通信

    private fun onConnected() {
        val v = Versus(vs?.isHost ?: true, table, memory)
        v.mode = vs?.mode ?: Versus.MODE_PROXY
        vs = v
        log.clear()
        if (v.isHost) {
            status = "つながりました。形式を選んでください。"
            state = S_SETUP
        } else {
            status = "つながりました。相手が形式を選んでいます。"
            state = S_WAIT
        }
        render()
    }

    private fun onMessage(raw: String) {
        val v = vs ?: return
        val p = raw.split("|")
        when (p[0]) {
            "MODE" -> {
                v.mode = p[1].toInt()
                v.setFormat(p[2].toInt())
                log.clear()
                if (v.mode == Versus.MODE_PROXY) {
                    status = "使う相手を選んでください。"
                    state = S_PICK
                } else {
                    v.startMatch()
                    status = "対戦開始。"
                    state = S_DECLARE
                }
            }
            "PICK" -> {
                v.peerNpc = p[1]
                if (state == S_WAIT_PEER && myReady) startProxyMatch()
                else peerReady = true
            }
            "DECLARE" -> {
                v.peerDeclared = p[1].toInt()
                peerIntensity = p[2]
                if (v.myDeclared >= 0) reveal()
            }
            "ACT" -> {
                v.peerActual = p[1].toInt()
                if (v.myActual >= 0) finishRound()
            }
            "NEXT" -> {
                peerReady = true
                if (myReady) nextRound()
            }
        }
        render()
    }

    // ---------------------------------------------------------------- 進行

    private fun startProxyMatch() {
        val v = vs ?: return
        v.startMatch()
        log.clear()
        line(v.myName() + " と " + v.peerName() + " の対戦です。")
        line("予告のあと5秒だけ助言できます。従うかどうかは相手しだいです。")
        status = "第1戦"
        state = S_DECLARE
        myReady = false
        peerReady = false
        autoDeclare()
    }

    /** NPC対戦では予告はNPCが決める */
    private fun autoDeclare() {
        val v = vs ?: return
        if (v.mode != Versus.MODE_PROXY) return
        val h = v.npcDeclare()
        myIntensity = v.npcIntensity()
        line("")
        line(v.myName() + " は " + Engine.HANDS_LABEL[h] + " と予告しました")
        net.send("DECLARE|" + h + "|" + myIntensity)
        status = "相手の予告を待っています"
        if (v.peerDeclared >= 0) reveal()
        render()
    }

    private fun declareMine(h: Int) {
        val v = vs ?: return
        v.myDeclared = h
        myIntensity = "mid"
        net.send("DECLARE|" + h + "|mid")
        status = "相手の予告を待っています"
        state = S_WAIT
        if (v.peerDeclared >= 0) reveal()
        render()
    }

    private fun reveal() {
        val v = vs ?: return
        line("")
        line("【予告】")
        line(v.myName() + "　" + Engine.HANDS_LABEL[v.myDeclared])
        line(v.peerName() + "　" + Engine.HANDS_LABEL[v.peerDeclared], peerIntensity == "high")

        if (v.mode == Versus.MODE_PROXY) {
            state = S_ADVICE
            deadline = System.currentTimeMillis() + Versus.ADVICE_MS
            status = "助言できます"
            startTick()
        } else {
            state = S_ACT
            status = "実際に出す手を選んでください"
        }
        render()
    }

    private fun startTick() {
        if (ticking) return
        ticking = true
        ui.postDelayed(object : Runnable {
            override fun run() {
                if (state != S_ADVICE) {
                    ticking = false
                    return
                }
                if (System.currentTimeMillis() >= deadline) {
                    ticking = false
                    applyAdvice(Versus.ADVICE_NONE)
                    return
                }
                render()
                ui.postDelayed(this, 200)
            }
        }, 200)
    }

    private fun remainSec(): Int {
        val ms = deadline - System.currentTimeMillis()
        if (ms <= 0) return 0
        return ((ms + 999) / 1000).toInt()
    }

    private fun applyAdvice(a: Int) {
        val v = vs ?: return
        v.advice = a
        val h = v.npcAct()
        line("")
        when (a) {
            Versus.ADVICE_LIE -> line("あなた：「予告と違う手を出せ」")
            Versus.ADVICE_HONEST -> line("あなた：「予告どおりに出せ」")
            else -> line("（助言なし）")
        }
        if (a != Versus.ADVICE_NONE) {
            line(if (v.adviceTaken) v.myName() + " はうなずいた" else v.myName() + " は聞き流した")
        }
        net.send("ACT|" + h)
        state = S_WAIT
        status = "相手の手を待っています"
        if (v.peerActual >= 0) finishRound()
        render()
    }

    private fun actMine(h: Int) {
        val v = vs ?: return
        v.myActual = h
        net.send("ACT|" + h)
        state = S_WAIT
        status = "相手の手を待っています"
        if (v.peerActual >= 0) finishRound()
        render()
    }

    private fun finishRound() {
        val v = vs ?: return
        val myDecl = v.myDeclared
        val peerDecl = v.peerDeclared
        val r = v.resolve()

        // 自分で予告して自分で出した回だけ、1人用と同じ記録に足す。
        // NPC を操った回は本人の予告ではないので数えない。
        if (v.mode == Versus.MODE_DIRECT) {
            memory.record("player", myDecl == v.myActual, false)
        }

        line("")
        line("【結果】")
        line(
            v.myName() + "　予告 " + Engine.HANDS_LABEL[myDecl] +
                " → 実際 " + Engine.HANDS_LABEL[v.myActual] +
                if (myDecl == v.myActual) "　予告通り" else "　予告と違う"
        )
        line(
            v.peerName() + "　予告 " + Engine.HANDS_LABEL[peerDecl] +
                " → 実際 " + Engine.HANDS_LABEL[v.peerActual] +
                if (peerDecl == v.peerActual) "　予告通り" else "　予告と違う"
        )
        line("")
        line(
            when {
                r > 0 -> v.myName() + " の勝ち"
                r < 0 -> v.peerName() + " の勝ち"
                else -> "あいこ"
            }
        )
        line(v.myWins.toString() + " － " + v.peerWins)

        state = if (v.finished) S_MATCH_END else S_RESULT
        status = if (v.finished) v.resultLine() else "第" + (v.round + 1) + "戦へ"
        myReady = false
        peerReady = false
        render()
    }

    private fun requestNext() {
        val v = vs ?: return
        myReady = true
        net.send("NEXT|")
        if (peerReady) nextRound() else {
            status = "相手を待っています"
            state = S_WAIT
        }
        render()
    }

    private fun nextRound() {
        val v = vs ?: return
        myReady = false
        peerReady = false
        v.beginRound()
        state = S_DECLARE
        status = "第" + (v.round + 1) + "戦"
        line("")
        line("── 第" + (v.round + 1) + "戦")
        if (v.mode == Versus.MODE_PROXY) autoDeclare()
        render()
    }

    // ---------------------------------------------------------------- 描画

    private fun line(s: String, emph: Boolean = false) {
        log.add(LogLine(s, emph))
    }

    private fun cardRes(h: Int): Int {
        return when (h) {
            0 -> R.drawable.card_rock
            1 -> R.drawable.card_scissors
            2 -> R.drawable.card_paper
            else -> R.drawable.card_back
        }
    }

    private fun portraitRes(name: String): Int {
        return when (name) {
            "char_ren" -> R.drawable.char_ren
            "char_momo" -> R.drawable.char_momo
            "char_sou" -> R.drawable.char_sou
            "char_mio" -> R.drawable.char_mio
            else -> R.drawable.char_player
        }
    }

    private fun applyFrame(hot: Boolean) {
        val r = root ?: return
        val bg = GradientDrawable()
        bg.setColor(paper)
        bg.setStroke(3, if (hot) declareRed else Color.parseColor("#E4DDD0"))
        r.background = bg
    }

    private fun seatColumn(name: String, portrait: String, hand: Int, reveal: Boolean) {
        val b = boardView ?: return
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(6, 0, 6, 0)

        val face = ImageView(this)
        face.setImageResource(portraitRes(portrait))
        face.scaleType = ImageView.ScaleType.FIT_CENTER
        col.addView(face, LinearLayout.LayoutParams(110, 110))

        val tv = TextView(this)
        tv.text = name
        tv.textSize = 12f
        tv.gravity = Gravity.CENTER
        tv.setTextColor(Color.WHITE)
        tv.setTypeface(null, Typeface.BOLD)
        col.addView(
            tv,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val iv = ImageView(this)
        iv.setImageResource(if (reveal) cardRes(hand) else R.drawable.card_back)
        iv.adjustViewBounds = true
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        col.addView(
            iv,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        b.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun renderBoard() {
        val b = boardView ?: return
        b.removeAllViews()
        val v = vs
        if (v == null || state <= S_PICK) {
            b.visibility = View.GONE
            return
        }
        b.visibility = View.VISIBLE

        val showActual = (state == S_RESULT || state == S_MATCH_END)
        val myPortrait = if (v.mode == Versus.MODE_PROXY)
            (v.myCharacter()?.portrait ?: "char_player") else "char_player"
        val peerPortrait = if (v.mode == Versus.MODE_PROXY)
            (v.peerCharacter()?.portrait ?: "char_player") else "char_player"

        seatColumn(
            v.myName(), myPortrait,
            if (showActual) v.myActual else v.myDeclared,
            if (showActual) v.myActual >= 0 else v.myDeclared >= 0
        )
        seatColumn(
            v.peerName(), peerPortrait,
            if (showActual) v.peerActual else v.peerDeclared,
            if (showActual) v.peerActual >= 0 else v.peerDeclared >= 0
        )
    }

    private fun options(): List<Option> {
        val list = ArrayList<Option>()
        val v = vs
        when (state) {
            S_ROLE -> {
                list.add(Option("待ち受ける（ホスト）") {
                    vs = Versus(true, table, memory)
                    status = "相手の接続を待っています"
                    state = S_WAIT
                    net.advertise(this)
                    net.host()
                })
                for (e in found.entries) {
                    list.add(Option("この端末につなぐ　" + e.key) { connectTo(e.key) })
                }
                list.add(Option("IP を入れてつなぐ") {
                    val ip = ipInput?.text?.toString()?.trim() ?: ""
                    if (ip.isEmpty()) {
                        status = "相手の IP を入力してください"
                    } else {
                        connectTo(ip)
                    }
                })
                list.add(Option("やめる") { finish() })
            }
            S_SETUP -> {
                list.add(Option("2人で対戦　5戦3勝先取") { sendMode(Versus.MODE_DIRECT, 5) })
                list.add(Option("2人で対戦　9戦5勝先取") { sendMode(Versus.MODE_DIRECT, 9) })
                list.add(Option("NPCを操る　5戦3勝先取") { sendMode(Versus.MODE_PROXY, 5) })
                list.add(Option("NPCを操る　9戦5勝先取") { sendMode(Versus.MODE_PROXY, 9) })
            }
            S_PICK -> {
                for (id in table.starter) {
                    val c = table.characters[id] ?: continue
                    list.add(Option(c.name + " を選ぶ") { pick(id) })
                }
            }
            S_DECLARE -> {
                if (v != null && v.mode == Versus.MODE_DIRECT) {
                    for (h in 0 until 3) {
                        list.add(Option("予告：" + Engine.HANDS_LABEL[h]) { declareMine(h) })
                    }
                }
            }
            S_ADVICE -> {
                list.add(Option("正直に出せと伝える") { applyAdvice(Versus.ADVICE_HONEST) })
                list.add(Option("予告と違う手を出せと伝える") { applyAdvice(Versus.ADVICE_LIE) })
                list.add(Option("何も言わない") { applyAdvice(Versus.ADVICE_NONE) })
            }
            S_ACT -> {
                for (h in 0 until 3) {
                    list.add(Option("実際に出す：" + Engine.HANDS_LABEL[h]) { actMine(h) })
                }
            }
            S_RESULT -> {
                list.add(Option("次の戦いへ") { requestNext() })
            }
            S_MATCH_END -> {
                list.add(Option("終了する") { finish() })
            }
        }
        return list
    }

    private fun connectTo(ip: String) {
        vs = Versus(false, table, memory)
        status = "接続しています"
        state = S_WAIT
        net.join(ip)
        render()
    }

    private fun sendMode(mode: Int, rounds: Int) {
        val v = vs ?: return
        v.mode = mode
        v.setFormat(rounds)
        net.send("MODE|" + mode + "|" + rounds)
        log.clear()
        if (mode == Versus.MODE_PROXY) {
            status = "使う相手を選んでください。"
            state = S_PICK
        } else {
            v.startMatch()
            status = "対戦開始。"
            state = S_DECLARE
        }
        render()
    }

    private fun pick(id: String) {
        val v = vs ?: return
        v.myNpc = id
        myReady = true
        net.send("PICK|" + id)
        if (v.peerNpc.isNotEmpty()) {
            startProxyMatch()
        } else {
            status = "相手が選んでいます"
            state = S_WAIT_PEER
        }
        render()
    }

    private fun render() {
        val v = vs
        applyFrame(state == S_DECLARE || state == S_ADVICE)

        val head = StringBuilder()
        head.append("2人対戦　").append(status)
        if (v != null && state >= S_DECLARE && state <= S_MATCH_END) {
            head.append("\n").append(v.scoreLine())
        }
        if (state == S_ADVICE) {
            head.append("\n助言できる残り時間　").append(remainSec()).append(" 秒")
        }
        headerView?.text = head.toString()

        ipInput?.visibility = if (state == S_ROLE) View.VISIBLE else View.GONE

        renderBoard()

        val sb = android.text.SpannableStringBuilder()
        for (l in log) {
            val start = sb.length
            sb.append(l.text).append("\n")
            if (l.emphasis) {
                val end = sb.length
                sb.setSpan(
                    android.text.style.RelativeSizeSpan(1.18f), start, end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                sb.setSpan(
                    android.text.style.StyleSpan(Typeface.BOLD), start, end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        logView?.setText(sb)

        val bl = buttons ?: return
        bl.removeAllViews()
        for (opt in options()) {
            val b = Button(this)
            b.text = opt.label
            b.setAllCaps(false)
            b.textSize = 15f
            b.setOnClickListener {
                opt.action()
                render()
            }
            bl.addView(
                b,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        scroll?.post { scroll?.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
