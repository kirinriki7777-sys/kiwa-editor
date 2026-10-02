package dev.kirin.kiwa.trace

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import dev.kirin.kiwa.engine.sora.ImeTrace
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * [ImeTrace] を JSONL のファイルへ書き出す。
 *
 * **IME の同期呼び出しの中で JSON 化もファイル書き込みもしない。**
 * 呼び出し側は記録を1つ作って行列へ入れるだけで、文字列化と書き込みは別スレッドがまとめてやる。
 * 1イベントごとに flush すると打鍵のたびにディスクの仕事が乗り、
 * **測ること自体が測る対象を変える**。
 *
 * 連番は呼んだスレッドで採番するので、書き込みが遅れても順序は実際の順序のまま。
 */
class JsonlTrace(context: Context) : ImeTrace, AutoCloseable {

    private class Record(
        val seq: Long,
        val sessionId: String,
        val testId: String,
        val source: String,
        val event: String,
        val fields: Array<out Any?>
    ) {
        val wallTimeMs: Long = System.currentTimeMillis()
        val elapsedNanos: Long = SystemClock.elapsedRealtimeNanos()
        val thread: String = Thread.currentThread().name
    }

    private val appContext = context.applicationContext
    private val queue = ConcurrentLinkedQueue<Record>()
    private val sequence = AtomicLong()
    private val callIds = AtomicLong()

    private val writerThread = HandlerThread("kiwa-trace-writer").apply { start() }
    private val writerHandler = Handler(writerThread.looper)

    @Volatile private var sessionId: String = "-"
    @Volatile private var testId: String = "-"
    @Volatile var currentFile: File? = null
        private set
    @Volatile private var running = true

    /** 書き込みスレッドだけが触る。 */
    private var writer: BufferedWriter? = null

    private val drainTask = object : Runnable {
        override fun run() {
            drain()
            if (running) writerHandler.postDelayed(this, DRAIN_INTERVAL_MS)
        }
    }

    init {
        writerHandler.postDelayed(drainTask, DRAIN_INTERVAL_MS)
    }

    override fun nextCall(): Long = callIds.incrementAndGet()

    override fun event(source: String, event: String, vararg fields: Any?) {
        if (!running) return
        queue.add(Record(sequence.incrementAndGet(), sessionId, testId, source, event, fields))
    }

    /**
     * 新しいセッションのファイルを開く。書き込みスレッドが切り替え終わるまで待つので、
     * 戻ってきた時点でパスを画面に出せる。
     */
    @Throws(IOException::class)
    fun startNewSession(): File {
        val base = appContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: appContext.filesDir
        val dir = File(base, "kiwa")
        if (!dir.exists() && !dir.mkdirs()) throw IOException("ログの置き場を作れない: $dir")

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val file = File(dir, "session-$stamp.jsonl")

        var failure: IOException? = null
        runOnWriterThreadBlocking {
            closeWriter()
            failure = runCatching { writer = BufferedWriter(FileWriter(file, true)) }
                .exceptionOrNull() as? IOException
        }
        failure?.let { throw it }

        sessionId = stamp
        currentFile = file
        // 連番と call id は**わざと戻さない**。セッションを開く前に採番済みのイベントと
        // 衝突すると、seq が重複して順序の解析が壊れる。区切りは sessionId が持つ。
        testId = "-"
        event("kiwa", "session.start", "path", file.absolutePath)
        return file
    }

    fun setTestId(id: String) {
        testId = id
        event("kiwa", "testId", "value", id)
        flushNow()
    }

    fun currentTestId(): String = testId

    /** 手で置く区切り。あとから1つの連続したログをテスト項目ごとに切るため。 */
    fun mark(note: String) {
        event("kiwa", "MARK", "note", note)
        flushNow()
    }

    fun flushNow() {
        writerHandler.post { drain() }
    }

    private fun drain() {
        val writer = this.writer ?: run { queue.clear(); return }
        var wrote = false
        while (true) {
            val record = queue.poll() ?: break
            writer.write(
                JsonlFormat.line(
                    record.seq, record.sessionId, record.testId, record.wallTimeMs,
                    record.elapsedNanos, record.thread, record.source, record.event, record.fields
                )
            )
            writer.write("\n")
            wrote = true
        }
        if (wrote) runCatching { writer.flush() }
    }

    private fun runOnWriterThreadBlocking(action: () -> Unit) {
        val lock = Object()
        var done = false
        writerHandler.post {
            action()
            synchronized(lock) { done = true; lock.notifyAll() }
        }
        synchronized(lock) { while (!done) lock.wait() }
    }

    private fun closeWriter() {
        runCatching { writer?.flush(); writer?.close() }
        writer = null
    }

    override fun close() {
        if (!running) return
        running = false
        runOnWriterThreadBlocking {
            drain()
            closeWriter()
        }
        writerThread.quitSafely()
    }

    private companion object {
        const val DRAIN_INTERVAL_MS = 150L
    }
}
