package jadxslides

import org.slf4j.LoggerFactory
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.TimeUnit

enum class Engine { MARP, SLIDEV, HTML }

object Engines {
    private val LOG = LoggerFactory.getLogger(Engines::class.java)

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
        return try {
            val pb = ProcessBuilder(
                marp.absolutePath, prepared.toString(),
                "-o", out.toString(), "--html", "--no-stdin",
            )
            pb.directory(prepared.parent.toFile())
            pb.environment().putAll(CliDiscovery.childEnv(marp))
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(90, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return "marp timed out"
            }
            if (proc.exitValue() != 0 || !out.toFile().isFile) {
                LOG.warn("marp failed: {}", output)
                "marp failed (exit ${proc.exitValue()}) — see log"
            } else null
        } catch (e: Exception) {
            LOG.error("marp spawn failed", e)
            "failed to start marp: ${e.message}"
        }
    }

    /** A running slidev dev server bound to a local port. */
    class SlidevServer(private val process: Process, val port: Int) {
        val url: String get() = "http://localhost:$port/"
        fun stop() {
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    fun startSlidev(prepared: Path): Pair<SlidevServer?, String?> {
        val slidev = CliDiscovery.find("slidev")
            ?: return null to "slidev CLI not found — install with: npm i -g @slidev/cli @slidev/theme-default"
        val port = ServerSocket(0).use { it.localPort }
        return try {
            val pb = ProcessBuilder(
                slidev.absolutePath, prepared.fileName.toString(),
                "--port", port.toString(),
            )
            pb.directory(prepared.parent.toFile())
            pb.environment().putAll(CliDiscovery.childEnv(slidev))
            pb.redirectErrorStream(true)
            val proc = pb.start()
            // drain output so the child never blocks on a full pipe
            Thread {
                proc.inputStream.bufferedReader().forEachLine { LOG.debug("slidev: {}", it) }
            }.apply { isDaemon = true; name = "jadx-slides-slidev-log" }.start()

            val deadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < deadline) {
                if (!proc.isAlive) return null to "slidev exited early — see log"
                try {
                    Socket("127.0.0.1", port).use { }
                    return SlidevServer(proc, port) to null
                } catch (_: Exception) {
                    Thread.sleep(300)
                }
            }
            proc.destroyForcibly()
            null to "slidev did not start within 60s"
        } catch (e: Exception) {
            LOG.error("slidev spawn failed", e)
            null to "failed to start slidev: ${e.message}"
        }
    }
}
