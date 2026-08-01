package jadxslides

import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.TimeUnit

enum class Engine { MARP, SLIDEV, HTML }

object Engines {
    private val LOG = LoggerFactory.getLogger(Engines::class.java)

    private val ANSI_RE = Regex("\u001B\\[[0-9;]*m")
    private val SLIDEV_URL_RE = Regex("https?://[A-Za-z0-9.\\-]+:(\\d+)/")

    private val SLIDEV_FM_KEYS = setOf(
        "transition", "mdc", "drawings", "highlighter", "monaco", "colorSchema",
        "routerMode", "canvasWidth", "aspectRatio", "fonts", "addons",
        "titleTemplate", "presenter", "browserExporter", "htmlAttrs",
        "lineNumbers", "record", "selectable", "seoMeta", "favicon", "info",
    )

    /**
     * Pick the engine for a deck. Explicit `jadx-slides-engine:` front matter
     * wins; then `marp: true`; then any Slidev-specific key (if its CLI is
     * installed). Marp is the default. `.html` files load directly.
     */
    fun detect(path: Path, text: String): Engine {
        val name = path.fileName.toString().lowercase()
        if (name.endsWith(".html") || name.endsWith(".htm")) return Engine.HTML

        val keys = MarpMarkdown.frontMatterKeys(text)
        when (keys["jadx-slides-engine"]?.lowercase()) {
            "marp" -> return Engine.MARP
            "slidev" -> return Engine.SLIDEV
        }
        val marpVal = keys["marp"]?.lowercase()
        if (marpVal != null && marpVal !in setOf("false", "no", "off", "0")) {
            return Engine.MARP
        }
        if (keys.keys.any { it in SLIDEV_FM_KEYS } && CliDiscovery.find("slidev") != null) {
            return Engine.SLIDEV
        }
        return Engine.MARP
    }

    /** One-shot marp render: prepared .md -> .html. Returns error message or null. */
    fun renderMarp(prepared: Path, out: Path): String? {
        val marp = CliDiscovery.find("marp")
            ?: return "marp CLI not found — install with: npm i -g @marp-team/marp-cli"
        var proc: Process? = null
        return try {
            val pb = ProcessBuilder(
                CliDiscovery.command(
                    marp, prepared.toString(),
                    "-o", out.toString(), "--html", "--no-stdin",
                ),
            )
            pb.directory(prepared.parent.toFile())
            pb.environment().putAll(CliDiscovery.childEnv(marp))
            pb.redirectErrorStream(true)
            val p = pb.start()
            proc = p
            // drain on a separate thread: reading to EOF inline would make
            // the 90s timeout unreachable when marp hangs with the pipe open
            val output = StringBuilder()
            val drain = Thread({
                p.inputStream.bufferedReader().forEachLine {
                    synchronized(output) { output.appendLine(it) }
                }
            }, "jadx-slides-marp-log").apply { isDaemon = true }
            drain.start()
            if (!p.waitFor(90, TimeUnit.SECONDS)) {
                killTreeForcibly(p)
                return "marp timed out"
            }
            drain.join(2_000)
            if (p.exitValue() != 0 || !out.toFile().isFile) {
                LOG.warn("marp failed: {}", synchronized(output) { output.toString() })
                "marp failed (exit ${p.exitValue()}) — see log"
            } else null
        } catch (e: InterruptedException) {
            // session closed mid-render: don't let the child outlive us
            proc?.let { killTreeForcibly(it) }
            Thread.currentThread().interrupt()
            "marp render interrupted"
        } catch (e: Exception) {
            LOG.error("marp spawn failed", e)
            "failed to start marp: ${e.message}"
        }
    }

    private fun portOpen(host: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 200) }
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Kill a spawned CLI and its whole process tree: on Windows the Process
     * is the cmd.exe shim and destroying only it orphans the node child
     * actually running vite/marp.
     */
    private fun killTree(process: Process) {
        process.toHandle().descendants().forEach { it.destroy() }
        process.destroy()
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
            process.toHandle().descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }

    private fun killTreeForcibly(process: Process) {
        process.toHandle().descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    /** A running slidev dev server bound to a local port. */
    class SlidevServer(private val process: Process, val port: Int, host: String) {
        // vite may listen on one stack only (Node ≥17 binds "localhost" to
        // ::1); point at the address that actually answered
        val url: String = if (host.contains(':')) "http://[$host]:$port/" else "http://$host:$port/"
        fun stop() {
            killTree(process)
        }
    }

    fun startSlidev(prepared: Path): Pair<SlidevServer?, String?> {
        val slidev = CliDiscovery.find("slidev")
            ?: return null to "slidev CLI not found — install with: npm i -g @slidev/cli @slidev/theme-default"
        val port = ServerSocket(0).use { it.localPort }
        var started: Process? = null
        return try {
            val pb = ProcessBuilder(
                CliDiscovery.command(
                    slidev, prepared.fileName.toString(),
                    "--port", port.toString(),
                ),
            )
            pb.directory(prepared.parent.toFile())
            pb.environment().putAll(CliDiscovery.childEnv(slidev))
            pb.redirectErrorStream(true)
            val proc = pb.start()
            started = proc
            // drain output so the child never blocks on a full pipe; keep a
            // tail so startup failures carry a real reason and the banner
            // (with the authoritative URL) stays parseable
            val tail = ArrayDeque<String>()
            Thread {
                proc.inputStream.bufferedReader().forEachLine {
                    LOG.debug("slidev: {}", it)
                    synchronized(tail) {
                        tail.addLast(it)
                        if (tail.size > 40) tail.removeFirst()
                    }
                }
            }.apply { isDaemon = true; name = "jadx-slides-slidev-log" }.start()

            fun lastOutput() = synchronized(tail) {
                tail.filter { it.isNotBlank() }.takeLast(4).joinToString("\n")
            }

            // vite silently auto-increments when the requested port is taken
            // (a TOCTOU against our ServerSocket probe), so trust the port
            // slidev itself prints over the one we asked for. The banner is
            // ANSI-colored — strip escapes before matching.
            fun bannerPort(): Int? = synchronized(tail) {
                tail.firstNotNullOfOrNull { line ->
                    ANSI_RE.replace(line, "")
                        .let { SLIDEV_URL_RE.find(it) }
                        ?.groupValues?.get(1)?.toIntOrNull()
                }
            }

            val deadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < deadline) {
                if (!proc.isAlive) {
                    return null to "slidev exited early:\n${lastOutput()}"
                }
                val actualPort = bannerPort()
                if (actualPort != null) {
                    // Node ≥17 may bind "localhost" to ::1 only — probe both
                    val host = listOf("127.0.0.1", "::1").firstOrNull { portOpen(it, actualPort) }
                    if (host != null) {
                        return SlidevServer(proc, actualPort, host) to null
                    }
                }
                Thread.sleep(300)
            }
            killTreeForcibly(proc)
            null to "slidev did not start within 60s:\n${lastOutput()}"
        } catch (e: InterruptedException) {
            // session closed while we waited for the banner — the node tree
            // is already running and must not outlive us (mirrors renderMarp)
            started?.let { killTreeForcibly(it) }
            Thread.currentThread().interrupt()
            null to "slidev startup interrupted"
        } catch (e: Exception) {
            LOG.error("slidev spawn failed", e)
            started?.let { killTreeForcibly(it) }
            null to "failed to start slidev: ${e.message}"
        }
    }
}
