package com.vscode.jetbrainssync

import java.text.SimpleDateFormat
import java.util.*


/**
 * Action type enumeration
 * Defines various operation types during editor synchronization
 * Enumeration names directly correspond to JSON transmission format, no custom serialization needed
 */
enum class ActionType {
    CLOSE,      // Close file
    OPEN,       // Open file
    NAVIGATE,   // Cursor navigation and code selection
    WORKSPACE_SYNC  // Workspace state synchronization
}

/**
 * Message source enumeration
 * Defines the sender of the message
 */
enum class SourceType {
    VSCODE,     // VSCode editor
    JETBRAINS   // JetBrains IDE
}

/**
 * WebSocket connection state enumeration
 * Defines various states of WebSocket connection
 */
enum class ConnectionState {
    DISCONNECTED,   // Disconnected
    CONNECTING,     // Connecting
    CONNECTED       // Connected
}

/**
 * Editor state data class
 * Used to synchronize editor state between VSCode and JetBrains
 */
data class EditorState(
    val action: ActionType,         // Action type enumeration (required)
    val filePath: String,           // File path
    val line: Int,                  // Line number (starts from 0)
    val column: Int,                // Column number (starts from 0)
    val source: SourceType = SourceType.JETBRAINS, // Message source enumeration
    val isActive: Boolean = false,  // Whether IDE is in active state
    val timestamp: String = formatTimestamp(), // Timestamp (yyyy-MM-dd HH:mm:ss.SSS)
    val openedFiles: List<String>? = null,  // All opened files in workspace (only used by WORKSPACE_SYNC type)
    // Selection range related fields (used by NAVIGATE type)
    val selectionStartLine: Int? = null,    // Selection start line number (starts from 0)
    val selectionStartColumn: Int? = null,  // Selection start column number (starts from 0)
    val selectionEndLine: Int? = null,      // Selection end line number (starts from 0)
    val selectionEndColumn: Int? = null     // Selection end column number (starts from 0)
) {
    // Platform compatible path cache
    @Transient
    private var _compatiblePath: String? = null

    /**
     * Get platform compatible file path
     * On first call, it cleans and transforms the original path and caches the result
     * Subsequent calls directly return the cached path
     */
    fun getCompatiblePath(): String {
        // If already cached, return directly
        _compatiblePath?.let {
            return it
        }

        // First call, perform path cleaning and conversion
        val cleaned = cleanFilePath(filePath)
        val converted = convertToIdeaFormat(cleaned)
        _compatiblePath = converted

        // Output log
        if (converted != filePath) {
            // Use system log to output path conversion information
            System.out.println("EditorState: Path converted $filePath -> $converted")
        }

        return converted
    }

    /**
     * Clean file path, remove abnormal suffixes
     * Reference cleanFilePath method in FileOperationHandler
     */
    private fun cleanFilePath(path: String): String {
        var cleaned = path

        // Remove abnormal .git suffix
        if (cleaned.endsWith(".git")) {
            cleaned = cleaned.removeSuffix(".git")
        }

        // Remove other possible abnormal suffixes
        val abnormalSuffixes = listOf(".tmp", ".bak", ".swp")
        for (suffix in abnormalSuffixes) {
            if (cleaned.endsWith(suffix)) {
                cleaned = cleaned.removeSuffix(suffix)
                break
            }
        }

        return cleaned
    }

    /**
     * Convert path to IDEA format
     * Handle cross-platform path compatibility, ensure unified path format
     */
    private fun convertToIdeaFormat(path: String): String {
        var ideaPath = path

        // Get operating system information
        val osName = System.getProperty("os.name").lowercase()
        val isWindows = osName.contains("windows")
        val isMacOS = osName.contains("mac")
        val isLinux = osName.contains("linux") || osName.contains("unix")

        if (isWindows) {
            // Windows: Replace backslashes with forward slashes, handle drive letter case
            ideaPath = ideaPath.replace('\\', '/')
            if (ideaPath.matches(Regex("^[a-z]:/.*")) || ideaPath.matches(Regex("^[a-z]:.*"))) {
                ideaPath = ideaPath[0].uppercaseChar() + ideaPath.substring(1)
            }
        } else if (isMacOS || isLinux) {
            // macOS/Linux: Ensure using forward slashes, maintain Unix path format
            ideaPath = ideaPath.replace('\\', '/')

            // Ensure path starts with / (Unix absolute path)
            if (!ideaPath.startsWith('/')) {
                ideaPath = "/$ideaPath"
            }

            // Clean up duplicate slashes
            ideaPath = ideaPath.replace(Regex("/+"), "/")
        }

        return ideaPath
    }

    /**
     * Check if there is a selection range
     * @return Returns true if there is a selection range, otherwise returns false
     */
    fun hasSelection(): Boolean {
        return selectionStartLine != null &&
                selectionEndLine != null &&
                selectionStartColumn != null &&
                selectionEndColumn != null
    }


    fun getSelectionLog(): String {
        return LogFormatter.selectionLog(selectionStartLine, selectionStartColumn, selectionEndLine, selectionEndColumn)

    }

    /**
     * Get formatted cursor position string
     * @return Formatted cursor position string
     */
    fun getCursorLog(): String {
        return LogFormatter.cursorLog(line, column)
    }

    /**
     * Get formatted cursor position string
     * @return Formatted cursor position string
     */
    fun getCursor(): String {
        return LogFormatter.cursor(line, column) ?: "none"
    }
}

/**
 * Format timestamp to standard format
 * @param timestamp Timestamp (milliseconds), defaults to current time
 * @return Formatted time string (yyyy-MM-dd HH:mm:ss.SSS)
 */
fun formatTimestamp(timestamp: Long = System.currentTimeMillis()): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
    return formatter.format(Date(timestamp))
}

/**
 * Position formatting utility class
 * Provides unified formatting methods for cursor position and selection range
 */
object LogFormatter {

    /**
     * Format cursor position
     * @param line Line number (starts from 0)
     * @param column Column number (starts from 0)
     * @return Formatted cursor position string: "line X, column Y"
     */
    fun cursor(line: Int?, column: Int?): String? {
        if (line == null || column == null) {
            return null;
        }
        return "line${line + 1},column${column + 1}"
    }

    /**
     * Format cursor position log information
     * @param line Line number (starts from 0)
     * @param column Column number (starts from 0)
     * @return Formatted cursor position log string: "Cursor position: line X, column Y"
     */
    fun cursorLog(line: Int?, column: Int?): String {
        return "Cursor position: ${cursor(line, column) ?: "none"}"
    }

    /**
     * Format selection range
     * @param startLine Start line number (starts from 0)
     * @param startColumn Start column number (starts from 0)
     * @param endLine End line number (starts from 0)
     * @param endColumn End column number (starts from 0)
     * @return Formatted selection range string: "startLine,startColumn-endLine,endColumn"
     */
    fun selection(startLine: Int?, startColumn: Int?, endLine: Int?, endColumn: Int?): String? {
        if (startLine == null || startColumn == null || endLine == null || endColumn == null) {
            return null
        }
        return "${startLine + 1},${startColumn + 1}-${endLine + 1},${endColumn + 1}"
    }


    fun selectionLog(startLine: Int?, startColumn: Int?, endLine: Int?, endColumn: Int?): String {
        return "Selection range: ${selection(startLine, startColumn, endLine, endColumn) ?: "none"}"
    }
}

/**
 * Parse timestamp string to milliseconds
 * @param timestampStr Timestamp string
 * @return Milliseconds
 */
fun parseTimestamp(timestampStr: String): Long {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
    return formatter.parse(timestampStr).time
}


// Callback interface
interface ConnectionCallback {
    fun onConnected()

    fun onDisconnected()

    fun onReconnecting()
}

/**
 * Message sender interface
 * Used to abstract message sending functionality of MulticastManager and TcpServerManager
 */
interface MessageSender {
    fun sendMessage(messageWrapper: MessageWrapper): Boolean
}

/**
 * Message wrapper data class
 * Used for unified packaging and processing of multicast messages
 */
data class MessageWrapper(
    val messageId: String,
    val senderId: String,
    val timestamp: Long,
    val payload: EditorState
) {
    companion object {
        private val gson = com.google.gson.Gson()

        /**
         * Create message wrapper
         * Note: messageId is now generated through LocalIdentifierManager.generateMessageId()
         */
        fun create(messageId: String, localIdentifier: String, payload: EditorState): MessageWrapper {
            return MessageWrapper(
                messageId = messageId,
                senderId = localIdentifier,
                timestamp = System.currentTimeMillis(),
                payload = payload
            )
        }

        /**
         * Parse MessageWrapper from JSON string
         */
        fun fromJsonString(jsonString: String): MessageWrapper? {
            return try {
                gson.fromJson(jsonString, MessageWrapper::class.java)
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Convert to JSON string
     */
    fun toJsonString(): String {
        return MessageWrapper.gson.toJson(this)
    }

    /**
     * Check if message was sent by self
     */
    fun isOwnMessage(localIdentifier: String): Boolean {
        return senderId == localIdentifier
    }
}