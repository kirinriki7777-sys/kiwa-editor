package dev.kirin.kiwa.ui

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.kirin.editoradapter.SearchQuery
import dev.kirin.kiwa.search.ProjectSearch
import dev.kirin.kiwa.search.SearchStatus
import dev.kirin.kiwa.trace.JsonlTrace
import java.io.File

/**
 * フォルダ全体の検索。**コマンドパレットと同じ位置・同じ形の別画面。**
 *
 * ## なぜパレットと同じ入力基盤を使うか
 *
 * パレットの入力欄は [ImeAwareInput] を通して IME 入力を処理する。
 * 検索欄も同じ入力基盤を使い、未確定文字列の扱いを揃える。
 *
 * 引き出しでは画面の 30% ほどの幅しかなく行の中身が読みにくい。
 * 本文の下に置くと、ソフトキーボードが出たときに縦の表示領域が足りない。
 *
 * ## パレットの候補に混ぜない理由
 *
 * パレットの候補は**同期で決まる**。フォルダ検索は**非同期に増える**ので、混ぜると
 * 絞り込みの実装に「探している最中」が入り込む ── 0 件が正常値の機能に
 * 「まだ数えていない 0」が混ざると、画面からもログからも見分けが付かない。
 */
class ProjectSearchView(
    context: Context,
    private val trace: JsonlTrace?,
    private val rootOf: () -> File,
    private val onChoose: (File, Int) -> Unit,
    private val onClosed: () -> Unit,
) : FrameLayout(context) {

    /** 高さを画面の一定割合で止めるスクロール。パレットと同じ理由（下まで伸ばさない）。 */
    private class LimitedScroll(context: Context) : ScrollView(context) {
        var maxHeight: Int = Int.MAX_VALUE

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        }
    }

    private val scrim = View(context)
    private val panel = LinearLayout(context)
    private val input = ImeAwareInput(context)
    private val flagBar = LinearLayout(context)
    private val status = TextView(context)
    private val scroll = LimitedScroll(context)
    private val list = LinearLayout(context)

    private var palette: Palette = Palette.SUMI

    private var caseSensitive = false
    private var wholeWord = false
    private var regex = false

    private val flagButtons = LinkedHashMap<String, TextView>()

    /**
     * 走らせた回の番号。**別のスレッドから読むので `@Volatile`。**
     *
     * 走査は投げっぱなしにできない ── 語を打ち替えるたびに前の回が生き残ると、
     * 遅れて返ってきた古い結果が新しい結果を上書きする。番号が変わっていたら
     * 走査自身がその場でやめ（`cancelled`）、届いても捨てる。
     */
    @Volatile
    private var generation = 0

    private var searching = false
    private var outcome: ProjectSearch.Outcome? = null
    private var rows: List<Row> = emptyList()
    private var selected = 0

    /** 一覧の1行。見出し（ファイル）と一致で同じ型を使い、[hit] の有無で分ける。 */
    private class Row(val label: CharSequence, val hit: ProjectSearch.Hit?, val detail: String = "")

    val isOpen: Boolean get() = visibility == VISIBLE

    init {
        visibility = GONE

        scrim.setOnClickListener { close() }
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(PAD, PAD, PAD, PAD)
        panel.isClickable = true

        input.inputType = InputType.TYPE_CLASS_TEXT
        // **日本語を通すので FORCE_ASCII は付けない**（日本語の語も日本語のファイル名も引く）。
        input.imeOptions = EditorInfo.IME_ACTION_SEARCH
        input.textSize = 18f
        input.setPadding(PAD, PAD, PAD, PAD)
        input.hint = "フォルダ全体を検索"
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = rerun()
        })
        input.setOnEditorActionListener { _, _, _ -> chooseSelected(); true }
        // 移動と決定と取り消しは [consumeKey] が引き受ける（口は1つ）。ここには置かない。
        panel.addView(
            input,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        flagBar.orientation = LinearLayout.HORIZONTAL
        flagBar.gravity = Gravity.CENTER_VERTICAL
        flagBar.setPadding(PAD, 0, PAD, PAD)
        addFlag("Aa", "大小を区別") { caseSensitive = !caseSensitive }
        addFlag("語", "単語単位") { wholeWord = !wholeWord }
        addFlag(".*", "正規表現") { regex = !regex }

        status.textSize = 12f
        status.gravity = Gravity.END
        status.maxLines = 1
        flagBar.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(
            flagBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        list.orientation = LinearLayout.VERTICAL
        scroll.isFillViewport = true
        scroll.addView(
            list,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        panel.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        addView(
            panel,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        )
    }

    private fun addFlag(label: String, hint: String, toggle: () -> Unit) {
        val button = TextView(context).apply {
            text = label
            textSize = 13f
            setPadding(PAD * 2, PAD, PAD * 2, PAD)
            isClickable = true
            contentDescription = hint
            setOnClickListener {
                toggle()
                renderFlags()
                // **条件が変われば引き直す。** 語を打ち直したときと同じ道を通す
                // （引く道が2本あると、片方だけ直したときに食い違う ── E11）。
                rerun()
            }
        }
        flagButtons[label] = button
        flagBar.addView(button)
    }

    // ------------------------------------------------------------------
    // 開け閉め
    // ------------------------------------------------------------------

    /** 開く。[seed] があれば入れた状態から始める（本文で選んでいた語）。 */
    fun open(seed: String?) {
        val metrics = resources.displayMetrics
        panel.layoutParams = (panel.layoutParams as LayoutParams).apply {
            width = minOf((metrics.widthPixels * WIDTH_RATIO).toInt(), MAX_WIDTH)
            topMargin = TOP_MARGIN
        }
        scroll.maxHeight = (metrics.heightPixels * HEIGHT_RATIO).toInt()

        visibility = VISIBLE
        input.setText(seed ?: "")
        input.setSelection(input.rawText().length)
        input.requestFocus()
        post {
            val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            manager?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        renderFlags()
        rerun()
        trace?.event("project_search", "open", "root", rootOf().path)
    }

    /**
     * 閉じる。**走っている走査もここで止まる** ── 番号を進めると、走査自身が
     * 次のファイルへ移る前にやめる。閉じた後に結果が遅れて出てこない。
     */
    /**
     * この画面が引き受けるキーならここで処理して true。**画面の一番外側から呼ぶ**
     * （`MainActivity.dispatchKeyEvent`）。
     *
     * **欄のフォーカスを当てにしない。** 実機 G で、↑↓ と Enter は欄に届くのに
     * **Esc だけ誰にも届かない**状態になった（`onKeyPreIme` はフォーカスされた View に
     * しか来ない）。開いている間はこの画面が画面全体を覆っているので、
     * 迷わず所有してよい ── 検索の帯が「最後に触った場所」で決めているのと違い、
     * ここは本文と取り合いにならない。
     *
     * **変換中は渡さない** ── Enter は確定に、Esc は取り消しに使われる。
     * 文字キーは1つも見ない（欄への入力は素通し）。
     */
    fun consumeKey(event: KeyEvent): Boolean {
        if (visibility != VISIBLE) return false
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (input.isComposing()) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> { move(1); true }
            KeyEvent.KEYCODE_DPAD_UP -> { move(-1); true }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { chooseSelected(); true }
            KeyEvent.KEYCODE_ESCAPE -> { close(); true }
            else -> false
        }
    }

    fun close() {
        if (!isOpen) return
        generation++
        searching = false
        visibility = GONE
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(windowToken, 0)
        input.setText("")
        outcome = null
        rows = emptyList()
        list.removeAllViews()
        trace?.event("project_search", "close")
        onClosed()
    }

    // ------------------------------------------------------------------
    // 引く
    // ------------------------------------------------------------------

    /**
     * 引き直す。**引く道はこの1本**（語もフラグも開いた直後も全部ここを通る）。
     *
     * 絞り込みに使うのは [ImeAwareInput.settledText] ＝ 確定した分だけ ──
     * 変換中の「ほ」「ほz」で引き直すと、結果が跳ねて押そうとした行が指の下から逃げる。
     */
    private fun rerun() {
        if (!isOpen) return
        val query = SearchQuery(input.settledText(), caseSensitive, wholeWord, regex)

        generation++
        val mine = generation
        outcome = null

        if (query.isEmpty()) {
            searching = false
            render()
            return
        }

        searching = true
        render()
        val root = rootOf()
        trace?.event("project_search", "start", "pattern", query.pattern())
        Thread {
            val result = ProjectSearch.search(root, query, cancelled = { generation != mine })
            post {
                // 走っている間に打ち替えられていたら捨てる（古い結果を出さない）。
                if (generation != mine) return@post
                searching = false
                outcome = result
                render()
                trace?.event(
                    "project_search", "done",
                    "hits", result.hits.size.toString(),
                    "files", result.filesScanned.toString(),
                    "truncated", result.truncated.toString(),
                    "cancelled", result.cancelled.toString()
                )
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // 描く
    // ------------------------------------------------------------------

    private fun render() {
        val result = outcome
        status.text = SearchStatus.projectLabel(
            error = result?.error,
            searching = searching,
            hasQuery = input.settledText().isNotEmpty(),
            hits = result?.hits?.size ?: 0,
            files = result?.filesScanned ?: 0,
            truncated = result?.truncated ?: false,
            cancelled = result?.cancelled ?: false,
        )
        rows = buildRows(result)
        // **選ぶのは飛べる行だけ。** 見出しに乗ったままだと、物理キーボードで
        // Enter を押しても何も起きない（押せていないのか効かないのか区別が付かない）。
        selected = rows.indexOfFirst { it.hit != null }.coerceAtLeast(0)
        renderList()
    }

    /**
     * 一致をファイルごとにまとめる。**見出しは相対パス** ── 同じ名前のファイルが
     * 別の場所にあるとき、名前だけでは区別が付かない。
     */
    private fun buildRows(result: ProjectSearch.Outcome?): List<Row> {
        if (result == null || result.hits.isEmpty()) return emptyList()
        val root = rootOf()
        val out = ArrayList<Row>()
        var lastFile: File? = null
        var shown = 0

        // **1行につき1行だけ出す。** 同じ行に一致が2つあると同じ文が並び、
        // どちらを押しても同じ場所へ飛ぶ ── **押し分ける意味の無い行**が並ぶ。
        // 件数（帯の「6 件」）は一致の数のままで、印は1行の中に複数乗せる。
        val perLine = ArrayList<MutableList<ProjectSearch.Hit>>()
        for (hit in result.hits) {
            val last = perLine.lastOrNull()?.firstOrNull()
            if (last != null && last.file == hit.file && last.line == hit.line) {
                perLine.last().add(hit)
            } else {
                perLine.add(mutableListOf(hit))
            }
        }

        for (group in perLine) {
            if (shown >= MAX_ROWS) {
                out.add(Row("ほか ${perLine.size - shown} 行", null))
                break
            }
            val head = group.first()
            if (head.file != lastFile) {
                lastFile = head.file
                val count = result.hits.count { it.file == head.file }
                // **件数は名前の側に付ける。** 左の欄は行番号の場所なので、
                // そこへ数を置くと「3 行目の alpha.txt」に読める（実機で見て気づいた）。
                out.add(Row("${relative(root, head.file)}（$count 件）", null))
            }
            out.add(Row(excerpt(group), head, head.line.toString()))
            shown++
        }
        return out
    }

    private fun relative(root: File, file: File): String {
        val rootPath = root.path.removeSuffix("/") + "/"
        return file.path.removePrefix(rootPath)
    }

    /**
     * 行の切れ端に、一致した所だけ太字を乗せる。
     *
     * **色を使わない** ── 選んでいる行の地は `palette.button` で塗るので、
     * そこへ色の帯を重ねると配色3択のどれかで沈む。太さなら3択とも同じに出る。
     */
    private fun excerpt(group: List<ProjectSearch.Hit>): CharSequence {
        val first = group.first()
        val head = if (first.textStart > 0) "…" else ""
        val tail = if (first.textStart + first.text.length < first.lineLength) "…" else ""
        val shown = SpannableString(head + first.text + tail)
        for (hit in group) {
            // 切れ端は行の途中から始まることがあるので、印の位置は切った分だけずらす。
            // **切れ端の外へ出た一致には印を付けない**（長い行の右端の一致は文字自体が見えていない）。
            val start = head.length + (hit.column - first.textStart)
            val end = minOf(head.length + first.text.length, start + hit.length)
            if (start in head.length until end) {
                shown.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return shown
    }

    /**
     * **空のときは1行も出さない。** 「当たらない」も「探している」も帯（[status]）が持つ ──
     * 一覧の側にも文言を置くと、探している最中に「当たらない」と読める行が並ぶ。
     */
    private fun renderList() {
        list.removeAllViews()
        for ((index, row) in rows.withIndex()) list.addView(rowView(row, index))
    }

    private fun rowView(row: Row, index: Int): View {
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(PAD * 2, ROW_PAD, PAD * 2, ROW_PAD)
            if (row.hit != null) {
                isClickable = true
                // 行全体を当たり判定にする（E7 の実機で踏んだ②）。
                setOnClickListener { choose(row) }
            }
            if (index == selected && row.hit != null) setBackgroundColor(palette.button)
        }
        val number = TextView(context).apply {
            text = row.detail
            textSize = 12f
            setTextColor(palette.dim)
            gravity = Gravity.END
            minWidth = NUMBER_WIDTH
        }
        val label = TextView(context).apply {
            text = row.label
            textSize = if (row.hit == null) 13f else 15f
            setTextColor(if (row.hit == null) palette.dim else palette.text)
            maxLines = 1
            // 見出しは道が右ほど効くので頭から削る。中身はそのまま尻を削る。
            ellipsize = if (row.hit == null) TextUtils.TruncateAt.START else TextUtils.TruncateAt.END
        }
        view.addView(number, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        view.addView(
            label,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = PAD * 2 }
        )
        return view
    }

    // ------------------------------------------------------------------
    // 選ぶ
    // ------------------------------------------------------------------

    /** 見出しは飛べないので**跨いで**動く。止まると選べない行に引っかかったように見える。 */
    private fun move(delta: Int) {
        var next = selected + delta
        while (next in rows.indices) {
            if (rows[next].hit != null) {
                selected = next
                renderList()
                list.getChildAt(selected)?.let { child -> scroll.post { scroll.smoothScrollTo(0, child.top) } }
                return
            }
            next += delta
        }
    }

    private fun chooseSelected() {
        rows.getOrNull(selected)?.let { if (it.hit != null) choose(it) }
    }

    /**
     * **閉じてから開く。** 一覧は結果を押した時点で用済みで、戻り道は位置履歴が持つ
     * （E13 の2つはここで噛み合う）。閉じない形にすると一覧が常設の面になり、
     * 骨格に4つ目の状態が増える。
     */
    private fun choose(row: Row) {
        val hit = row.hit ?: return
        trace?.event("project_search", "choose", "line", hit.line.toString())
        close()
        onChoose(hit.file, hit.line)
    }

    // ------------------------------------------------------------------
    // 配色
    // ------------------------------------------------------------------

    fun apply(palette: Palette) {
        this.palette = palette
        scrim.setBackgroundColor(SCRIM)
        panel.setBackgroundColor(palette.toolbar)
        input.setBackgroundColor(palette.button)
        input.setTextColor(palette.text)
        input.setHintTextColor(palette.dim)
        status.setTextColor(palette.dim)
        flagBar.setBackgroundColor(palette.toolbar)
        scroll.setBackgroundColor(palette.toolbar)
        renderFlags()
        if (isOpen) renderList()
    }

    /** 入っているフラグを**地の色で**出す。字だけ変えると押したのか分からない。 */
    private fun renderFlags() {
        val on = mapOf("Aa" to caseSensitive, "語" to wholeWord, ".*" to regex)
        for ((label, button) in flagButtons) {
            val enabled = on[label] == true
            button.setBackgroundColor(if (enabled) palette.button else palette.toolbar)
            button.setTextColor(if (enabled) palette.text else palette.dim)
        }
    }

    private companion object {
        const val PAD = 12
        const val ROW_PAD = 14

        /** 幅は画面の 82%（案2）。行の中身が読めることがこの画面の存在理由。 */
        const val WIDTH_RATIO = 0.82f
        const val HEIGHT_RATIO = 0.55f
        const val MAX_WIDTH = 2100
        const val TOP_MARGIN = 60

        /** 行番号の欄の幅（px）。 */
        const val NUMBER_WIDTH = 90

        /** 一度に並べる上限。これ以上は語を足して絞る方が速い。 */
        const val MAX_ROWS = 200

        const val SCRIM = 0x99000000.toInt()
    }
}
