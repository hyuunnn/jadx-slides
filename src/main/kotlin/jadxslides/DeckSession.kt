package jadxslides

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * One open deck: preprocessed sibling files, the render pipeline for its
 * engine, and a directory watcher that re-renders on every save.
 *
 * Hidden siblings (`.<name>.jadx-slides.md` / `.html`) sit next to the deck
 * so relative image paths keep working; they are deleted on close.
 */
class DeckSession(val source: File, val engine: Engine) {
    private val log = LoggerFactory.getLogger(DeckSession::class.java)

    private val dir = source.absoluteFile.parentFile
    private val base = source.name.substringBeforeLast('.')
    val prepared = File(dir, ".$base.jadx-slides.md")
    val htmlOut = File(dir, ".$base.jadx-slides.html")

    @Volatile var url: String = ""
        private set

    private var slidev: Engines.SlidevServer? = null
    private var watchService: WatchService? = null
    private var watchThread: Thread? = null
    private var bridgeRef: BridgeServer? = null

    @Volatile private var closed = false
    private val debounce = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "jadx-slides-debounce").apply { isDaemon = true }
    }
    private var pending: ScheduledFuture<*>? = null

    private fun readSource(): String =
        source.readText(Charsets.UTF_8).removePrefix("﻿")

    private fun preprocess(bridgePort: Int) {
        prepared.writeText(DeckPreprocess.rewrite(readSource(), bridgePort))
    }

    /** Full pipeline for the engine; returns an error message or null. */
    fun prepare(bridge: BridgeServer): String? {
        bridgeRef = bridge
        when (engine) {
            Engine.HTML -> {
                bridge.deckHtml = source
                bridge.deckDir = dir
                url = "http://127.0.0.1:${bridge.port}/"
            }
            Engine.MARP -> {
                preprocess(bridge.port)
                Engines.renderMarp(prepared.toPath(), htmlOut.toPath())?.let { return it }
                bridge.deckHtml = htmlOut
                bridge.deckDir = dir
                url = "http://127.0.0.1:${bridge.port}/"
            }
            Engine.SLIDEV -> {
                preprocess(bridge.port)
                val (server, err) = Engines.startSlidev(prepared.toPath())
                if (server == null) return err ?: "slidev failed"
                slidev = server
                url = server.url
            }
        }
        startWatcher(bridge)
        return null
    }

    private fun startWatcher(bridge: BridgeServer) {
        try {
            val ws = FileSystems.getDefault().newWatchService()
            dir.toPath().register(
                ws,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
            )
            watchService = ws
            val t = Thread({
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        val key = ws.take()
                        for (ev in key.pollEvents()) {
                            val changed = (ev.context() as? Path)?.fileName?.toString()
                            // only the source deck; our own prepared/html writes
                            // land in the same dir and must not re-trigger
                            if (changed == source.name) scheduleRerender(bridge)
                        }
                        if (!key.reset()) break
                    }
                } catch (_: InterruptedException) {
                } catch (_: java.nio.file.ClosedWatchServiceException) {
                }
            }, "jadx-slides-watch")
            t.isDaemon = true
            t.start()
            watchThread = t
        } catch (e: Exception) {
            log.warn("file watcher failed to start — live reload disabled", e)
        }
    }

    private fun scheduleRerender(bridge: BridgeServer) {
        pending?.cancel(false)
        pending = debounce.schedule({ rerender(bridge) }, 250, TimeUnit.MILLISECONDS)
    }

    private fun rerender(bridge: BridgeServer) {
        if (closed) return
        try {
            when (engine) {
                Engine.HTML -> bridge.bumpVersion()
                Engine.MARP -> {
                    preprocess(bridge.port)
                    val err = Engines.renderMarp(prepared.toPath(), htmlOut.toPath())
                    if (err == null) bridge.bumpVersion() else log.warn("re-render failed: {}", err)
                }
                Engine.SLIDEV -> preprocess(bridge.port) // vite HMR picks it up
            }
        } catch (e: Exception) {
            log.warn("re-render failed", e)
        } finally {
            // a close that raced this rerender already ran its deletes —
            // don't leave freshly rewritten siblings behind
            if (closed && engine != Engine.HTML) {
                prepared.delete()
                htmlOut.delete()
            }
        }
    }

    /** May block (child-process shutdown, in-flight render) — call off the EDT. */
    fun close() {
        closed = true
        pending?.cancel(false)
        debounce.shutdownNow()
        // let an already-running rerender finish (or hit its interrupt) so
        // the deletes below can't race a preprocess/marp write
        runCatching { debounce.awaitTermination(10, TimeUnit.SECONDS) }
        watchThread?.interrupt()
        runCatching { watchService?.close() }
        slidev?.let { runCatching { it.stop() } }
        slidev = null
        bridgeRef?.let {
            // stop serving the closed deck's directory; leave the bridge
            // alone if another session already took it over
            if (it.deckHtml == source || it.deckHtml == htmlOut) {
                it.deckHtml = null
                it.deckDir = null
            }
        }
        if (engine != Engine.HTML) {
            prepared.delete()
            htmlOut.delete()
        }
    }
}
