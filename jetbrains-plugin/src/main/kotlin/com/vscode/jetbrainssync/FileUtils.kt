package com.vscode.jetbrainssync

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFocusManager
import java.io.File

/**
 * File utility class
 * Provides file operation related utility methods
 */
class FileUtils(private val project: Project, private val log: Logger) {

    /**
     * Check if file is still open in other editors
     */
    fun isFileOpenInOtherTabs(file: VirtualFile): Boolean {
        val fileEditorManager = FileEditorManager.getInstance(project)
        return fileEditorManager.isFileOpen(file)
    }

    /**
     * Determine if it's a regular file editor (only allow regular file systems)
     */
    fun isRegularFile(virtualFile: VirtualFile): Boolean {
        val fileSystem = virtualFile.fileSystem.protocol

        // Whitelist mechanism: only allow regular file system protocols
        val allowedFileSystems = listOf(
            "file"       // Local file system
        )

        return allowedFileSystems.contains(fileSystem)
    }

    /**
     * Get all currently opened file paths
     * Only return regular file editors, filter out special tab windows
     */
    fun getAllOpenedFiles(): List<String> {
        val fileEditorManager = FileEditorManager.getInstance(project)
        return fileEditorManager.openFiles
            .filter { virtualFile ->
                // Only keep regular file editors, filter out all special tab windows
                isRegularFile(virtualFile)
            }
            .map { it.path }
    }


    /**
     * Extract filename from file path
     * @param filePath File path
     * @return Filename
     */
    fun extractFileName(filePath: String): String {
        return File(filePath).name
    }

    /**
     * Get file path of editor
     * @param virtualFile Virtual file
     * @return File path
     */
    fun getVirtualFilePath(virtualFile: VirtualFile): String {
        return virtualFile.path
    }

    /**
     * Get cursor position of editor
     * @param editor Text editor
     * @return Cursor position Pair<line number, column number>
     */
    fun getEditorCursorPosition(editor: Editor): Pair<Int, Int> {
        val position = editor.caretModel.logicalPosition
        return Pair(position.line, position.column)
    }

    /**
     * Get selection range coordinates of editor
     * @param editor Text editor
     * @return Selection range coordinates (startLine, startColumn, endLine, endColumn), return null if no selection
     */
    fun getSelectionCoordinates(editor: Editor): Quadruple<Int, Int, Int, Int>? {
        val selectionModel = editor.selectionModel
        val hasSelection = selectionModel.hasSelection()

        return if (hasSelection) {
            val startPosition = editor.offsetToLogicalPosition(selectionModel.selectionStart)
            val endPosition = editor.offsetToLogicalPosition(selectionModel.selectionEnd)
            Quadruple(startPosition.line, startPosition.column, endPosition.line, endPosition.column)
        } else {
            null
        }
    }

    /**
     * Quadruple data class, used to return four coordinates of selection range
     */
    data class Quadruple<out A, out B, out C, out D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )


    /**
     * Get currently selected file and editor
     * @return Pair<TextEditor?, VirtualFile?> combination of editor and virtual file
     */
    fun getCurrentActiveEditorAndFile(): Pair<Editor?, VirtualFile?> {
        val fileEditorManager = FileEditorManager.getInstance(project)
        val selectedEditor = fileEditorManager.selectedTextEditor
        val selectedFile = fileEditorManager.selectedFiles.firstOrNull()
        if (selectedFile != null && isRegularFile(selectedFile)) {
            return Pair(selectedEditor, selectedFile)
        }
        return Pair(null, null);
    }

    /**
     * Determine if current editor has focus
     * @return Boolean whether current editor has focus
     */
    fun isEditorFocused(): Boolean {
        val focusManager = IdeFocusManager.getInstance(project)
        val focusOwner = focusManager.focusOwner

        // Get current active editor
        val (currentEditor, _) = getCurrentActiveEditorAndFile()

        // If there is no active editor, it definitely doesn't have focus
        if (currentEditor == null) {
            return false
        }

        // Check if current focus component belongs to editor
        return focusOwner != null && currentEditor.contentComponent.isAncestorOf(focusOwner)
    }

    /**
     * Close file by file path
     * If direct path matching fails, will try matching by filename
     */
    fun closeFileByPath(filePath: String) {
        try {
            log.info("Preparing to close file: $filePath")
            val fileEditorManager = FileEditorManager.getInstance(project)
            // Try matching by file path
            val virtualFile = findFileByPath(filePath)
            virtualFile?.let { vFile ->
                if (fileEditorManager.isFileOpen(vFile)) {
                    fileEditorManager.closeFile(vFile)
                    log.info("✅ Successfully closed file: $filePath")
                    return
                } else {
                    log.warn("⚠️ File not open, no need to close: $filePath")
                    return
                }
            }
            log.warn("❌ File not found: $filePath")
        } catch (e: Exception) {
            log.warn("Failed to close file: $filePath - ${e.message}", e)
        }
    }

    /**
     * Open file by file path
     * Supports opening newly created files from other IDEs, solving file not found issues by refreshing VFS cache
     * @param filePath File path
     * @param focusEditor Whether to get focus, default is true
     * @return Returns opened TextEditor, returns null if failed
     */
    fun openFileByPath(filePath: String, focusEditor: Boolean = true): TextEditor? {
        try {
            log.info("Preparing to open file: $filePath")
            val fileEditorManager = FileEditorManager.getInstance(project)
            // Try finding virtual file by file path
            val virtualFile = findFileByPath(filePath)
            virtualFile?.let { vFile ->
                // FileEditorManager.openFile() will automatically reuse opened files, no need for manual check
                val editors = fileEditorManager.openFile(vFile, focusEditor)
                val editor = editors.firstOrNull() as? TextEditor

                if (editor != null) {
                    log.info("✅ Successfully opened file: $filePath")
                    return editor
                } else {
                    log.warn("❌ Unable to get file editor: $filePath")
                    return null
                }
            }
            log.warn("❌ File not found: $filePath")
            return null
        } catch (e: Exception) {
            log.warn("Failed to open file: $filePath - ${e.message}", e)
            return null
        }
    }

    /**
     * Find virtual file, refresh VFS cache and retry if not found
     * This method specifically handles synchronization issues of newly created files in other IDEs
     * @param filePath File path
     * @return Virtual file object, returns null if not found
     */
    fun findFileByPath(filePath: String): VirtualFile? {
        val file = File(filePath)
        val fileSystem = LocalFileSystem.getInstance()
        // First attempt: direct search
        var virtualFile = fileSystem.findFileByIoFile(file)
        if (virtualFile != null) {
            log.info("Found file directly: ${file.path}")
            return virtualFile
        }

        log.info("File not found, starting VFS cache refresh: ${file.path}")

        // Second attempt: search after refreshing parent directory
        val parentFile = file.parentFile
        if (parentFile != null && parentFile.exists()) {
            val parentVirtualFile = fileSystem.findFileByIoFile(parentFile)
            parentVirtualFile?.refresh(false, true)
            log.info("Refreshed parent directory VFS cache: ${parentFile.path}")

            virtualFile = fileSystem.findFileByIoFile(file)
            if (virtualFile != null) {
                log.info("Found file after refreshing parent directory: ${file.path}")
                return virtualFile
            }
        }

        // Third attempt: search after forcing refresh of entire file system
        log.info("Refreshing parent directory ineffective, performing global VFS refresh")
        fileSystem.refresh(false)

        // Give file system some time to update index
        try {
            Thread.sleep(100)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        virtualFile = fileSystem.findFileByIoFile(file)
        if (virtualFile != null) {
            log.info("Found file after global refresh: ${file.path}")
            return virtualFile
        }

        log.warn("All refresh attempts failed, file may not exist: ${file.path}")
        return null
    }


    /**
     * Unified handling of selection and cursor movement
     * First handle selection state (set selection if there is one, clear selection if none), then ensure cursor position is within visible area
     * @param textEditor Text editor
     * @param line Cursor line number
     * @param column Cursor column number
     * @param startLine Selection start line number (optional)
     * @param startColumn Selection start column number (optional)
     * @param endLine Selection end line number (optional)
     * @param endColumn Selection end column number (optional)
     */
    fun handleSelectionAndNavigate(
        textEditor: TextEditor,
        line: Int,
        column: Int,
        startLine: Int? = null,
        startColumn: Int? = null,
        endLine: Int? = null,
        endColumn: Int? = null
    ) {
        try {
            log.info("Preparing to handle selection and cursor navigation: ${LogFormatter.cursorLog(line, column)}, ${LogFormatter.selectionLog(startLine, startColumn, endLine, endColumn)}")

            ApplicationManager.getApplication().runWriteAction {
                val selectionModel = textEditor.editor.selectionModel

                // First handle selection state
                if (startLine != null && startColumn != null && endLine != null && endColumn != null) {
                    // Has selection range, set selection
                    val startPosition = LogicalPosition(startLine, startColumn)
                    val endPosition = LogicalPosition(endLine, endColumn)

                    selectionModel.setSelection(
                        textEditor.editor.logicalPositionToOffset(startPosition),
                        textEditor.editor.logicalPositionToOffset(endPosition)
                    )
                    log.info("✅ Successfully set selection range: ${LogFormatter.selection(startLine, startColumn, endLine, endColumn)}")
                } else {
                    // No selection range, clear selection
                    selectionModel.removeSelection()
                    log.info("✅ Successfully cleared selection state, ${LogFormatter.cursorLog(line, column)}")
                }

                // Then move cursor to specified position
                val cursorPosition = LogicalPosition(line, column)
                textEditor.editor.caretModel.moveToLogicalPosition(cursorPosition)
                log.info("✅ Successfully moved cursor to position: ${LogFormatter.cursor(line, column)}")

                // Ensure cursor position is within visible area
                val visibleArea = textEditor.editor.scrollingModel.visibleArea
                val targetPoint = textEditor.editor.logicalPositionToXY(cursorPosition)

                if (!visibleArea.contains(targetPoint)) {
                    textEditor.editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
                    log.info("✅ Cursor position not visible, scrolled to: ${LogFormatter.cursor(line, column)}")
                } else {
                    log.info("Cursor position already within visible area, no scrolling needed")
                }
            }
            log.info("✅ Selection and cursor navigation processing completed")
        } catch (e: Exception) {
            log.warn("❌ Failed to handle selection and cursor navigation: ${LogFormatter.cursorLog(line, column)} - ${e.message}", e)
        }
    }
}