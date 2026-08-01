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

    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val debounce = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "jadx-slides-debounce").apply { isDaemon = true }
    }
    private var pending: ScheduledFuture<*>? = null

    private fun readSource(): String =
        source.readText(Charsets.UTF_8).removePrefix("﻿")

    private fun preprocess(bridgePort: Int) {
        prepared.writeText(DeckPreprocess.rewrite(readSource(), bridgePort))
    }

    /** Full pipeline for the engine; returns an error message or null.
     * Deliberately does NOT touch the bridge's serving state — a slow
     * prepare losing the open race must not overwrite the winner's deck.
     * The winner publishes via [publishTo] on the EDT under the gen check. */
    fun prepare(bridge: BridgeServer): String? {
        bridgeRef = bridge
        when (engine) {
            Engine.HTML -> {
                url = "http://127.0.0.1:${bridge.port}/"
            }
            Engine.MARP -> {
                preprocess(bridge.port)
                Engines.renderMarp(prepared.toPath(), htmlOut.toPath())?.let { return it }
                url = "http://127.0.0.1:${bridge.port}/"
            }
            Engine.SLIDEV -> {
                preprocess(bridge.port)
                val (server, err) = Engines.startSlidev(prepared.toPath())
                if (server == null) return err ?: "slidev failed"
                slidev = server
                // a close() racing this prepare saw slidev == null and
                // stopped nothing — re-check after publishing the field
                if (closed.get()) {
                    server.stop()
                    return "deck was closed during slidev startup"
                }
                url = server.url
            }
        }
        startWatcher(bridge)
        return null
    }

    /** Called only for the session that won the open race. */
    fun publishTo(bridge: BridgeServer) {
        when (engine) {
            Engine.HTML -> bridge.publishDeck(this, source, dir)
            Engine.MARP -> bridge.publishDeck(this, htmlOut, dir)
            Engine.SLIDEV -> {} // vite serves the deck itself
        }
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
        try {
            pending?.cancel(false)
            pending = debounce.schedule({ rerender(bridge) }, 250, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // a save landed inside close()'s drain window — the executor is
            // already shut down and the session is going away; not an error
        }
    }

    private fun rerender(bridge: BridgeServer) {
        if (closed.get()) return
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
            if (closed.get() && engine != Engine.HTML) {
                deleteSiblingsUnlessReused()
            }
        }
    }

    /**
     * Re-opening the same deck derives the SAME sibling paths, and the new
     * session is prepared before this one is closed — deleting by path here
     * would destroy the files the successor is serving.
     */
    private fun deleteSiblingsUnlessReused() {
        val successor = Slides.session
        if (successor != null && successor !== this && successor.prepared == prepared) return
        prepared.delete()
        htmlOut.delete()
    }

    /** May block (child-process shutdown, in-flight render) — call off the
     * EDT. Idempotent: only the first call does the work. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        pending?.cancel(false)
        debounce.shutdownNow()
        // let an already-running rerender finish (or hit its interrupt) so
        // the deletes below can't race a preprocess/marp write; the render
        // was interrupted, so a short bound is enough
        runCatching { debounce.awaitTermination(2, TimeUnit.SECONDS) }
        watchThread?.interrupt()
        runCatching { watchService?.close() }
        slidev?.let { runCatching { it.stop() } }
        slidev = null
        // stop serving the closed deck — unless a newer session already took
        // the bridge over (owner identity, checked atomically in clearDeck)
        bridgeRef?.clearDeck(this)
        if (engine != Engine.HTML) {
            deleteSiblingsUnlessReused()
        }
    }
}
