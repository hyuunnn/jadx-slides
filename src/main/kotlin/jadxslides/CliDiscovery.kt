package jadxslides

import java.io.File

/**
 * Locate the marp / slidev CLIs (and node) even when jadx-gui was launched
 * outside a shell profile: PATH first, then nvm, Homebrew, npm/pnpm/yarn
 * global bins.
 */
object CliDiscovery {

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private val home = System.getProperty("user.home")

    private fun candidateDirs(): List<File> {
        val dirs = ArrayList<File>()
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dirs.add(File(it)) }

        // nvm: newest node version first
        File("$home/.nvm/versions/node").listFiles()
            ?.sortedByDescending { it.name }
            ?.forEach { dirs.add(File(it, "bin")) }

        dirs.add(File("/opt/homebrew/bin"))
        dirs.add(File("/usr/local/bin"))
        dirs.add(File("$home/Library/pnpm"))
        dirs.add(File("$home/.local/share/pnpm"))
        dirs.add(File("$home/.yarn/bin"))
        dirs.add(File("$home/.npm-global/bin"))
        if (isWindows) {
            System.getenv("APPDATA")?.let { dirs.add(File(it, "npm")) }
        }
        return dirs
    }

    private fun executable(dir: File, tool: String): File? {
        val names = if (isWindows) listOf("$tool.cmd", "$tool.exe", "$tool.bat", tool)
        else listOf(tool)
        for (name in names) {
            val f = File(dir, name)
            if (f.isFile && f.canExecute()) return f
        }
        return null
    }

    // hits are cached (tool locations don't move within a session; the scan
    // stats dozens of dirs and runs on every render); misses keep rescanning
    // so a CLI installed mid-session is still picked up
    private val found = java.util.concurrent.ConcurrentHashMap<String, File>()

    fun find(tool: String): File? {
        found[tool]?.let { return it }
        for (dir in candidateDirs()) {
            executable(dir, tool)?.let {
                found[tool] = it
                return it
            }
        }
        return null
    }

    /**
     * Command line for spawning a tool: Windows .cmd/.bat launcher shims
     * cannot be exec'd directly (CreateProcess error 193) and must go
     * through cmd.exe.
     */
    fun command(tool: File, vararg args: String): List<String> {
        val ext = tool.extension.lowercase()
        return if (isWindows && (ext == "cmd" || ext == "bat")) {
            listOf("cmd.exe", "/c", tool.absolutePath) + args
        } else {
            listOf(tool.absolutePath) + args
        }
    }

    /**
     * Child-process env with the tool's own dir (and a found node dir)
     * prepended to PATH, so npm launcher shims can locate `node`.
     */
    fun childEnv(tool: File): Map<String, String> {
        val env = HashMap(System.getenv())
        val extra = LinkedHashSet<String>()
        extra.add(tool.parentFile.absolutePath)
        find("node")?.let { extra.add(it.parentFile.absolutePath) }
        // Windows names the variable "Path" and this copy is case-sensitive:
        // match case-insensitively so we extend the real entry instead of
        // adding a second, truncated PATH that races it in the child env
        val key = env.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
        val path = env[key].orEmpty()
        env[key] = extra.joinToString(File.pathSeparator) +
                if (path.isEmpty()) "" else File.pathSeparator + path
        return env
    }
}
