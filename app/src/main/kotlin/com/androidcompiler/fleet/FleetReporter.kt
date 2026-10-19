package com.androidcompiler.fleet

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

/**
 * FleetReporter: crashes, freezes (ANRs), errors and warnings for FleetGuardian on the same phone (dev hub
 * contract, section 6). Copy-in: one file, no dependencies, no permissions, minSdk 26. No public call throws.
 * See docs/fleet-reporter/README.md in the FleetGuardian repo.
 *
 *   class MyApp : Application() {
 *       override fun onCreate() {
 *           super.onCreate()
 *           FleetHealth.install(this)     // only if the app has FleetHealth
 *           FleetReporter.install(this)
 *       }
 *   }
 *
 *   FleetReporter.error("Sync", "upload failed", e)   // handled problems; also written to logcat
 *   FleetReporter.warn("Sync", "server slow, retrying")
 *   FleetReporter.report(e)                            // a caught exception worth a look
 *
 * The functions marked `internal` below (signature, redaction, caps, rate limiter, queue trimming, watchdog
 * state) are pure JVM code so the app's unit tests can cover them.
 */
object FleetReporter {
    // FleetGuardian's HealthReportProvider: call("report_issue", null, extras) -> Bundle { ok, message, retry? }.
    private const val AUTHORITY = "com.sexualmoose.fleetguardian.health"
    private const val METHOD = "report_issue"
    private const val PROTOCOL = 1
    private const val TAG = "FleetReporter"

    internal const val KIND_CRASH = "crash"
    internal const val KIND_ANR = "anr"
    internal const val KIND_ERROR = "error"
    internal const val KIND_WARNING = "warning"
    internal const val KIND_HANDLED = "handled"

    internal const val STACK_MAX_BYTES = 16 * 1024
    internal const val MESSAGE_MAX_BYTES = 2 * 1024
    internal const val LOG_MAX_BYTES = 64 * 1024
    internal const val TITLE_MAX_CHARS = 200
    internal const val RATE_WINDOW_MS = 60_000L
    internal const val QUEUE_MAX = 50
    internal const val ANR_THRESHOLD_MS = 5_000L
    internal const val STARTUP_WINDOW_MS = 5_000L
    internal const val SIGNATURE_FRAMES = 5
    private const val LOG_LINES = 400
    private const val LOG_TIMEOUT_MS = 1_500L
    private const val CRASH_BUDGET_MS = 2_000L
    private const val CALL_TIMEOUT_MS = 5_000L
    private const val WATCHDOG_TICK_MS = 1_000L

    @Volatile private var app: Context? = null
    @Volatile private var prefixes: List<String> = emptyList()
    @Volatile private var versionCode = -1L
    @Volatile private var processStart = 0L // SystemClock.elapsedRealtime() when this process started
    private val crashing = AtomicBoolean(false)
    private val watchdogStarted = AtomicBoolean(false)
    private val limiter = RateLimiter(RATE_WINDOW_MS)
    private val fileSeq = AtomicInteger()
    private val inflight: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    private val selfName: String = FleetReporter::class.java.name

    // An R8/ProGuard-minified build renames this class, and moves most app classes out of the app's packages.
    private val minified: Boolean = selfName.substringAfterLast('.') != "FleetReporter"
    private val worker: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
            1, 1, 30L, TimeUnit.SECONDS, LinkedBlockingQueue(32),
            { r -> Thread(r, "fleet-reporter").apply { isDaemon = true } },
            ThreadPoolExecutor.DiscardPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }
    }

    // ------------------------------------------------------------------ public API

    /**
     * Call once in Application.onCreate (after FleetHealth.install when the app has it). Chains an uncaught
     * exception handler, starts the main-thread watchdog and resends reports queued by earlier runs.
     */
    fun install(context: Context) {
        try {
            val ctx = context.applicationContext ?: context
            synchronized(this) {
                if (app === ctx) return
                processStart = try {
                    android.os.Process.getStartElapsedRealtime()
                } catch (_: Throwable) {
                    SystemClock.elapsedRealtime()
                }
                versionCode = readVersionCode(ctx)
                prefixes = appPackagePrefixes(ctx.packageName, ctx.javaClass.name, context.javaClass.name, selfName)
                Thread.setDefaultUncaughtExceptionHandler(CrashHandler(Thread.getDefaultUncaughtExceptionHandler()))
                app = ctx
            }
            if (watchdogStarted.compareAndSet(false, true)) startWatchdog()
            worker.execute { flushQueue() }
        } catch (_: Throwable) {
        }
    }

    /** A handled error (kind "error"). Also written to logcat like Log.e, so it can replace that call. */
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        try {
            Log.e(tag, message, throwable)
        } catch (_: Throwable) {
        }
        enqueue(KIND_ERROR, tag, message, throwable)
    }

    /** A warning (kind "warning"). Also written to logcat like Log.w, so it can replace that call. */
    fun warn(tag: String, message: String, throwable: Throwable? = null) {
        try {
            Log.w(tag, message, throwable)
        } catch (_: Throwable) {
        }
        enqueue(KIND_WARNING, tag, message, throwable)
    }

    /** A caught exception worth a look (kind "handled"), with an optional note. Not written to logcat. */
    fun report(throwable: Throwable, message: String? = null) {
        enqueue(KIND_HANDLED, null, message, throwable)
    }

    // ------------------------------------------------------------------ capture

    private class CrashHandler(private val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, e: Throwable) {
            val first = crashing.compareAndSet(false, true)
            try {
                if (first) {
                    try {
                        onCrash(thread, e)
                    } catch (_: Throwable) {
                    }
                }
                // Never swallow the crash: hand it on (FleetHealth's counter, then the platform's crash handling).
                if (previous != null) {
                    previous.uncaughtException(thread, e)
                } else {
                    try {
                        e.printStackTrace()
                    } catch (_: Throwable) {
                    }
                    android.os.Process.killProcess(android.os.Process.myPid())
                    exitProcess(10)
                }
            } finally {
                if (first) crashing.set(false)
            }
        }
    }

    /** Runs on the crashing thread: everything here fits in CRASH_BUDGET_MS, then the crash moves on. */
    private fun onCrash(thread: Thread, e: Throwable) {
        val ctx = app ?: return
        val deadline = SystemClock.uptimeMillis() + CRASH_BUDGET_MS
        val occurredMs = System.currentTimeMillis()
        val startup = (SystemClock.elapsedRealtime() - processStart) in 0L until STARTUP_WINDOW_MS
        val signature = throwableSignature(e, prefixes, minified)
        val count = limiter.acquire(signature, SystemClock.elapsedRealtime(), force = true)
        val root = causeChain(e).last()
        val message = buildString {
            append(e.toString())
            if (root !== e) append("\nCaused by: ").append(root.toString())
            append("\n(thread \"").append(thread.name).append("\")")
        }
        // Leave at least 500 ms of the budget for the hand-off to FleetGuardian.
        val logBudget = minOf(LOG_TIMEOUT_MS, deadline - SystemClock.uptimeMillis() - 500L)
        val issue = assemble(KIND_CRASH, throwableTitle(e, prefixes), message, stackText(e), occurredMs, signature, count, startup, logBudget)
        send(ctx, issue, deadline - SystemClock.uptimeMillis())
    }

    private fun enqueue(kind: String, tag: String?, note: String?, t: Throwable?) {
        try {
            val ctx = app ?: return
            val occurredMs = System.currentTimeMillis()
            val thread = Thread.currentThread().name
            val caller = if (t == null) Throwable().stackTrace else null
            val signature = if (t != null) throwableSignature(t, prefixes, minified) else messageSignature(tag.orEmpty(), note.orEmpty())
            val count = limiter.acquire(signature, SystemClock.elapsedRealtime())
            if (count == 0) return
            worker.execute {
                try {
                    val title = when {
                        tag != null && !note.isNullOrBlank() -> "$tag: $note"
                        t != null -> throwableTitle(t, prefixes)
                        else -> tag ?: kind
                    }
                    val message = buildString {
                        if (!note.isNullOrBlank()) append(note)
                        if (t != null) {
                            if (isNotEmpty()) append('\n')
                            append(t.toString())
                        }
                        append("\n(thread \"").append(thread).append("\")")
                    }
                    val stack = when {
                        t != null -> stackText(t)
                        caller != null -> framesText("Reported from thread \"$thread\":", callerFrames(caller))
                        else -> ""
                    }
                    send(ctx, assemble(kind, title, message, stack, occurredMs, signature, count, false, LOG_TIMEOUT_MS), CALL_TIMEOUT_MS)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun startWatchdog() {
        val t = Thread({
            try {
                val main = Handler(Looper.getMainLooper())
                val acked = AtomicBoolean(true)
                val ping = Runnable { acked.set(true) }
                val detector = AnrDetector(ANR_THRESHOLD_MS, WATCHDOG_TICK_MS * 3)
                while (true) {
                    val now = SystemClock.uptimeMillis()
                    when (detector.tick(now, acked.get())) {
                        AnrDetector.Step.PING -> {
                            acked.set(false)
                            main.post(ping)
                        }
                        AnrDetector.Step.REPORT ->
                            if (!crashing.get() && !Debug.isDebuggerConnected()) onAnr(detector.blockedMs(now))
                        AnrDetector.Step.WAIT -> Unit
                    }
                    Thread.sleep(WATCHDOG_TICK_MS)
                }
            } catch (_: Throwable) {
                // Interrupted or anything unexpected: the watchdog stops; the app is unaffected.
            }
        }, "fleet-reporter-watchdog")
        t.isDaemon = true
        t.start()
    }

    /** Runs on the watchdog thread: grabs the main thread's stack now, the rest happens on the worker. */
    private fun onAnr(blockedMs: Long) {
        try {
            val ctx = app ?: return
            val main = Looper.getMainLooper().thread
            val frames = main.stackTrace
            val state = main.state
            val occurredMs = System.currentTimeMillis()
            val signature = frameSignature("ANR", frames, prefixes, minified)
            val count = limiter.acquire(signature, SystemClock.elapsedRealtime())
            if (count == 0) return
            worker.execute {
                try {
                    val top = frames.firstOrNull { isAppFrame(it.className, prefixes) }
                    val title = if (top != null) "ANR: main thread blocked in ${shortFrame(top)}" else "ANR: main thread blocked"
                    val message = "Main thread blocked for ${blockedMs / 1000}.${blockedMs % 1000 / 100} s (state $state)"
                    val stack = framesText("\"main\" state=$state", frames)
                    send(ctx, assemble(KIND_ANR, title, message, stack, occurredMs, signature, count, false, LOG_TIMEOUT_MS), CALL_TIMEOUT_MS)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun assemble(
        kind: String, title: String, message: String, stack: String, occurredMs: Long,
        signature: String, count: Int, startup: Boolean, logTimeoutMs: Long,
    ) = Issue(
        kind = kind,
        fatal = kind == KIND_CRASH || kind == KIND_ANR,
        startup = startup,
        title = cleanTitle(title),
        message = cleanMessage(message),
        stack = cleanStack(stack),
        logTail = readLogTail(logTimeoutMs)?.let { cleanLog(it) },
        occurredMs = occurredMs,
        vCode = versionCode,
        signature = signature,
        count = count,
    )

    /** This process's own recent logcat lines (no READ_LOGS needed for them), or null. */
    private fun readLogTail(timeoutMs: Long): String? {
        if (timeoutMs < 100L) return null
        var proc: java.lang.Process? = null
        return try {
            val p = ProcessBuilder(
                "logcat", "-d", "-t", LOG_LINES.toString(), "--pid=${android.os.Process.myPid()}", "-v", "threadtime",
            ).redirectErrorStream(true).start()
            proc = p
            val out = ByteArrayOutputStream(64 * 1024)
            val reader = Thread({
                try {
                    p.inputStream.use { it.copyTo(out) }
                } catch (_: Throwable) {
                }
            }, "fleet-reporter-logcat")
            reader.isDaemon = true
            reader.start()
            reader.join(timeoutMs)
            out.toString("UTF-8").ifBlank { null }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Throwable) {
            null
        } finally {
            try {
                proc?.destroy()
            } catch (_: Throwable) {
            }
        }
    }

    private fun readVersionCode(ctx: Context): Long = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    } catch (_: Throwable) {
        -1L
    }

    private fun callerFrames(raw: Array<StackTraceElement>): Array<StackTraceElement> {
        var i = 0
        while (i < raw.size && (raw[i].className == selfName || raw[i].className.startsWith("$selfName$"))) i++
        return raw.copyOfRange(i, raw.size)
    }

    // ------------------------------------------------------------------ delivery + queue

    internal enum class Outcome { DELIVERED, REJECTED, KEEP }

    /**
     * What to do with a report after one delivery attempt. No answer (FleetGuardian missing, busy, dead or too
     * slow) keeps it queued; so does an explicit `retry` and an older FleetGuardian's "unknown method".
     */
    internal fun outcomeOf(answered: Boolean, ok: Boolean, retry: Boolean, message: String?): Outcome = when {
        !answered -> Outcome.KEEP
        ok -> Outcome.DELIVERED
        retry || message == "unknown method" -> Outcome.KEEP
        else -> Outcome.REJECTED
    }

    /** Write-ahead to the queue, hand to FleetGuardian within [guardMs], drop the file once it is answered. */
    private fun send(ctx: Context, issue: Issue, guardMs: Long): Outcome {
        val name = queueFileName(issue.occurredMs, android.os.Process.myPid(), fileSeq.incrementAndGet())
        inflight.add(name)
        try {
            val file = persist(ctx, name, issue)
            val outcome = deliver(ctx, issue.toBundle(), guardMs)
            if (outcome != Outcome.KEEP) file?.delete()
            try {
                Log.d(TAG, "report_issue ${issue.kind} -> $outcome")
            } catch (_: Throwable) {
            }
            return outcome
        } finally {
            inflight.remove(name)
        }
    }

    private fun queueDir(ctx: Context) = File(ctx.filesDir, "fleetreporter/queue")

    private fun persist(ctx: Context, name: String, issue: Issue): File? = try {
        val dir = queueDir(ctx)
        if (!dir.isDirectory) dir.mkdirs()
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(issue.toJson(), Charsets.UTF_8)
        val file = File(dir, name)
        if (tmp.renameTo(file)) {
            dir.list()?.let { names -> queueOverflow(names.asList()).forEach { File(dir, it).delete() } }
            file
        } else {
            tmp.delete()
            null
        }
    } catch (_: Throwable) {
        null
    }

    /** One binder call on its own thread; the caller waits at most [guardMs]. */
    private fun deliver(ctx: Context, extras: Bundle, guardMs: Long): Outcome {
        if (guardMs <= 0L) return Outcome.KEEP
        val answer = AtomicReference<Bundle?>()
        val done = CountDownLatch(1)
        val t = Thread({
            try {
                if (ctx.packageManager.resolveContentProvider(AUTHORITY, 0) != null) {
                    answer.set(ctx.contentResolver.call(Uri.parse("content://$AUTHORITY"), METHOD, null, extras))
                }
            } catch (_: Throwable) {
            } finally {
                done.countDown()
            }
        }, "fleet-reporter-call")
        t.isDaemon = true
        t.start()
        val inTime = try {
            done.await(guardMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!inTime) return Outcome.KEEP
        val res = answer.get()
        return outcomeOf(res != null, res?.getBoolean("ok", false) == true, res?.getBoolean("retry", false) == true, res?.getString("message"))
    }

    /** Resends what earlier runs could not deliver, oldest first; stops at the first one FleetGuardian can't take. */
    private fun flushQueue() {
        try {
            val ctx = app ?: return
            val dir = queueDir(ctx)
            val names = dir.list()?.asList() ?: return
            for (n in names) if (n.endsWith(".tmp") && n.removeSuffix(".tmp") !in inflight) File(dir, n).delete()
            val dropped = queueOverflow(names).toSet()
            dropped.forEach { File(dir, it).delete() }
            for (name in queuedFiles(names)) {
                if (name in dropped || name in inflight) continue
                val file = File(dir, name)
                val extras = try {
                    Issue.fromJson(file.readText(Charsets.UTF_8)).toBundle()
                } catch (_: Throwable) {
                    file.delete() // unreadable: drop it
                    continue
                }
                if (deliver(ctx, extras, CALL_TIMEOUT_MS) == Outcome.KEEP) return
                file.delete()
            }
        } catch (_: Throwable) {
        }
    }

    /** Sorts by time: zero-padded occurredMs first. */
    internal fun queueFileName(occurredMs: Long, pid: Int, seq: Int): String =
        occurredMs.coerceAtLeast(0L).toString().padStart(15, '0') + "-" +
            pid.toString().padStart(7, '0') + "-" + seq.toString().padStart(6, '0') + ".json"

    /** Queued report files, oldest first. */
    internal fun queuedFiles(names: Collection<String>): List<String> = names.filter { it.endsWith(".json") }.sorted()

    /** The oldest queued files to delete so at most [max] remain. */
    internal fun queueOverflow(names: Collection<String>, max: Int = QUEUE_MAX): List<String> {
        val queued = queuedFiles(names)
        return if (queued.size <= max) emptyList() else queued.subList(0, queued.size - max)
    }

    internal class Issue(
        val kind: String,
        val fatal: Boolean,
        val startup: Boolean,
        val title: String,
        val message: String,
        val stack: String,
        val logTail: String?,
        val occurredMs: Long,
        val vCode: Long,
        val signature: String,
        val count: Int,
    ) {
        /** The report_issue extras. Names and types are the contract with FleetGuardian. */
        fun toBundle(): Bundle = Bundle().apply {
            putInt("v", PROTOCOL)
            putString("kind", kind)
            putBoolean("fatal", fatal)
            putBoolean("startup", startup)
            putString("title", title)
            putString("message", message)
            putString("stack", stack)
            putString("logTail", logTail)
            putLong("occurredMs", occurredMs)
            putLong("vCode", vCode)
            putString("signature", signature)
            putInt("count", count)
        }

        fun toJson(): String = JSONObject().apply {
            put("v", PROTOCOL)
            put("kind", kind)
            put("fatal", fatal)
            put("startup", startup)
            put("title", title)
            put("message", message)
            put("stack", stack)
            if (logTail != null) put("logTail", logTail)
            put("occurredMs", occurredMs)
            put("vCode", vCode)
            put("signature", signature)
            put("count", count)
        }.toString()

        companion object {
            fun fromJson(text: String): Issue {
                val o = JSONObject(text)
                return Issue(
                    kind = o.getString("kind"),
                    fatal = o.optBoolean("fatal"),
                    startup = o.optBoolean("startup"),
                    title = o.optString("title"),
                    message = o.optString("message"),
                    stack = o.optString("stack"),
                    logTail = if (o.isNull("logTail")) null else o.getString("logTail"),
                    occurredMs = o.getLong("occurredMs"),
                    vCode = o.getLong("vCode"),
                    signature = o.getString("signature"),
                    count = o.optInt("count", 1),
                )
            }
        }
    }

    // ------------------------------------------------------------------ pure: signatures

    private val LIBRARY_PREFIXES = arrayOf(
        "java.", "javax.", "jdk.", "sun.", "kotlin.", "kotlinx.", "android.", "androidx.", "com.android.", "dalvik.",
        "libcore.", "org.json.", "org.xml.", "org.w3c.", "org.apache.", "org.jetbrains.", "com.google.", "okhttp3.",
        "okio.", "retrofit2.", "io.ktor.", "coil.", "ai.onnxruntime.", "com.squareup.",
    )

    /**
     * The app's own code packages: the Application class's package, this file's package and the package name,
     * plus their shared root (e.g. com.x.app and com.x.app.fleet -> com.x.app). Framework packages never count.
     */
    internal fun appPackagePrefixes(packageName: String?, vararg classNames: String?): List<String> {
        fun ok(p: String) = p.contains('.') && LIBRARY_PREFIXES.none { "$p.".startsWith(it) }
        val pkgs = classNames.mapNotNull { n -> n?.substringBeforeLast('.', "")?.takeIf { it.isNotEmpty() && ok(it) } }.distinct()
        val out = LinkedHashSet<String>()
        if (pkgs.size >= 2) out.add(pkgs.reduce { a, b -> commonPackage(a, b) })
        out.addAll(pkgs)
        if (!packageName.isNullOrEmpty()) {
            out.add(packageName)
            out.add(packageName.removeSuffix(".debug"))
        }
        return out.filter { ok(it) }
    }

    internal fun commonPackage(a: String, b: String): String {
        val x = a.split('.')
        val y = b.split('.')
        var i = 0
        while (i < x.size && i < y.size && x[i] == y[i]) i++
        return x.take(i).joinToString(".")
    }

    private fun isReporterClass(className: String): Boolean {
        val simple = className.substringAfterLast('.')
        return simple.startsWith("FleetReporter") || simple.startsWith("FleetHealth") ||
            className == selfName || className.startsWith("$selfName$")
    }

    internal fun isAppFrame(className: String, appPrefixes: Collection<String>): Boolean =
        !isReporterClass(className) && appPrefixes.any { className.startsWith("$it.") }

    private fun isNonLibraryFrame(className: String): Boolean =
        !isReporterClass(className) && LIBRARY_PREFIXES.none { className.startsWith(it) }

    /** In a minified build the app's renamed classes count as its own (they are the non-library frames). */
    private fun isOwnFrame(className: String, appPrefixes: Collection<String>, minified: Boolean): Boolean =
        if (minified) isNonLibraryFrame(className) else isAppFrame(className, appPrefixes)

    /** Class + method only (no file, no line), with compiler-generated lambda numbers removed. */
    internal fun frameKey(e: StackTraceElement): String = try {
        Rx.GENERATED_NUMBER.replace(e.className, "") + "." + Rx.GENERATED_NUMBER.replace(e.methodName, "")
    } catch (_: Throwable) {
        e.className + "." + e.methodName
    }

    /** The top [n] frames of the app's own code; else of any non-library code; else of anything. */
    internal fun topFrames(
        frames: Array<StackTraceElement>, appPrefixes: Collection<String>, minified: Boolean = false, n: Int = SIGNATURE_FRAMES,
    ): List<String> {
        var pick = frames.filter { isOwnFrame(it.className, appPrefixes, minified) }
        if (pick.isEmpty()) pick = frames.filter { isNonLibraryFrame(it.className) }
        if (pick.isEmpty()) pick = frames.asList()
        return pick.take(n).map { frameKey(it) }
    }

    /** sha1(head + top frames); with no frames at all, sha1(head + message template). */
    internal fun frameSignature(
        head: String, frames: Array<StackTraceElement>, appPrefixes: Collection<String>, minified: Boolean = false, message: String? = null,
    ): String {
        val top = topFrames(frames, appPrefixes, minified)
        val body = if (top.isEmpty()) messageTemplate(message.orEmpty()) else top.joinToString("\n")
        return sha1Hex(head + "\n" + body)
    }

    /** sha1 of the root cause's class + the top 5 app frames of the deepest throwable in the chain that has any. */
    internal fun throwableSignature(t: Throwable, appPrefixes: Collection<String>, minified: Boolean = false): String {
        val chain = causeChain(t)
        val root = chain.last()
        val source = chain.asReversed().firstOrNull { th -> th.stackTrace.any { isOwnFrame(it.className, appPrefixes, minified) } } ?: root
        return frameSignature(root.javaClass.name, source.stackTrace, appPrefixes, minified, root.message)
    }

    /** sha1 of tag + the message with numbers, ids and secrets taken out. */
    internal fun messageSignature(tag: String, message: String): String = sha1Hex(tag + "\n" + messageTemplate(message))

    internal fun messageTemplate(message: String): String = try {
        var s = redact(headChars(message, 4096))
        s = Rx.UUID.replace(s, "#")
        s = Rx.HEX_LITERAL.replace(s, "#")
        s = Rx.HEXISH_WORD.replace(s, "#")
        s = Rx.DIGITS.replace(s, "#")
        Rx.SPACES.replace(s, " ").trim().take(300)
    } catch (_: Throwable) {
        REDACTION_UNAVAILABLE
    }

    internal fun causeChain(t: Throwable): List<Throwable> {
        val out = ArrayList<Throwable>()
        var cur: Throwable? = t
        while (cur != null && out.size < 16 && out.none { it === cur }) {
            out.add(cur)
            cur = cur.cause
        }
        return out
    }

    internal fun sha1Hex(s: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            sb.append(hex[v ushr 4]).append(hex[v and 0x0f])
        }
        return sb.toString()
    }

    internal fun throwableTitle(t: Throwable, appPrefixes: Collection<String>): String {
        val root = causeChain(t).last()
        val name = root.javaClass.simpleName.ifEmpty { root.javaClass.name.substringAfterLast('.') }
        val msg = firstLine(root.message)
        if (msg.isNotEmpty()) return "$name: $msg"
        val top = root.stackTrace.firstOrNull { isAppFrame(it.className, appPrefixes) }
        return if (top != null) "$name in ${shortFrame(top)}" else name
    }

    internal fun shortFrame(e: StackTraceElement): String = e.className.substringAfterLast('.') + "." + e.methodName

    internal fun stackText(t: Throwable): String = try {
        val sw = StringWriter()
        PrintWriter(sw).use { t.printStackTrace(it) }
        sw.toString()
    } catch (_: Throwable) {
        t.toString()
    }

    internal fun framesText(header: String, frames: Array<StackTraceElement>): String = buildString {
        append(header)
        for (f in frames) append("\n\tat ").append(f)
    }

    private fun firstLine(s: String?): String = s?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }.orEmpty()

    // ------------------------------------------------------------------ pure: redaction

    private const val SECRET_WORDS = "password|passwd|pwd|token|secret|apikey|api_key|api-key|auth|session|cookie"
    private const val KEY_CHAR = "[A-Za-z0-9_.\\-]"
    private const val SECRET_VALUE =
        "(\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|(?:(?:bearer|basic|digest|token)[ \\t]+)?[^\\s\"',;\\&<>\\(\\)\\{\\}\\[\\]]+)"

    /** What [redact] returns if the patterns can't run: text that could not be redacted is never handed on. */
    internal const val REDACTION_UNAVAILABLE = "[redacted: no regex support]"

    /**
     * Every regex, compiled on first use instead of when FleetReporter loads. Android's regex engine (ICU) is
     * stricter than the JVM's, so a pattern it rejected must not be able to break install(); [redact],
     * [frameKey] and [messageTemplate] catch that and fall back. Inside [...] every '-', '$', '&', brace and
     * parenthesis is escaped, and no class starts with ':' (ICU reads "[:" as a POSIX class).
     */
    private object Rx {
        val GENERATED_NUMBER = Regex("(?<=[\\$\\-])\\d+|(?<=Lambda)\\d+|(?<=Outline)\\d+")
        val UUID = Regex("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b")
        val HEX_LITERAL = Regex("\\b0[xX][0-9a-fA-F]+\\b")
        val HEXISH_WORD = Regex("(?<![A-Za-z0-9])[0-9a-fA-F]*[0-9][0-9a-fA-F]*(?![A-Za-z0-9])")
        val DIGITS = Regex("\\d+")
        val SPACES = Regex("\\s+")

        /** Cookie headers ("Cookie: a=1; b=2"): everything after the name, to the end of the line. */
        val COOKIE_LINE = Regex("(?i)\\b((?:set-)?cookie2?)([ \\t]*:[ \\t]*)[^\\r\\n]+")
        val BEARER = Regex("(?i)\\b(bearer)([ \\t]+)[A-Za-z0-9_.~+/=\\-]{8,}")
        val JWT = Regex("\\beyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}(?:\\.[A-Za-z0-9_\\-]+)?")
        val API_TOKEN = Regex(
            "\\b(?:sk-[A-Za-z0-9_\\-]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}" +
                "|xox[abposr]-[A-Za-z0-9\\-]{10,}|AIza[0-9A-Za-z_\\-]{30,}|AKIA[0-9A-Z]{16}|ya29\\.[0-9A-Za-z_\\-]{10,}" +
                "|tskey-[A-Za-z0-9\\-]{10,}|hf_[A-Za-z0-9]{20,}|glpat-[A-Za-z0-9_\\-]{20,}|[spr]k_(?:live|test)_[A-Za-z0-9]{10,})",
        )

        /** key=value where the key CONTAINS a secret word: password=…, access_token=…, X-Session-Id=…. */
        val SECRET_ASSIGN = Regex(
            "(?i)(?<!$KEY_CHAR)($KEY_CHAR{0,64}?(?:$SECRET_WORDS)$KEY_CHAR{0,64}+)([\"']?[ \\t]*=[ \\t]*)$SECRET_VALUE",
        )

        /**
         * key: value where the key ENDS with a secret word: "password": "…", Authorization: …, X-Auth-Token: ….
         * (Ends-with keeps logcat tags such as "AuthManager: signed in" readable.)
         */
        val SECRET_FIELD = Regex(
            "(?i)(?<!$KEY_CHAR)($KEY_CHAR{0,48}?(?:password|passwd|pwd|token|secret|api[_\\-]?key|authorization|auth" +
                "|session[_\\-]?id|session|cookie))([\"']?[ \\t]*:[ \\t]*)$SECRET_VALUE",
        )
        val EMAIL = Regex(
            "(?<![A-Za-z0-9._%+\\-])[A-Za-z0-9._%+\\-]{1,64}@[A-Za-z0-9\\-]{1,63}(?:\\.[A-Za-z0-9\\-]{1,63})*\\.[A-Za-z]{2,24}\\b",
        )
        val LONG_RUN = Regex("(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{24,}={0,2}")
    }

    /**
     * Masks emails, bearer/API tokens and JWTs, cookie headers, values of secret-looking keys, and long
     * hex/base64 runs (24+ chars). If the patterns can't run, returns [REDACTION_UNAVAILABLE] instead of the text.
     */
    internal fun redact(text: String): String {
        if (text.isEmpty()) return text
        return try {
            var s = Rx.COOKIE_LINE.replace(text) { it.groupValues[1] + it.groupValues[2] + "[redacted]" }
            s = Rx.BEARER.replace(s) { it.groupValues[1] + it.groupValues[2] + "[token]" }
            s = Rx.JWT.replace(s, "[token]")
            s = Rx.API_TOKEN.replace(s, "[token]")
            s = Rx.SECRET_ASSIGN.replace(s) { maskValue(it) }
            s = Rx.SECRET_FIELD.replace(s) { maskValue(it) }
            s = Rx.EMAIL.replace(s, "[email]")
            Rx.LONG_RUN.replace(s) { if (looksSecret(it.value)) "[redacted]" else it.value }
        } catch (_: Throwable) {
            REDACTION_UNAVAILABLE
        }
    }

    private fun maskValue(m: MatchResult): String {
        val v = m.groupValues[3]
        val masked = when {
            v.startsWith("\"") -> "\"[redacted]\""
            v.startsWith("'") -> "'[redacted]'"
            else -> "[redacted]"
        }
        return m.groupValues[1] + m.groupValues[2] + masked
    }

    /**
     * A 24+ char hex/base64 run is masked when it has a digit. With a slash in it, it must also look like
     * base64 rather than a file or URL path: mixed case and a 12+ char stretch between slashes.
     */
    internal fun looksSecret(run: String): Boolean {
        val body = run.trimEnd('=')
        if (body.none { it in '0'..'9' }) return false
        if ('/' !in body) return true
        return body.split('/').maxOf { it.length } >= 12 && body.any { it in 'A'..'Z' } && body.any { it in 'a'..'z' }
    }

    // ------------------------------------------------------------------ pure: caps

    private const val MARK_RESERVE = 40

    internal fun cleanTitle(raw: String): String = takeChars(firstLine(redact(headChars(raw, 1024))), TITLE_MAX_CHARS)

    internal fun cleanMessage(raw: String): String = capHead(redact(headChars(raw, MESSAGE_MAX_BYTES * 4)), MESSAGE_MAX_BYTES)

    internal fun cleanStack(raw: String): String {
        val s = if (raw.length <= STACK_MAX_BYTES * 4) raw else headChars(raw, STACK_MAX_BYTES * 3) + "\n" + tailChars(raw, STACK_MAX_BYTES)
        return capStack(redact(s), STACK_MAX_BYTES)
    }

    /** Keeps the NEWEST lines. */
    internal fun cleanLog(raw: String): String = capTail(redact(tailChars(raw, LOG_MAX_BYTES * 2)), LOG_MAX_BYTES)

    internal fun utf8Length(s: CharSequence): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val pair = Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])
            n += when {
                pair -> 4
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                else -> 3
            }
            i += if (pair) 2 else 1
        }
        return n
    }

    /** Keeps the start within [maxBytes] UTF-8 bytes, marker included. */
    internal fun capHead(s: String, maxBytes: Int): String {
        val total = utf8Length(s)
        if (total <= maxBytes) return s
        if (maxBytes <= MARK_RESERVE) return s.substring(0, prefixEnd(s, maxBytes))
        val head = s.substring(0, prefixEnd(s, maxBytes - MARK_RESERVE))
        return head + "\n" + cutMark(total - utf8Length(head))
    }

    /** Keeps the end within [maxBytes] UTF-8 bytes, marker included. */
    internal fun capTail(s: String, maxBytes: Int): String {
        val total = utf8Length(s)
        if (total <= maxBytes) return s
        if (maxBytes <= MARK_RESERVE) return s.substring(suffixStart(s, maxBytes))
        val tail = s.substring(suffixStart(s, maxBytes - MARK_RESERVE))
        return cutMark(total - utf8Length(tail)) + "\n" + tail
    }

    /** Keeps the top three quarters and the bottom quarter (the root cause) within [maxBytes]. */
    internal fun capStack(s: String, maxBytes: Int): String {
        val total = utf8Length(s)
        if (total <= maxBytes) return s
        if (maxBytes <= MARK_RESERVE * 2) return capHead(s, maxBytes)
        val budget = maxBytes - MARK_RESERVE
        val head = s.substring(0, prefixEnd(s, budget * 3 / 4))
        val rest = s.substring(head.length)
        val tail = rest.substring(suffixStart(rest, budget - utf8Length(head)))
        return head + "\n\t" + cutMark(total - utf8Length(head) - utf8Length(tail)) + "\n" + tail
    }

    private fun cutMark(droppedBytes: Int) = "…[$droppedBytes bytes cut]…"

    private fun prefixEnd(s: String, maxBytes: Int): Int {
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val pair = Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])
            val b = when {
                pair -> 4
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                else -> 3
            }
            if (bytes + b > maxBytes) break
            bytes += b
            i += if (pair) 2 else 1
        }
        return i
    }

    private fun suffixStart(s: String, maxBytes: Int): Int {
        var bytes = 0
        var i = s.length
        while (i > 0) {
            val c = s[i - 1]
            val pair = Character.isLowSurrogate(c) && i >= 2 && Character.isHighSurrogate(s[i - 2])
            val b = when {
                pair -> 4
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                else -> 3
            }
            if (bytes + b > maxBytes) break
            bytes += b
            i -= if (pair) 2 else 1
        }
        return i
    }

    /** Cheap pre-trim before redaction (the byte caps run after it); cuts at a line break when one is near. */
    internal fun headChars(s: String, n: Int): String {
        if (s.length <= n) return s
        val nl = s.lastIndexOf('\n', n)
        return s.substring(0, if (nl > n / 2) nl else n)
    }

    internal fun tailChars(s: String, n: Int): String {
        if (s.length <= n) return s
        val from = s.length - n
        val nl = s.indexOf('\n', from)
        return s.substring(if (nl >= 0 && nl < from + n / 2) nl + 1 else from)
    }

    private fun takeChars(s: String, n: Int): String =
        if (s.length <= n) s else s.substring(0, if (Character.isHighSurrogate(s[n - 1])) n - 1 else n)

    // ------------------------------------------------------------------ pure: rate limiter + watchdog state

    /** One report per [windowMs] per signature; counts what it holds back so the next report carries it. */
    internal class RateLimiter(private val windowMs: Long, private val maxKeys: Int = 256) {
        private class Slot(val atMs: Long, var held: Int)

        private val slots = object : LinkedHashMap<String, Slot>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Slot>?): Boolean = size > maxKeys
        }

        /** 0 = hold this one back; otherwise the count to report (this one + those held back since the last). */
        @Synchronized
        fun acquire(key: String, nowMs: Long, force: Boolean = false): Int {
            val slot = slots[key]
            if (!force && slot != null && nowMs >= slot.atMs && nowMs - slot.atMs < windowMs) {
                slot.held++
                return 0
            }
            slots[key] = Slot(nowMs, 0)
            return 1 + (slot?.held ?: 0)
        }
    }

    /**
     * Main-thread watchdog state. One ping is outstanding at a time; the main thread answering it ends an
     * episode. REPORT comes once per episode, after [thresholdMs] without an answer. A tick gap over
     * [maxGapMs] (process frozen, device asleep) restarts the clock instead of counting as a freeze.
     */
    internal class AnrDetector(private val thresholdMs: Long, private val maxGapMs: Long) {
        enum class Step { PING, WAIT, REPORT }

        private var pingAt = -1L
        private var lastTick = -1L
        private var reported = false

        fun tick(now: Long, acked: Boolean): Step {
            val gap = if (lastTick < 0L) 0L else now - lastTick
            lastTick = now
            if (pingAt < 0L || acked) {
                reported = false
                pingAt = now
                return Step.PING
            }
            if (gap > maxGapMs || gap < 0L) {
                pingAt = now
                return Step.WAIT
            }
            if (!reported && now - pingAt > thresholdMs) {
                reported = true
                return Step.REPORT
            }
            return Step.WAIT
        }

        fun blockedMs(now: Long): Long = if (pingAt < 0L) 0L else now - pingAt
    }
}
