package com.appathy.shinrigame

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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

/**
 * 同じ Wi-Fi 内でのチャット。
 *
 * 履歴は残さない。画面を閉じれば消える。
 * 発言は Hub を通して全員へ流れる。
 */
class ChatActivity : Activity() {

    companion object {
        private const val S_AVATAR = 0
        private const val S_ROOM = 1
        private const val S_CHAT = 2
    }

    private val hub = Hub()
    private lateinit var table: CharacterTable

    private var state = S_AVATAR
    private var myId = ""
    private var myName = ""
    private var myColor = "#6E7684"
    private var myPortrait = "char_player"
    private var status = ""

    private val found = LinkedHashMap<String, String>()

    private var header: TextView? = null
    private var feed: LinearLayout? = null
    private var feedScroll: ScrollView? = null
    private var input: EditText? = null
    private var ipInput: EditText? = null
    private var buttons: LinearLayout? = null
    private var inputRow: LinearLayout? = null

    private val paper = Color.parseColor("#FBF8F3")
    private val inkMain = Color.parseColor("#2C3E58")
    private val inkSub = Color.parseColor("#5B6472")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        table = CharacterTable(Assets.read(this, "characters.json"))

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(paper)
        root.setPadding(22, 20, 22, 20)

        val h = TextView(this)
        h.textSize = 14f
        h.setTextColor(inkMain)
        h.setTypeface(null, Typeface.BOLD)
        h.setPadding(0, 0, 0, 10)
        root.addView(h)
        header = h

        val ip = EditText(this)
        ip.hint = "部屋の IP（例 192.168.1.5）"
        ip.inputType = InputType.TYPE_CLASS_TEXT
        ip.setTextColor(inkMain)
        ip.visibility = View.GONE
        root.addView(
            ip,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        ipInput = ip

        val sv = ScrollView(this)
        val fd = LinearLayout(this)
        fd.orientation = LinearLayout.VERTICAL
        sv.addView(fd)
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        feedScroll = sv
        feed = fd

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.visibility = View.GONE
        val ed = EditText(this)
        ed.hint = "ひとこと"
        ed.inputType = InputType.TYPE_CLASS_TEXT
        ed.setTextColor(inkMain)
        row.addView(ed, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val send = Button(this)
        send.text = "送る"
        send.setAllCaps(false)
        send.setOnClickListener { sendMine() }
        row.addView(
            send,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        input = ed
        inputRow = row

        val bl = LinearLayout(this)
        bl.orientation = LinearLayout.VERTICAL
        bl.setPadding(0, 10, 0, 0)
        root.addView(bl)
        buttons = bl

        setContentView(root)

        hub.onStatus = { m ->
            status = m
            system(m)
            render()
        }
        hub.onFound = { _, ip2 ->
            if (!found.containsKey(ip2)) {
                found[ip2] = ip2
                render()
            }
        }
        hub.onLine = { line -> receive(line) }

        status = "使う顔を選んでください。"
        render()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (myName.isNotEmpty() && hub.connected) {
            hub.send(Hub.pack(Hub.KIND_LEAVE, myName, ""))
        }
        hub.close()
    }

    // ---------------------------------------------------------------- 受信

    private fun receive(line: String) {
        val (kind, from, body) = Hub.unpack(line)
        when (kind) {
            Hub.KIND_JOIN -> system(from + " が入りました")
            Hub.KIND_LEAVE -> system(from + " が出ました")
            Hub.KIND_MSG -> bubble(from, body, colorOfName(from), portraitOfName(from), false)
            else -> system(body)
        }
        render()
    }

    private fun colorOfName(name: String): String {
        for (c in table.characters.values) if (c.name == name) return c.color
        return "#6E7684"
    }

    private fun portraitOfName(name: String): String {
        for (c in table.characters.values) if (c.name == name) return c.portrait
        return "char_player"
    }

    // ---------------------------------------------------------------- 表示

    private fun system(text: String) {
        val f = feed ?: return
        val tv = TextView(this)
        tv.text = "— " + text
        tv.textSize = 12f
        tv.setTextColor(inkSub)
        tv.gravity = Gravity.CENTER
        tv.setPadding(0, 8, 0, 8)
        f.addView(tv)
        scrollDown()
    }

    private fun bubble(
        name: String,
        text: String,
        color: String,
        portrait: String,
        mine: Boolean
    ) {
        val f = feed ?: return

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, 6, 0, 6)
        row.gravity = if (mine) Gravity.END else Gravity.START

        val face = ImageView(this)
        face.setImageResource(portraitRes(portrait))
        face.scaleType = ImageView.ScaleType.FIT_CENTER

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val who = TextView(this)
        who.text = name
        who.textSize = 11f
        who.setTextColor(inkSub)

        val body = TextView(this)
        body.text = text
        body.textSize = 15f
        body.setTextColor(Color.WHITE)
        body.setPadding(18, 12, 18, 12)
        val bg = GradientDrawable()
        bg.setColor(parse(color))
        bg.cornerRadius = 16f
        body.background = bg

        col.addView(who)
        col.addView(body)

        if (mine) {
            row.addView(col)
            row.addView(face, LinearLayout.LayoutParams(72, 72))
        } else {
            row.addView(face, LinearLayout.LayoutParams(72, 72))
            row.addView(col)
        }

        f.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        scrollDown()
    }

    private fun scrollDown() {
        feedScroll?.post { feedScroll?.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun parse(hex: String): Int {
        return try {
            Color.parseColor(hex)
        } catch (e: Exception) {
            inkSub
        }
    }

    private fun portraitRes(name: String): Int {
        val id = resources.getIdentifier(name, "drawable", packageName)
        if (id != 0) return id
        return R.drawable.char_player
    }

    // ---------------------------------------------------------------- 操作

    private fun pickAvatar(c: Character) {
        myId = c.id
        myName = c.name
        myColor = c.color
        myPortrait = c.portrait
        state = S_ROOM
        status = "部屋を開くか、見つけた部屋に入ってください。"
        hub.discover(this)
        render()
    }

    private fun openRoom() {
        state = S_CHAT
        hub.host(this)
        system(myName + " として部屋を開きました")
        system("この端末の IP　" + Hub.localIp())
        render()
    }

    private fun enterRoom(ip: String) {
        state = S_CHAT
        hub.join(ip)
        hub.send(Hub.pack(Hub.KIND_JOIN, myName, ""))
        render()
    }

    private fun sendMine() {
        val ed = input ?: return
        val text = ed.text.toString().trim()
        if (text.isEmpty()) return
        hub.send(Hub.pack(Hub.KIND_MSG, myName, text))
        bubble(myName, text, myColor, myPortrait, true)
        ed.setText("")
        render()
    }

    private fun options(): List<Option> {
        val list = ArrayList<Option>()
        when (state) {
            S_AVATAR -> {
                for (c in table.characters.values) {
                    if (c.id.startsWith("mob")) continue
                    list.add(Option(c.name + " の顔で入る", c.color) { pickAvatar(c) })
                }
                list.add(Option("やめる") { finish() })
            }
            S_ROOM -> {
                list.add(Option("部屋を開く（ホスト）") { openRoom() })
                for (ip in found.keys) {
                    list.add(Option("この部屋に入る　" + ip) { enterRoom(ip) })
                }
                list.add(Option("IP を入れて入る") {
                    val ip = ipInput?.text?.toString()?.trim() ?: ""
                    if (ip.isEmpty()) {
                        status = "部屋の IP を入れてください"
                    } else {
                        enterRoom(ip)
                    }
                })
                list.add(Option("やめる") { finish() })
            }
            S_CHAT -> {
                list.add(Option("退出する") { finish() })
            }
        }
        return list
    }

    private fun render() {
        val sb = StringBuilder()
        sb.append("チャット")
        if (myName.isNotEmpty()) sb.append("　").append(myName)
        if (state == S_CHAT && hub.isHost) sb.append("　接続 ").append(hub.peerCount()).append("台")
        sb.append("\n").append(status)
        header?.text = sb.toString()

        ipInput?.visibility = if (state == S_ROOM) View.VISIBLE else View.GONE
        inputRow?.visibility = if (state == S_CHAT) View.VISIBLE else View.GONE

        val bl = buttons ?: return
        bl.removeAllViews()
        for (opt in options()) {
            val b = Button(this)
            b.text = opt.label
            b.setAllCaps(false)
            b.textSize = 15f
            if (opt.color.isNotEmpty()) {
                val bg = GradientDrawable()
                bg.setColor(parse(opt.color))
                bg.cornerRadius = 12f
                b.background = bg
                b.setTextColor(Color.WHITE)
            }
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
    }
}
