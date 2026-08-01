package jadxslides

import fi.iki.elonen.NanoHTTPD
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Local bridge on 127.0.0.1: serves the rendered Marp deck (plus its assets)
 * and handles `/jump?t=<token>` clicks from either the embedded JCEF view or
 * an external browser. CORS is open because Slidev decks call /jump from the
 * vite dev server's origin.
 */
class BridgeServer : NanoHTTPD("127.0.0.1", 0) {
    private val log = LoggerFactory.getLogger(BridgeServer::class.java)
    private val version = AtomicLong(1)

    // NanoHTTPD logs SEVERE "Broken pipe" whenever the browser drops a
    // connection mid-response (every live-reload does this); drop only the
    // socket-error records so real server failures still reach the log —
    // kept as a field so JUL can't GC the setting away
    private val nanoLogger = java.util.logging.Logger
        .getLogger(NanoHTTPD::class.java.name)
        .apply {
            filter = java.util.logging.Filter { record ->
                var t = record.thrown
                while (t != null) {
                    if (t is java.net.SocketException || t is java.net.SocketTimeoutException) {
                        return@Filter false
                    }
                    t = t.cause
                }
                true
            }
        }

    /** Marp html to serve at `/` and the dir static assets resolve against.
     * deckOwner identifies which session set them — File equality is
     * path-based, so reopening the same deck needs an identity check. */
    @Volatile var deckHtml: File? = null
    @Volatile var deckDir: File? = null
    @Volatile var deckOwner: Any? = null

    private val pendingJump = java.util.concurrent.atomic.AtomicReference<String?>()
    private val jumpExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "jadx-slides-jump").apply { isDaemon = true }
    }

    val port: Int get() = listeningPort

    fun bumpVersion() {
        version.incrementAndGet()
    }

    private fun cors(r: Response): Response {
        r.addHeader("Access-Control-Allow-Origin", "*")
        return r
    }

    private fun noCache(r: Response): Response {
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            route(session)
        } catch (e: Exception) {
            log.warn("bridge request failed: {}", session.uri, e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "error")
        }
    }

    private fun route(session: IHTTPSession): Response {
        when (session.uri) {
            "/jump" -> {
                val token = session.parms["t"]
                if (!token.isNullOrBlank()) {
                    // resolution may decompile classes — keep it off this
                    // request thread (respond immediately) and off the EDT.
                    // One worker + latest-token-wins: this is an open local
                    // endpoint and only one jump can land anyway, so rapid
                    // clicks must not each spawn a decompilation thread
                    pendingJump.set(token)
                    jumpExecutor.execute {
                        val t = pendingJump.getAndSet(null) ?: return@execute
                        JumpService.jump(t)
                    }
                }
                return cors(newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", ""))
            }

            "/version" -> return cors(
                noCache(
                    newFixedLengthResponse(
                        Response.Status.OK, "text/plain", version.get().toString(),
                    ),
                ),
            )

            "/", "/index.html" -> {
                val html = deckHtml
                if (html == null || !html.isFile) {
                    return noCache(
                        newFixedLengthResponse(
                            Response.Status.OK, "text/html",
                            "<html><body style='font-family:sans-serif'>" +
                                    "<h3>jadx-slides</h3><p>No deck is open.</p></body></html>",
                        ),
                    )
                }
                return noCache(
                    newFixedLengthResponse(
                        Response.Status.OK, "text/html", injectLiveReload(html.readText()),
                    ),
                )
            }

            else -> return serveStatic(session.uri)
        }
    }

    private fun serveStatic(uri: String): Response {
        val dir = deckDir
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        val target = File(dir, uri.trimStart('/'))
        val canonical = target.canonicalFile
        // component-wise containment: a raw string prefix would also admit
        // sibling dirs like <deckDir>-private, and NanoHTTPD percent-decodes
        // but does not normalize "..", so canonicalize before comparing
        if (!canonical.toPath().startsWith(dir.canonicalFile.toPath()) || !canonical.isFile) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        }
        val mime = getMimeTypeForFile(canonical.name)
        return newFixedLengthResponse(
            Response.Status.OK, mime, FileInputStream(canonical), canonical.length(),
        )
    }

    /**
     * Injected into the served Marp html: poll /version and reload on change,
     * restoring the current slide. Bespoke only repositions on hashchange, so
     * the restore flips to slide 0 first, then to the saved hash.
     */
    private fun injectLiveReload(html: String): String {
        val script = """
            <script>
            (function () {
              var saved = sessionStorage.getItem('jadxSlidesHash');
              if (saved) {
                sessionStorage.removeItem('jadxSlidesHash');
                location.hash = '#/0';
                location.hash = saved;
              }
              var v = null;
              setInterval(function () {
                fetch('/version').then(function (r) { return r.text(); }).then(function (t) {
                  if (v === null) { v = t; return; }
                  if (t !== v) {
                    sessionStorage.setItem('jadxSlidesHash', location.hash);
                    location.reload();
                  }
                }).catch(function () {});
              }, 700);
            })();
            </script>
        """.trimIndent()
        val idx = html.lastIndexOf("</body>", ignoreCase = true)
        return if (idx >= 0) {
            html.substring(0, idx) + script + html.substring(idx)
        } else {
            html + script
        }
    }
}
