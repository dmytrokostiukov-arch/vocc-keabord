package com.voss.keyboard

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val PREFS = "cfg"
private const val DEFAULT_MODEL = "claude-haiku-5-5"

private val BG = Color.parseColor("#1B2333")
private val KEY = Color.parseColor("#2C3650")
private val SPECIAL = Color.parseColor("#3A4668")
private val ACCENT = Color.parseColor("#4F7BFF")

private val UA = listOf("йцукенгшщзхї", "фівапролджє", "ячсмитьбю")
private val EN = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
private val SYM = listOf("1234567890", "@#₴%&-+()/", "ґ’.,?!:;\"")

private val RULES = """
You improve chat messages typed on a phone. Reply with JSON only, no markdown.
Keep the author's meaning and facts. Never invent facts, prices or promises. No lies or manipulation.
Write each variant in the same language as the draft. Write situation, changes and techniques in Ukrainian.
First classify the situation as one of: Звичайна розмова, Прохання, Торг, Конфлікт.
For Звичайна розмова: fix errors and sharpen wording only, no negotiation techniques, techniques is an empty array.
For Прохання, Торг, Конфлікт use Chris Voss techniques where they fit: mirroring, labeling (Здається, що... / Схоже, що...), accusation audit, calibrated questions starting with Що or Як (never Чому), no-oriented questions, a summary that earns Саме так, fairness framing.
Improve content, arguments and wording, but keep each variant close to the original length (at most 1.5 times longer).
Return exactly three variants with labels: Коротший, М’якший, Наполегливіший.
Format: {"situation":"...","variants":[{"label":"...","text":"...","changes":"one short sentence","techniques":["..."]}]}
""".trimIndent()

data class Variant(val label: String, val text: String, val changes: String, val techniques: String)

class VossIme : InputMethodService() {
    private val ui = Handler(Looper.getMainLooper())
    private var layout = 0
    private var lastAlpha = 0
    private var shift = false
    private var hadSel = false
    private var beforeLen = 0
    private var afterLen = 0
    private lateinit var keysBox: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var chips: LinearLayout
    private lateinit var chipsScroll: HorizontalScrollView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreateInputView(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)
        root.setPadding(dp(4), dp(4), dp(4), dp(8))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val go = Button(this)
        go.text = "✨ Покращити"
        go.isAllCaps = false
        go.setTextColor(Color.WHITE)
        go.setBackgroundColor(ACCENT)
        go.setOnClickListener { improve() }
        top.addView(go, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        statusView = TextView(this)
        statusView.setTextColor(Color.parseColor("#B8C2DD"))
        statusView.textSize = 12f
        statusView.maxLines = 2
        statusView.ellipsize = TextUtils.TruncateAt.END
        statusView.setPadding(dp(8), 0, dp(8), 0)
        top.addView(statusView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(top)

        chipsScroll = HorizontalScrollView(this)
        chips = LinearLayout(this)
        chips.orientation = LinearLayout.HORIZONTAL
        chipsScroll.addView(chips)
        chipsScroll.visibility = View.GONE
        root.addView(chipsScroll)

        keysBox = LinearLayout(this)
        keysBox.orientation = LinearLayout.VERTICAL
        root.addView(keysBox)
        render()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (::keysBox.isInitialized) {
            shift = false
            chipsScroll.visibility = View.GONE
            statusView.text = ""
            render()
        }
    }

    private fun say(t: String) { statusView.text = t }

    private fun keyBtn(label: String, weight: Float, bg: Int, action: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.textSize = 18f
        b.setPadding(0, 0, 0, 0)
        b.minWidth = 0
        b.minimumWidth = 0
        b.minHeight = 0
        b.minimumHeight = 0
        val d = GradientDrawable()
        d.setColor(bg)
        d.cornerRadius = dp(6).toFloat()
        b.background = d
        val lp = LinearLayout.LayoutParams(0, dp(48), weight)
        lp.setMargins(dp(2), dp(3), dp(2), dp(3))
        b.layoutParams = lp
        b.setOnClickListener { action() }
        return b
    }

    private fun del() {
        val ic = currentInputConnection ?: return
        val sel = ic.getSelectedText(0)
        if (sel != null && sel.isNotEmpty()) ic.commitText("", 1) else ic.deleteSurroundingText(1, 0)
    }

    private fun backBtn(w: Float): Button {
        val b = keyBtn("⌫", w, SPECIAL) {}
        val rep = object : Runnable {
            override fun run() {
                del()
                ui.postDelayed(this, 60)
            }
        }
        b.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; del(); ui.postDelayed(rep, 400) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; ui.removeCallbacks(rep) }
            }
            true
        }
        return b
    }

    private fun type(s: String) {
        currentInputConnection?.commitText(s, 1)
        if (shift) { shift = false; render() }
    }

    private fun enter() {
        val ic = currentInputConnection ?: return
        val ei = currentInputEditorInfo
        val action = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noAction = ((ei?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    private fun rowLayout(): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        return r
    }

    private fun render() {
        keysBox.removeAllViews()
        val rows = when (layout) { 0 -> UA; 1 -> EN; else -> SYM }
        for ((i, r) in rows.withIndex()) {
            val row = rowLayout()
            if (i == 2 && layout != 2) {
                row.addView(keyBtn(if (shift) "⬆" else "⇧", 1.5f, if (shift) ACCENT else SPECIAL) { shift = !shift; render() })
            }
            for (ch in r) {
                val s = if (shift && layout != 2) ch.toString().uppercase() else ch.toString()
                row.addView(keyBtn(s, 1f, KEY) { type(s) })
            }
            if (i == 2) row.addView(backBtn(1.5f))
            keysBox.addView(row)
        }
        val b = rowLayout()
        b.addView(keyBtn(if (layout == 2) "абв" else "?123", 1.5f, SPECIAL) {
            layout = if (layout == 2) lastAlpha else 2
            render()
        })
        b.addView(keyBtn("🌐", 1f, SPECIAL) {
            val next = if (lastAlpha == 0) 1 else 0
            lastAlpha = next
            layout = next
            render()
        })
        b.addView(keyBtn(",", 1f, KEY) { type(",") })
        b.addView(keyBtn(if (layout == 1) "English" else "Українська", 4f, KEY) { type(" ") })
        b.addView(keyBtn(".", 1f, KEY) { type(".") })
        b.addView(keyBtn("⏎", 1.5f, SPECIAL) { enter() })
        keysBox.addView(b)
    }

    private fun improve() {
        val ic = currentInputConnection ?: return
        val t = currentInputEditorInfo?.inputType ?: 0
        val cls = t and InputType.TYPE_MASK_CLASS
        val va = t and InputType.TYPE_MASK_VARIATION
        val pw = (cls == InputType.TYPE_CLASS_TEXT && (va == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                va == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || va == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
                (cls == InputType.TYPE_CLASS_NUMBER && va == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        if (pw) { say("У полях паролів вимкнено"); return }
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        val key = p.getString("key", "").orEmpty().trim()
        if (key.isEmpty()) { say("Спершу додайте API-ключ у застосунку Voss Keyboard"); return }
        val model = p.getString("model", "").orEmpty().trim().ifEmpty { DEFAULT_MODEL }
        val sel = ic.getSelectedText(0)?.toString().orEmpty()
        val text: String
        if (sel.isNotEmpty()) {
            hadSel = true; beforeLen = 0; afterLen = 0; text = sel
        } else {
            val b = ic.getTextBeforeCursor(5000, 0)?.toString().orEmpty()
            val a = ic.getTextAfterCursor(5000, 0)?.toString().orEmpty()
            hadSel = false; beforeLen = b.length; afterLen = a.length; text = b + a
        }
        if (text.isBlank()) { say("Спершу напишіть текст"); return }
        say("Покращую…")
        chipsScroll.visibility = View.GONE
        Thread {
            try {
                val vs = callApi(key, model, text)
                ui.post { showVariants(vs) }
            } catch (e: Exception) {
                ui.post { say("Помилка: " + (e.message ?: "невідома")) }
            }
        }.start()
    }

    private fun callApi(key: String, model: String, text: String): List<Variant> {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 1500)
            .put("system", RULES)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Draft:\n" + text)))
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.doOutput = true
        c.setRequestProperty("x-api-key", key)
        c.setRequestProperty("anthropic-version", "2023-06-01")
        c.setRequestProperty("content-type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val ok = code in 200..299
        val stream = (if (ok) c.inputStream else c.errorStream) ?: throw RuntimeException("HTTP " + code)
        val resp = stream.bufferedReader().use { it.readText() }
        if (!ok) throw RuntimeException("HTTP " + code + ": " + resp.take(160))
        val txt = JSONObject(resp).getJSONArray("content").getJSONObject(0).getString("text")
        val json = txt.substring(txt.indexOf('{'), txt.lastIndexOf('}') + 1)
        val arr = JSONObject(json).getJSONArray("variants")
        val out = ArrayList<Variant>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val ta = o.optJSONArray("techniques")
            val techs = if (ta == null) "" else (0 until ta.length()).joinToString(", ") { ta.getString(it) }
            out.add(Variant(o.optString("label"), o.optString("text"), o.optString("changes"), techs))
        }
        return out
    }

    private fun showVariants(vs: List<Variant>) {
        chips.removeAllViews()
        for (v in vs) {
            val b = Button(this)
            b.text = v.label + "\n" + v.text
            b.isAllCaps = false
            b.setTextColor(Color.WHITE)
            b.textSize = 13f
            b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            b.maxLines = 5
            b.ellipsize = TextUtils.TruncateAt.END
            val d = GradientDrawable()
            d.setColor(SPECIAL)
            d.cornerRadius = dp(8).toFloat()
            b.background = d
            b.setOnClickListener { applyVariant(v) }
            val lp = LinearLayout.LayoutParams(dp(230), ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(3), dp(3), dp(3), dp(3))
            chips.addView(b, lp)
        }
        chipsScroll.visibility = if (vs.isEmpty()) View.GONE else View.VISIBLE
        say(if (vs.isEmpty()) "Порожня відповідь, спробуйте ще раз" else "Оберіть варіант")
    }

    private fun applyVariant(v: Variant) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (!hadSel) ic.deleteSurroundingText(beforeLen, afterLen)
        ic.commitText(v.text, 1)
        ic.endBatchEdit()
        chipsScroll.visibility = View.GONE
        say("Що змінено: " + v.changes + (if (v.techniques.isNotEmpty()) " (" + v.techniques + ")" else ""))
    }
}

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)

        fun label(t: String): TextView {
            val v = TextView(this)
            v.text = t
            v.textSize = 16f
            v.setPadding(0, pad / 2, 0, pad / 4)
            return v
        }

        val keyField = EditText(this)
        keyField.hint = "sk-ant-..."
        keyField.setText(p.getString("key", ""))
        keyField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        val modelField = EditText(this)
        modelField.hint = DEFAULT_MODEL
        modelField.setText(p.getString("model", ""))
        modelField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD

        val save = Button(this)
        save.text = "Зберегти"
        save.setOnClickListener {
            p.edit().putString("key", keyField.text.toString().trim())
                .putString("model", modelField.text.toString().trim()).apply()
            save.text = "Збережено"
        }
        val enable = Button(this)
        enable.text = "1. Увімкнути клавіатуру"
        enable.setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        val pick = Button(this)
        pick.text = "2. Вибрати клавіатуру"
        pick.setOnClickListener {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }

        root.addView(label("API-ключ Anthropic"))
        root.addView(keyField)
        root.addView(label("Модель (можна залишити порожнім)"))
        root.addView(modelField)
        root.addView(save)
        root.addView(label("Після збереження:"))
        root.addView(enable)
        root.addView(pick)
        root.addView(label("Напишіть текст, натисніть «✨ Покращити» й оберіть варіант. У полях паролів кнопка вимкнена."))

        val sv = ScrollView(this)
        sv.addView(root)
        setContentView(sv)
    }
}
