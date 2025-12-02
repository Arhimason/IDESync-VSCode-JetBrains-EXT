package com.vscode.jetbrainssync

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger

/**
 * File operation handler
 * Responsible for handling file open, close and navigation operations
 */
class FileOperationHandler(
    private val editorStateManager: EditorStateManager,
    private val windowStateManager: WindowStateManager,
    private val fileUtils: FileUtils
) {
    private val log: Logger = Logger.getInstance(FileOperationHandler::class.java)

    /**
     * Handle received editor state
     */
    fun handleIncomingState(state: EditorState) {
        ApplicationManager.getApplication().invokeLater {
            try {
                when (state.action) {
                    ActionType.CLOSE -> handleFileClose(state)
                    ActionType.WORKSPACE_SYNC -> handleWorkspaceSync(state)
                    ActionType.OPEN -> handleFileOpenOrNavigate(state, false)
                    else -> handleFileOpenOrNavigate(state, false)
                }
            } catch (e: Exception) {
                log.warn("Failed to handle message operation: ${e.message}", e)
            }
        }
    }

    /**
     * Handle file close operation
     */
    private fun handleFileClose(state: EditorState) {
        log.info("Performing file close operation: ${state.filePath}")
        val compatiblePath = state.getCompatiblePath()
        fileUtils.closeFileByPath(compatiblePath)
    }

    /**
     * Handle workspace sync operation
     */
    private fun handleWorkspaceSync(state: EditorState) {
        log.info("Performing workspace sync operation: target file count: ${state.openedFiles?.size ?: 0}")

        if (state.openedFiles.isNullOrEmpty()) {
            log.info("No opened files in workspace sync message, skipping processing")
            return
        }

        try {
            // Get current editor active state
            var currentActiveState = isCurrentWindowActive();
            log.info("Current editor active state: $currentActiveState");
            // If current editor is active, save current editor state
            val savedActiveEditorState: EditorState? = editorStateManager.getCurrentActiveEditorState(windowStateManager.isWindowActive(forceRealTime = true))
            if (savedActiveEditorState != null) {
                log.info("Saved current active editor state: ${savedActiveEditorState.filePath}, ${savedActiveEditorState.getCursorLog()}, ${savedActiveEditorState.getSelectionLog()}")
            } else {
                log.info("No active editor currently")
            }

            // Get all currently opened files
            val currentOpenedFiles = fileUtils.getAllOpenedFiles()
            val targetFiles = state.openedFiles.map { filePath ->
                // Create temporary EditorState to use path conversion logic
                val tempState = EditorState(ActionType.OPEN, filePath, 0, 0)
                tempState.getCompatiblePath()
            }

            log.info("Currently opened files: ${currentOpenedFiles.size}")
            log.info("Target files: ${targetFiles.size}")
            log.info("List of currently opened regular files: ${currentOpenedFiles.joinToString(", ") { fileUtils.extractFileName(it) }}")

            // Close excess files (files currently opened but not in target)
            val filesToClose = currentOpenedFiles.filter { file -> !targetFiles.contains(file) }
            for (fileToClose in filesToClose) {
                fileUtils.closeFileByPath(fileToClose)
            }

            // Open missing files (files in target but not currently opened)
            val filesToOpen = targetFiles.filter { file -> !currentOpenedFiles.contains(file) }
            for (fileToOpen in filesToOpen) {
                fileUtils.openFileByPath(fileToOpen, false)
            }

            // Get current editor active state again (to prevent delayed state changes)
            currentActiveState = isCurrentWindowActive();
            if (currentActiveState) {
                if (savedActiveEditorState != null && !filesToOpen.isEmpty()) {
                    restoreLocalState(savedActiveEditorState, false)
                } else {
                    log.info("No active editor state, not restoring")
                }
            } else {
                followRemoteState(state)
            }

            log.info("✅ Workspace sync completed")
        } catch (e: Exception) {
            log.warn("Workspace sync failed: ${e.message}", e)
        }
    }

    /**
     * Restore local editor state
     */
    private fun restoreLocalState(state: EditorState, focusEditor: Boolean = true) {
        log.info("Restoring local state: ${state.filePath}, focused=${focusEditor}, ${state.getCursorLog()}, ${state.getSelectionLog()}")
        handleFileOpenOrNavigate(state, focusEditor)
    }

    /**
     * Follow remote editor state
     */
    private fun followRemoteState(state: EditorState) {
        log.info("Following remote state: ${state.filePath}, ${state.getCursorLog()}, ${state.getSelectionLog()}")
        handleFileOpenOrNavigate(state, false)
    }

    /**
     * Handle file open and navigation operations
     */
    private fun handleFileOpenOrNavigate(state: EditorState, focusEditor: Boolean = true) {
        if (state.hasSelection()) {
            log.info("Performing file selection and navigation operation: ${state.filePath}, navigating to: ${state.getCursor()}, ${state.getSelectionLog()}")
        } else {
            log.info("Performing file navigation operation: ${state.filePath}, navigating to: ${state.getCursorLog()}")
        }

        val editor = fileUtils.openFileByPath(state.getCompatiblePath(), focusEditor)
        editor?.let { textEditor ->
            // Use unified selection and cursor handling logic
            fileUtils.handleSelectionAndNavigate(
                textEditor,
                state.line,
                state.column,
                state.selectionStartLine,
                state.selectionStartColumn,
                state.selectionEndLine,
                state.selectionEndColumn
            )
        } ?: run {
            log.warn("Unable to open file for navigation: ${state.getCompatiblePath()}")
        }
    }


    /**
     * Check if current editor is in active state
     */
    private fun isCurrentWindowActive(): Boolean {
        // For critical editor state checks, use forced real-time queries to ensure accuracy
        return windowStateManager.isWindowActive(forceRealTime = true)
    }


}
