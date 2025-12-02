package com.vscode.jetbrainssync

import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * IDE Path Detector
 * Automatically detects installation paths for VSCode/Cursor/Windsurf
 */
class IDEPathDetector {
    private val log: Logger = Logger.getInstance(IDEPathDetector::class.java)
    private val osName: String = System.getProperty("os.name").lowercase()
    private val isWindows: Boolean = osName.contains("windows")
    private val isMacOS: Boolean = osName.contains("mac")
    private val isLinux: Boolean = osName.contains("linux") || osName.contains("unix")
    private val userHome: String = System.getProperty("user.home")

    /**
     * Detect VSCode path
     * Try different IDEs by priority (VSCode, Cursor, Windsurf)
     */
    fun detectVSCodePath(): String? {
        log.info("Starting to detect VSCode/Cursor/Windsurf paths...")

        val detectedPath = findFirstAvailableIDE()

        if (detectedPath != null) {
            log.info("Detected IDE: $detectedPath")
        } else {
            log.warn("No VSCode/Cursor/Windsurf detected")
        }

        return detectedPath
    }

    /**
     * Find first available IDE
     */
    private fun findFirstAvailableIDE(): String? {
        val candidates = getIDECandidates()

        for (candidate in candidates) {
            val expandedPaths = expandPath(candidate)
            for (path in expandedPaths) {
                if (isExecutable(path)) {
                    return path
                }
            }
        }

        return null
    }

    /**
     * Get IDE candidate path list
     */
    private fun getIDECandidates(): List<String> {
        return when {
            isMacOS -> getMacOSCandidates()
            isWindows -> getWindowsCandidates()
            isLinux -> getLinuxCandidates()
            else -> emptyList()
        }
    }

    /**
     * macOS IDE candidate paths
     */
    private fun getMacOSCandidates(): List<String> {
        return listOf(
            // Standard installation path - Visual Studio Code
            "/Applications/Visual Studio Code.app/Contents/Resources/app/bin/code",
            "/Applications/VSCode.app/Contents/Resources/app/bin/code",
            
            // Standard installation path - Cursor
            "/Applications/Cursor.app/Contents/Resources/app/bin/cursor",
            
            // Standard installation path - Windsurf
            "/Applications/Windsurf.app/Contents/Resources/app/bin/windsurf",
            
            // User installation paths
            "$userHome/Applications/Visual Studio Code.app/Contents/Resources/app/bin/code",
            "$userHome/Applications/Cursor.app/Contents/Resources/app/bin/cursor",
            "$userHome/Applications/Windsurf.app/Contents/Resources/app/bin/windsurf",
            
            // CLI command paths
            "/usr/local/bin/code",
            "/usr/local/bin/cursor",
            "/usr/local/bin/windsurf",
            
            // Homebrew paths
            "/opt/homebrew/bin/code",
            "/opt/homebrew/bin/cursor",
            "/opt/homebrew/bin/windsurf"
        )
    }

    /**
     * Windows IDE candidate paths
     */
    private fun getWindowsCandidates(): List<String> {
        val localAppData = System.getenv("LOCALAPPDATA") ?: "$userHome\\AppData\\Local"
        val programFiles = System.getenv("ProgramFiles") ?: "C:\\Program Files"
        val programFilesX86 = System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"

        return listOf(
            // Standard installation path - Visual Studio Code
            "$localAppData\\Programs\\Microsoft VS Code\\bin\\code.cmd",
            "$localAppData\\Programs\\Microsoft VS Code\\Code.exe",
            "$programFiles\\Microsoft VS Code\\bin\\code.cmd",
            "$programFiles\\Microsoft VS Code\\Code.exe",
            
            // Standard installation path - Cursor
            "$localAppData\\Programs\\cursor\\Cursor.exe",
            "$localAppData\\cursor\\Cursor.exe",
            
            // Standard installation path - Windsurf
            "$localAppData\\Programs\\Windsurf\\Windsurf.exe",
            "$localAppData\\Windsurf\\Windsurf.exe",
            
            // System PATH command paths
            "code.cmd",
            "cursor.cmd",
            "windsurf.cmd"
        )
    }

    /**
     * Linux IDE candidate paths
     */
    private fun getLinuxCandidates(): List<String> {
        return listOf(
            // System binary paths
            "/usr/bin/code",
            "/usr/bin/cursor",
            "/usr/bin/windsurf",
            
            // Local binary paths
            "/usr/local/bin/code",
            "/usr/local/bin/cursor",
            "/usr/local/bin/windsurf",
            
            // Snap installations
            "/snap/code/current/bin/code",
            "/snap/bin/code",
            
            // Flatpak installations
            "/var/lib/flatpak/exports/bin/com.visualstudio.code",
            
            // User local installations
            "$userHome/.local/bin/code",
            "$userHome/.local/bin/cursor",
            "$userHome/.local/bin/windsurf",
            
            // Manual installation paths
            "/opt/VSCode-linux-x64/bin/code",
            "/opt/cursor/cursor",
            "/opt/windsurf/windsurf"
        )
    }

    /**
     * Expand paths containing wildcards
     */
    private fun expandPath(pattern: String): List<String> {
        if (!pattern.contains("*")) {
            return listOf(pattern)
        }

        val results = mutableListOf<String>()
        val parts = pattern.split(File.separator)
        var currentPaths = mutableListOf("")

        for (part in parts) {
            if (part.isEmpty()) {
                currentPaths = mutableListOf(File.separator)
                continue
            }

            val newPaths = mutableListOf<String>()

            for (currentPath in currentPaths) {
                if (part.contains("*")) {
                    // Expand wildcard
                    val dir = if (currentPath.isEmpty()) "." else currentPath
                    val directory = File(dir)
                    
                    if (directory.exists() && directory.isDirectory) {
                        val regex = Regex("^" + part.replace("*", ".*") + "$")
                        val entries = directory.listFiles() ?: emptyArray()
                        
                        for (entry in entries) {
                            if (regex.matches(entry.name)) {
                                newPaths.add(File(currentPath, entry.name).path)
                            }
                        }
                    }
                } else {
                    newPaths.add(File(currentPath, part).path)
                }
            }

            currentPaths = newPaths
        }

        return currentPaths
    }

    /**
     * Check if file is executable
     */
    private fun isExecutable(path: String): Boolean {
        val file = File(path)
        return file.exists() && file.canExecute()
    }

    /**
     * Detect JetBrains IDE path (for VSCode use)
     */
    fun detectJetBrainsPath(): String? {
        val candidates = getJetBrainsCandidates()

        for (candidate in candidates) {
            val expandedPaths = expandPath(candidate)
            for (path in expandedPaths) {
                if (isExecutable(path)) {
                    return path
                }
            }
        }

        return null
    }

    /**
     * JetBrains IDE candidate paths
     */
    private fun getJetBrainsCandidates(): List<String> {
        return when {
            isMacOS -> listOf(
                "$userHome/Library/Application Support/JetBrains/Toolbox/scripts/idea",
                "$userHome/Library/Application Support/JetBrains/Toolbox/scripts/webstorm",
                "$userHome/Library/Application Support/JetBrains/Toolbox/scripts/pycharm",
                "/Applications/IntelliJ IDEA.app/Contents/MacOS/idea",
                "/Applications/WebStorm.app/Contents/MacOS/webstorm",
                "/Applications/PyCharm.app/Contents/MacOS/pycharm"
            )
            isWindows -> {
                val localAppData = System.getenv("LOCALAPPDATA") ?: "$userHome\\AppData\\Local"
                listOf(
                    "$localAppData\\JetBrains\\Toolbox\\scripts\\idea.cmd",
                    "$localAppData\\JetBrains\\Toolbox\\scripts\\webstorm.cmd",
                    "$localAppData\\JetBrains\\Toolbox\\scripts\\pycharm.cmd"
                )
            }
            isLinux -> listOf(
                "$userHome/.local/share/JetBrains/Toolbox/scripts/idea",
                "$userHome/.local/share/JetBrains/Toolbox/scripts/webstorm",
                "$userHome/.local/share/JetBrains/Toolbox/scripts/pycharm",
                "/snap/intellij-idea-ultimate/current/bin/idea.sh",
                "/snap/intellij-idea-community/current/bin/idea.sh"
            )
            else -> emptyList()
        }
    }

    /**
     * Data class representing a detected IDE
     */
    data class DetectedIDE(
        val name: String,
        val path: String
    )

    /**
     * Detect all available VSCode/Cursor/Windsurf IDEs
     * Returns a list of detected IDEs with names and paths
     */
    fun detectAllVSCodePaths(): List<DetectedIDE> {
        log.info("Detecting all VSCode/Cursor/Windsurf IDEs...")
        val results = mutableListOf<DetectedIDE>()
        val candidates = getIDECandidates()

        for (candidate in candidates) {
            val expandedPaths = expandPath(candidate)
            for (path in expandedPaths) {
                if (isExecutable(path)) {
                    val name = getIDENameFromPath(path)
                    // Avoid duplicates by name
                    if (results.none { it.name == name }) {
                        results.add(DetectedIDE(name, path))
                        log.info("Detected IDE: $name at $path")
                    }
                }
            }
        }

        return results
    }

    /**
     * Get IDE name from path
     */
    private fun getIDENameFromPath(path: String): String {
        val lowerPath = path.lowercase()
        return when {
            lowerPath.contains("windsurf") -> "Windsurf"
            lowerPath.contains("cursor") -> "Cursor"
            lowerPath.contains("visual studio code") || lowerPath.contains("vs code") -> "Visual Studio Code"
            lowerPath.endsWith("/code") || lowerPath.endsWith("\\code") || 
            lowerPath.endsWith("/code.cmd") || lowerPath.endsWith("\\code.cmd") ||
            lowerPath.endsWith("code.exe") -> "Visual Studio Code"
            else -> File(path).nameWithoutExtension.replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * Resolve a command name or path to an executable path
     * Supports:
     * - Full paths (e.g., "/usr/bin/windsurf")
     * - Command names (e.g., "windsurf", "cursor", "code")
     */
    fun resolveIDEPath(pathOrCommand: String): String? {
        if (pathOrCommand.isBlank()) {
            return null
        }

        // First, check if it's already a valid executable path
        if (File(pathOrCommand).let { it.exists() && it.canExecute() }) {
            log.info("Path is already executable: $pathOrCommand")
            return pathOrCommand
        }

        // Check if it looks like a command name (no path separators)
        if (!pathOrCommand.contains(File.separator) && !pathOrCommand.contains("/") && !pathOrCommand.contains("\\")) {
            log.info("Attempting to resolve command name: $pathOrCommand")
            
            // Try to find in PATH using which/where
            val resolvedPath = findCommandInPath(pathOrCommand)
            if (resolvedPath != null) {
                log.info("Resolved command '$pathOrCommand' to: $resolvedPath")
                return resolvedPath
            }

            // Try to find in known IDE paths
            val knownPath = findInKnownPaths(pathOrCommand)
            if (knownPath != null) {
                log.info("Found '$pathOrCommand' in known paths: $knownPath")
                return knownPath
            }
        }

        log.warn("Could not resolve IDE path: $pathOrCommand")
        return null
    }

    /**
     * Find command in system PATH using which (Unix) or where (Windows)
     */
    private fun findCommandInPath(command: String): String? {
        return try {
            val cmd = if (isWindows) arrayOf("cmd", "/c", "where", command) else arrayOf("which", command)
            val process = ProcessBuilder(*cmd)
                .redirectErrorStream(true)
                .start()
            
            val result = process.inputStream.bufferedReader().readLine()
            val exitCode = process.waitFor()
            
            if (exitCode == 0 && !result.isNullOrBlank() && File(result).exists()) {
                result
            } else {
                null
            }
        } catch (e: Exception) {
            log.debug("Failed to execute which/where for '$command': ${e.message}")
            null
        }
    }

    /**
     * Find command in known IDE installation paths
     */
    private fun findInKnownPaths(command: String): String? {
        val lowerCommand = command.lowercase()
        val candidates = getIDECandidates()

        for (candidate in candidates) {
            val expandedPaths = expandPath(candidate)
            for (path in expandedPaths) {
                val lowerPath = path.lowercase()
                // Match by command name in the path
                if (lowerPath.contains(lowerCommand) && isExecutable(path)) {
                    return path
                }
            }
        }

        return null
    }
}
