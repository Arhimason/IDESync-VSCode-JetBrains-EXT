package com.vscode.jetbrainssync

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.swing.Timer
import kotlin.concurrent.write

/**
 * Editor State Manager
 * Responsible for managing editor state caching, debouncing and deduplication logic
 */
class EditorStateManager(
    private val project: Project,
    private val fileUtils: FileUtils
) {
    private val log: Logger = Logger.getInstance(EditorStateManager::class.java)

    // Debounce timers grouped by file path
    private val debounceTimers: ConcurrentHashMap<String, Timer> = ConcurrentHashMap()

    // Read-write lock, protecting atomicity of timer operations
    private val timersLock = ReentrantReadWriteLock()

    // Debounce delay
    private val debounceDelayMs = 300

    private var stateChangeCallback: StateChangeCallback? = null

    // Callback interface
    interface StateChangeCallback {
        fun onStateChanged(state: EditorState)
    }

    fun setStateChangeCallback(callback: StateChangeCallback) {
        this.stateChangeCallback = callback
    }

    /**
     * Create editor state object
     */
    fun createEditorState(
        editor: Editor,
        file: VirtualFile,
        action: ActionType,
        isActive: Boolean = false
    ): EditorState {
        val (line, column) = fileUtils.getEditorCursorPosition(editor)

        // Get selection range coordinates
        val selectionCoordinates = fileUtils.getSelectionCoordinates(editor)

        return EditorState(
            action = action,
            filePath = fileUtils.getVirtualFilePath(file),
            line = line,
            column = column,
            source = SourceType.JETBRAINS,
            isActive = isActive,
            timestamp = formatTimestamp(),
            openedFiles = null,
            selectionStartLine = selectionCoordinates?.first,
            selectionStartColumn = selectionCoordinates?.second,
            selectionEndLine = selectionCoordinates?.third,
            selectionEndColumn = selectionCoordinates?.fourth
        )
    }

    /**
     * Create close state object
     */
    fun createCloseState(filePath: String, isActive: Boolean = false): EditorState {
        return EditorState(
            action = ActionType.CLOSE,
            filePath = filePath,
            line = 0,
            column = 0,
            source = SourceType.JETBRAINS,
            isActive = isActive,
            timestamp = formatTimestamp()
        )
    }


    /**
     * Create workspace sync state
     */
    fun createWorkspaceSyncState(isActive: Boolean = false): EditorState {
        val (editor, file) = fileUtils.getCurrentActiveEditorAndFile()
        val openedFiles = fileUtils.getAllOpenedFiles()

        return if (editor != null && file != null && fileUtils.isRegularFile(file)) {
            val (line, column) = fileUtils.getEditorCursorPosition(editor)
            EditorState(
                action = ActionType.WORKSPACE_SYNC,
                filePath = file.path,
                line = line,
                column = column,
                source = SourceType.JETBRAINS,
                isActive = isActive,
                timestamp = formatTimestamp(),
                openedFiles = openedFiles,
                selectionStartLine = null,
                selectionStartColumn = null,
                selectionEndLine = null,
                selectionEndColumn = null
            )
        } else {
            // When there is no active editor, use empty file path and position
            EditorState(
                action = ActionType.WORKSPACE_SYNC,
                filePath = "",
                line = 0,
                column = 0,
                source = SourceType.JETBRAINS,
                isActive = isActive,
                timestamp = formatTimestamp(),
                openedFiles = openedFiles,
                selectionStartLine = null,
                selectionStartColumn = null,
                selectionEndLine = null,
                selectionEndColumn = null
            )
        }
    }

    /**
     * Clear debounce timer for specified file path
     * Use write lock to ensure operation atomicity
     */
    private fun clearDebounceTimer(filePath: String) {
        timersLock.write {
            val timer = debounceTimers.remove(filePath)
            if (timer != null) {
                timer.stop()
                log.debug("Clearing file debounce timer: $filePath")
            }
        }
    }

    /**
     * Debounced update state
     */
    fun debouncedUpdateState(state: EditorState) {
        val filePath = state.filePath

        timersLock.write {
            // Clear previous debounce timer for this file
            val oldTimer = debounceTimers.remove(filePath)
            oldTimer?.stop()

            // Create new debounce timer
            val timer = Timer(debounceDelayMs) {
                try {
                    updateState(state)
                } catch (e: Exception) {
                    log.warn("Error occurred while updating state", e)
                } finally {
                    // Regardless of whether an exception occurs, clean up the timer to prevent memory leaks
                    timersLock.write {
                        debounceTimers.remove(filePath)
                    }
                }
            }
            timer.isRepeats = false

            debounceTimers[filePath] = timer
            timer.start()
        }
    }

    /**
     * Update state immediately (no debounce)
     */
    fun updateState(state: EditorState) {
        // If it's a file close operation, immediately clear debounce timer
        if (state.action == ActionType.CLOSE) {
            clearDebounceTimer(state.filePath)
        }
        // Notify state change
        stateChangeCallback?.onStateChanged(state)
    }

    /**
     * Send current state
     */
    fun sendCurrentState(isActive: Boolean) {
        val currentState = getCurrentActiveEditorState(isActive);
        if (currentState != null) {
            this.updateState(currentState)
            log.info("Sending current state: ${currentState.filePath}")
        }
    }

    /**
     * Get current active editor state
     */
    fun getCurrentActiveEditorState(isActive: Boolean): EditorState? {
        return try {
            val (editor, file) = fileUtils.getCurrentActiveEditorAndFile()

            if (editor != null && file != null && fileUtils.isRegularFile(file)) {
                val position = fileUtils.getEditorCursorPosition(editor)

                // Get selection range coordinates
                val selectionCoordinates = fileUtils.getSelectionCoordinates(editor)

                EditorState(
                    action = ActionType.NAVIGATE,
                    filePath = fileUtils.getVirtualFilePath(file),
                    line = position.first,
                    column = position.second,
                    source = SourceType.JETBRAINS,
                    isActive = isActive,
                    timestamp = formatTimestamp(),
                    openedFiles = null,
                    selectionStartLine = selectionCoordinates?.first,
                    selectionStartColumn = selectionCoordinates?.second,
                    selectionEndLine = selectionCoordinates?.third,
                    selectionEndColumn = selectionCoordinates?.fourth
                )
            } else {
                null
            }
        } catch (e: Exception) {
            log.warn("Failed to get current active editor state: ${e.message}", e)
            null
        }
    }

    /**
     * Clean up resources
     */
    fun dispose() {
        log.info("Starting to clean up editor state manager resources")

        timersLock.write {
            // Clean up all debounce timers
            for ((filePath, timer) in debounceTimers) {
                timer.stop()
                log.debug("Cleaning debounce timer: $filePath")
            }
            debounceTimers.clear()
        }

        log.info("Editor state manager resource cleanup completed")
    }
}
