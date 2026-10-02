package dev.kirin.kiwa.ui

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import dev.kirin.editoradapter.EditorSearch
import dev.kirin.editoradapter.SearchQuery
import dev.kirin.kiwa.search.SearchStatus
import dev.kirin.kiwa.trace.JsonlTrace

/**
 * 検索と置換の帯（E11）。**エンジンの検索を包んだ [EditorSearch] を操作するだけ**で、
 * 探す処理はここに1行も無い（表Aの「自作しない」）。
 *
 * ```
 * ── タブ列 ──────────────────────────────
 * 検索 [__________] Aa W .*  ↑ ↓  3 / 17   置換 ✕   ← ここ
 * 置換 [__________] [1件] [全部]                      ← 「置換」で開く
 * 本文
 * ```
 *
 * ## 被せずに挟む
 *
 * パレットは本文の上へ被せたが、検索は**本文を見ながら操作する**ので被せられない。
 * タブ列と本文の間に挟んで本文を縮める ── 常設バーもタブ列も動かないので、
 * 案Cの「位置は変わらない。変わるのは面積だけ」は保たれる
 * （ソフトキーボードが出たときと同じ挙動）。
 *
 * ## ★引く道は1本（[applyQuery]）
 *
 * 語を打っても、`Aa` を押しても、タブを切り替えても、**引き直しはこの1本を通る**。
 * 検索は状態を持つ（語 / 3つのフラグ / 検索 ↔ 置換 / 次 ↔ 前）ので、
 * **状態ごとに別々の道で引くと、跨いだときだけ壊れる** ── E10b でパレットのモードを
 * 跨いだときに同じ形で踏んだ（3モードは単独では全部通っていた）。
 *
 * ## ★「探している最中」を「当たらない」と書かない
 *
 * 検索は 0 件が正常値になる機能なので、数え終わる前の 0 をそのまま出すと
 * **打つたびに「当たらない」が一瞬出る**し、ログでも見分けが付かない。
 * [EditorSearch.isSearching] が立っている間は件数を出さない（[render]）。
 */
class SearchBarView(
    context: Context,
    private val search: EditorSearch,
    private val trace: JsonlTrace?,
    /** 閉じたときに本文へフォーカスを戻すためのもの。 */
    private val onClosed: () -> Unit
) : LinearLayout(context) {

    /**
     * この帯がキー（Enter / Shift+Enter / Esc）を引き受けるか。
     *
     * **フォーカスと IME の接続先では守れなかった。** エンジンは一致へ飛ぶたび
     * *遅れて* フォーカスを取り、そのあと物理キーが本文へ流れる ── 断っても
     * 取り返しても、遅れの幅が読めない以上いつか漏れる（実機 G で3通り試して3通り漏れた）。
     * だから Android のフォーカスではなく、**最後に人が触ったのはどちらか**で決める。
     * タッチは誰も横取りしない ── そこだけは信用できる。
     */
    private var ownsKeys = false

    /**
     * 帯の中で**最後に人が触った欄**。Enter が「次へ」か「置き換えて次へ」かを分ける。
     *
     * [ownsKeys] と同じ理由でフォーカスを見ない ── 一致へ飛ぶと本文がフォーカスを取るので、
     * `hasFocus()` で分けると**置換の Enter が2回目から「次へ」に化ける**（実機 G）。
     */
    private var activeInput: ImeAwareInput? = null

    private val findRow = LinearLayout(context)
    private val replaceRow = LinearLayout(context)

    private val findLabel = TextView(context)
    private val findInput = ImeAwareInput(context)
    private val replaceLabel = TextView(context)
    private val replaceInput = ImeAwareInput(context)

    private val caseButton = TextView(context)
    private val wordButton = TextView(context)
    private val regexButton = TextView(context)
    private val previousButton = TextView(context)
    private val nextButton = TextView(context)
    private val statusText = TextView(context)
    private val toggleReplaceButton = TextView(context)
    private val closeButton = TextView(context)
    private val replaceOneButton = TextView(context)
    private val replaceAllButton = TextView(context)

    private var palette: Palette = Palette.SUMI

    private var caseSensitive = false
    private var wholeWord = false
    private var regex = false

    /** 最後にエンジンへ投げた条件。**同じものを投げ直さない**ための覚え。 */
    private var applied: SearchQuery? = null

    /**
     * 次に結果が届いたら1回だけやること。
     *
     * 置換のあとに「次へ」飛ぶには、**先に引き直して位置を取り直す**必要がある ──
     * 置換で後ろの一致がまるごとずれるので、古い結果のまま次へ飛ぶと違う所へ着く。
     * 引き直しは非同期なので、届いてからやる。
     */
    private var afterResult: (() -> Unit)? = null

    val isOpen: Boolean get() = visibility == VISIBLE

    init {
        orientation = VERTICAL
        visibility = GONE

        buildFindRow()
        buildReplaceRow()
        addView(findRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(replaceRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        replaceRow.visibility = GONE
    }

    // ------------------------------------------------------------------
    // 組み立て
    // ------------------------------------------------------------------

    private fun buildFindRow() {
        findRow.orientation = HORIZONTAL
        findRow.gravity = Gravity.CENTER_VERTICAL
        findRow.setPadding(PAD, PAD / 2, PAD, PAD / 2)

        findLabel.text = "検索"
        findLabel.textSize = 13f
        findLabel.setPadding(PAD, PAD, PAD, PAD)
        findRow.addView(findLabel)

        findInput.inputType = InputType.TYPE_CLASS_TEXT
        // **日本語も検索する**ので FORCE_ASCII は付けない（E7 で設定欄が塞がったのと逆の判断）。
        findInput.imeOptions = EditorInfo.IME_ACTION_SEARCH
        findInput.textSize = 16f
        findInput.setPadding(PAD, PAD, PAD, PAD)
        findInput.addTextChangedListener(watcher { applyQuery() })
        // ソフトキーボードの「検索」。**実機では物理 Enter がアプリまで届かない**ことがあるので
        // （E10b で確認）、指で使う道はこちらが本命。
        findInput.setOnEditorActionListener { _, actionId, _ ->
            trace?.event("search", "key.action", "actionId", actionId, "focus", focusName())
            goNext()
            true
        }
        // Enter と Esc は [consumeKey] が引き受ける（口は1つ）。ここには置かない。
        // **触った欄を覚える**（フォーカスではなく）。消費はしない。
        findInput.setOnTouchListener { _, _ -> activeInput = findInput; false }
        findRow.addView(
            findInput,
            LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = PAD
                marginEnd = PAD
            }
        )

        // **フラグは3つとも同じ道（applyQuery）で引き直す。**
        chip(caseButton, "Aa", "大文字小文字を区別") { caseSensitive = !caseSensitive; applyQuery() }
        chip(wordButton, "W", "単語として一致") { wholeWord = !wholeWord; applyQuery() }
        chip(regexButton, ".*", "正規表現") { regex = !regex; applyQuery() }
        findRow.addView(caseButton)
        findRow.addView(wordButton)
        findRow.addView(regexButton)

        chip(previousButton, "↑", "前を探す") { goPrevious() }
        chip(nextButton, "↓", "次を探す") { goNext() }
        findRow.addView(previousButton)
        findRow.addView(nextButton)

        statusText.textSize = 12f
        statusText.gravity = Gravity.CENTER_VERTICAL
        statusText.setPadding(PAD * 2, PAD, PAD * 2, PAD)
        findRow.addView(
            statusText,
            LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        chip(toggleReplaceButton, "置換", "置換の欄を出す") { toggleReplace() }
        chip(closeButton, "✕", "検索を閉じる") { close() }
        findRow.addView(toggleReplaceButton)
        findRow.addView(closeButton)
    }

    private fun buildReplaceRow() {
        replaceRow.orientation = HORIZONTAL
        replaceRow.gravity = Gravity.CENTER_VERTICAL
        replaceRow.setPadding(PAD, 0, PAD, PAD / 2)

        replaceLabel.text = "置換"
        replaceLabel.textSize = 13f
        replaceLabel.setPadding(PAD, PAD, PAD, PAD)
        replaceRow.addView(replaceLabel)

        replaceInput.inputType = InputType.TYPE_CLASS_TEXT
        replaceInput.imeOptions = EditorInfo.IME_ACTION_DONE
        replaceInput.textSize = 16f
        replaceInput.setPadding(PAD, PAD, PAD, PAD)
        // **置換する文字は絞り込みに使わない**ので、変換中でも打った通りに使う（[rawText]）。
        replaceInput.setOnEditorActionListener { _, _, _ -> replaceOne(); true }
        replaceInput.setOnTouchListener { _, _ -> activeInput = replaceInput; false }
        replaceRow.addView(
            replaceInput,
            LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = PAD
                marginEnd = PAD
            }
        )

        chip(replaceOneButton, "1件", "今の一致を置き換えて次へ") { replaceOne() }
        chip(replaceAllButton, "全部", "全部置き換える") { replaceAll() }
        replaceRow.addView(replaceOneButton)
        replaceRow.addView(replaceAllButton)
    }

    private fun chip(view: TextView, label: String, description: String, onClick: () -> Unit) {
        view.text = label
        view.textSize = 14f
        view.gravity = Gravity.CENTER
        view.minWidth = CHIP_WIDTH
        // 指で押す画面なので、文字の幅だけを当たり判定にしない（E7/E8 の実機で踏んだ②）。
        view.setPadding(PAD, PAD, PAD, PAD)
        view.isClickable = true
        view.contentDescription = description
        view.setOnClickListener { onClick() }
        (view.layoutParams as? LayoutParams ?: LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )).let { view.layoutParams = it.apply { marginStart = PAD / 2 } }
    }

    private fun watcher(onChanged: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = onChanged()
    }

    // ------------------------------------------------------------------
    // 開け閉め
    // ------------------------------------------------------------------

    /**
     * 開く。[initial] があれば検索語に入れる（本文で選んでいた文字）。
     *
     * **既に開いていれば欄を選び直すだけ** ── 語は残す。探し直したい語は打ち替えればよく、
     * 押すたびに消えると「もう一度打つ」ことになる。
     */
    fun open(initial: String?) {
        visibility = VISIBLE
        ownsKeys = true
        activeInput = findInput
        if (!initial.isNullOrEmpty()) {
            findInput.setText(initial)
        }
        findInput.requestFocus()
        findInput.setSelection(findInput.text?.length ?: 0)
        // requestFocus と同じ回では窓がまだ受け取れていないことがある（パレットと同じ）。
        post {
            val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            manager?.showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT)
        }
        // **開き直しでも必ず引き直す** ── エンジン側は閉じたときに検索を捨てている。
        applyQuery(force = true)
        trace?.event("search", "open", "term", findInput.rawText())
    }

    /**
     * 帯の中を触ったら、キーはこの帯のもの。**本文を触ったら手を引く**（[noteBodyTouched]）。
     * 消費はせず、印を付けて素通しする。
     */
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        ownsKeys = true
        return super.dispatchTouchEvent(event)
    }

    /** 本文が触られた。**以降 Enter は本文の改行**（帯は開いたままでよい）。 */
    fun noteBodyTouched() {
        ownsKeys = false
    }

    /**
     * 帯が引き受けるキーならここで処理して true。**画面の一番外側から呼ぶ**
     * （`MainActivity.dispatchKeyEvent`）ので、フォーカスがどこにあっても届く。
     *
     * **変換中は渡さない** ── Enter は確定に使われる（E10b で決めた線と同じ）。
     * 文字キーは1つも見ない ── 欄への入力は素通しする。
     */
    fun consumeKey(event: KeyEvent): Boolean {
        if (!isOpen || !ownsKeys) return false
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (activeInput?.isComposing() == true) return false
        val handled = when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                when {
                    activeInput === replaceInput -> replaceOne()
                    event.isShiftPressed -> goPrevious()
                    else -> goNext()
                }
                true
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                close()
                true
            }
            else -> false
        }
        if (handled) {
            trace?.event(
                "search", "key.owned",
                "keyName", KeyEvent.keyCodeToString(event.keyCode),
                "shift", event.isShiftPressed,
                "focus", focusName()
            )
        }
        return handled
    }

    fun close() {
        if (!isOpen) return
        ownsKeys = false
        visibility = GONE
        applied = null
        afterResult = null
        // **ハイライトごと捨てる。** 閉じた帯の検索結果が本文に残ると、消す道が無くなる。
        search.stop()
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(windowToken, 0)
        trace?.event("search", "close")
        onClosed()
    }

    /**
     * 同じ条件で引き直す。**本文が別物になったときに呼ぶ**（タブの切り替え・全文の差し替え）。
     *
     * エンジンは本文が替わると検索を捨てる（位置が別の場所を指すため）。
     * 帯が開いているなら、新しい本文で引き直すのがここ。
     */
    fun reapply() {
        if (isOpen) applyQuery(force = true)
    }

    /**
     * 本文が編集された。**開いていて条件があるときだけ引き直す。**
     *
     * 一致の位置は文字数で持っているので、**1文字打つだけで後ろが全部ずれる**。
     * ずれたまま「次へ」を押すと違う所へ着く ── undo でも置換でも同じ。
     *
     * **打つたびに投げてよい** ── エンジンは前の検索スレッドを潰してから始めるので
     * （`EditorSearcher.executeMatch`）、実際に数え切るのは指が止まった後の1回だけ。
     */
    fun onTextEdited() {
        if (isOpen && search.hasQuery()) applyQuery(force = true)
    }

    /** 結果が届いた。**件数を聞き直すのはここだけ。** */
    fun onSearchResult() {
        val pending = afterResult
        afterResult = null
        pending?.invoke()
        render()
    }

    // ------------------------------------------------------------------
    // ★引く道は1本
    // ------------------------------------------------------------------

    private fun currentQuery(): SearchQuery =
        // **変換中の文字は流さない**（[ImeAwareInput.settledText]）── 打つたびに件数が跳ねる。
        SearchQuery(findInput.settledText(), caseSensitive, wholeWord, regex)

    /**
     * 引き直す。**語もフラグも本文の入れ替えも、全部ここを通る。**
     *
     * @param force 条件が同じでも投げ直す。本文が別物になったときに使う
     */
    private fun applyQuery(force: Boolean = false) {
        val query = currentQuery()
        // **同じ条件は投げ直さない** ── 投げ直すと数え直しになり、その間だけ 0 件に見える。
        val rerun = force || query != applied
        if (rerun) {
            applied = query
            search.start(query)
        }
        render()
        // **投げなかったときも1行出す。** 出さないと「変換中は流していない」が
        // ログでは「何も起きていない」と区別できない ── 打鍵に対して行が出ることが証拠になる
        // （E10b のパレットが `used` の据え置きで示したのと同じ形）。
        trace?.event(
            "search", "query",
            "raw", findInput.rawText(),
            "used", query.pattern(),
            "composing", findInput.isComposing(),
            "rerun", rerun,
            "case", caseSensitive,
            "word", wholeWord,
            "regex", regex,
            "searching", search.isSearching(),
            "count", search.matchCount()
        )
    }

    /** 帯の中でフォーカスを持っている場所。`outside` ＝ 帯の外（本文など）。 */
    private fun focusName(): String = when (findFocus()) {
        null -> "outside"
        findInput -> "find"
        replaceInput -> "replace"
        else -> "bar"
    }

    private fun goNext() {
        if (search.isSearching()) return
        search.next()
        render()
        trace?.event(
            "search", "next",
            "index", search.currentIndex(), "of", search.matchCount(), "focus", focusName()
        )
    }

    private fun goPrevious() {
        if (search.isSearching()) return
        search.previous()
        render()
        trace?.event(
            "search", "previous",
            "index", search.currentIndex(), "of", search.matchCount(), "focus", focusName()
        )
    }

    private fun toggleReplace() {
        val opening = replaceRow.visibility != VISIBLE
        replaceRow.visibility = if (opening) VISIBLE else GONE
        // **語は残す**（検索語も置換語も）── 開き直すたびに打ち直しになると帯の意味が無い。
        activeInput = if (opening) replaceInput else findInput
        if (opening) replaceInput.requestFocus() else findInput.requestFocus()
        render()
        trace?.event("search", "replaceRow", "open", opening)
    }

    /** 置き換えの入口（ボタン・Enter・IME の確定）が共通で見る。**壊れた式の間は押しても何もしない。** */
    private fun canReplace(): Boolean =
        SearchStatus.canReplace(search.error(), search.isSearching(), search.hasQuery())

    /**
     * 今の一致を置き換える。
     *
     * **乗っていないときは次へ飛ぶだけ**（エンジンの作法）。乗っていたときだけ
     * 引き直してから次へ進む ── 置換で後ろの一致がずれるので、
     * 古い結果のまま次へ飛ぶと違う所へ着く。
     */
    private fun replaceOne() {
        if (!canReplace()) return
        val wasOnMatch = search.currentIndex() > 0
        search.replaceCurrent(replaceInput.rawText())
        trace?.event("search", "replaceOne", "onMatch", wasOnMatch)
        if (wasOnMatch) {
            afterResult = { search.next() }
            applyQuery(force = true)
        } else {
            render()
        }
    }

    /**
     * 全部置き換える。
     *
     * **終わったら必ず引き直す** ── 置換後の文字列がまた一致することがあるし
     * （`a` を `aa` にする）、位置は全部ずれている。
     */
    private fun replaceAll() {
        if (!canReplace()) return
        trace?.event("search", "replaceAll", "of", search.matchCount())
        search.replaceAll(replaceInput.rawText()) { applyQuery(force = true) }
    }

    // ------------------------------------------------------------------
    // 描く
    // ------------------------------------------------------------------

    /**
     * 状態を1箇所で文字にする。**4つを混ぜない。**
     *
     * - 受け付けなかった（壊れた正規表現）→ 理由
     * - 探している最中 → そう出す。**ここで 0 件と書かない**
     * - 条件が無い → 何も出さない
     * - 数え終わった → 件数（乗っていれば「3 / 17」、乗っていなければ「17 件」）
     */
    private fun render() {
        // **組み立ては [SearchStatus] が持つ**（画面を知らないので単体で試験できる）。
        statusText.text = SearchStatus.label(
            error = search.error(),
            searching = search.isSearching(),
            hasQuery = search.hasQuery(),
            count = search.matchCount(),
            index = search.currentIndex()
        )
        renderChips()
    }

    private fun renderChips() {
        paint(caseButton, caseSensitive)
        // **正規表現が入っていると単語単位は効かない**（条件の型が3択で、正規表現が勝つ）。
        // 押せるままにしておくと「押したのに変わらない」になるので灰色で出す。
        paint(wordButton, wholeWord && !regex, dimmed = regex)
        paint(regexButton, regex)
        paint(toggleReplaceButton, replaceRow.visibility == VISIBLE)
        paint(previousButton, false)
        paint(nextButton, false)
        paint(closeButton, false)
        paint(replaceOneButton, false)
        paint(replaceAllButton, false)
    }

    private fun paint(view: TextView, on: Boolean, dimmed: Boolean = false) {
        view.setBackgroundColor(if (on) palette.button else palette.toolbar)
        view.setTextColor(if (dimmed) palette.dim else palette.text)
        view.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
    }

    fun apply(palette: Palette) {
        this.palette = palette
        setBackgroundColor(palette.toolbar)
        findLabel.setTextColor(palette.dim)
        replaceLabel.setTextColor(palette.dim)
        statusText.setTextColor(palette.dim)
        for (input in listOf(findInput, replaceInput)) {
            input.setBackgroundColor(palette.button)
            input.setTextColor(palette.text)
            input.setHintTextColor(palette.dim)
        }
        renderChips()
        if (isOpen) render()
    }

    private companion object {
        const val PAD = 12

        /** 記号1つのボタンでも指で押せる幅。 */
        const val CHIP_WIDTH = 96
    }
}
