package jadxslides

import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.EnumProgress
import me.friwi.jcefmaven.IProgressHandler
import org.cef.CefApp
import java.io.File

/**
 * Owns the process-wide CefApp. It is built once and never disposed: CEF
 * cannot be re-initialized inside one JVM, and jadx re-instantiates plugins
 * on every project open. Natives are downloaded once into the user cache.
 */
object CefHolder {
    @Volatile
    private var app: CefApp? = null
    private val lock = Any()

    val installDir = File(System.getProperty("user.home"), ".cache/jadx-slides/jcef")

    /**
     * JCEF on macOS needs `--add-opens java.desktop/sun.awt|sun.lwawt|
     * sun.lwawt.macosx=ALL-UNNAMED`. jadx's launcher doesn't pass them, so
     * check before initializing — a failed CEF init cannot be retried.
     */
    fun macOpensMissing(): List<String> {
        val os = System.getProperty("os.name").lowercase()
        if (!os.contains("mac")) return emptyList()
        val desktop = ModuleLayer.boot().findModule("java.desktop").orElse(null)
            ?: return emptyList()
        val me = CefHolder::class.java.module
        return listOf("sun.awt", "sun.lwawt", "sun.lwawt.macosx")
            .filter { !desktop.isOpen(it, me) }
    }

    /** Blocking (first call downloads ~100MB of natives); call off the EDT. */
    fun getOrBuild(onProgress: (String) -> Unit): CefApp {
        app?.let { return it }
        synchronized(lock) {
            app?.let { return it }
            val builder = CefAppBuilder()
            builder.setInstallDir(installDir)
            builder.cefSettings.windowless_rendering_enabled = false
            builder.setProgressHandler(object : IProgressHandler {
                override fun handleProgress(state: EnumProgress, percent: Float) {
                    val pct = if (percent >= 0) " ${percent.toInt()}%" else ""
                    onProgress("JCEF: ${state.name.lowercase()}$pct")
                }
            })
            val built = builder.build()
            app = built
            return built
        }
    }
}
