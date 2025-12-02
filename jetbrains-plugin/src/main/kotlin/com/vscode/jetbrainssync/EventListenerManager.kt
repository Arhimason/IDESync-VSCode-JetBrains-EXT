package com.vscode.jetbrainssync

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.MessageBusConnection

/**
 * Event listener manager
 * Unified management of various editor event listeners, converting events to standard operation tasks
 */
class EventListenerManager(
    private val project: Project,
    private val editorStateManager: EditorStateManager,
    private val windowStateManager: WindowStateManager,
    private val fileUtils: FileUtils
) {
    private val log: Logger = Logger.getInstance(EventListenerManager::class.java)

    // Global unique cursor listener reference
    private var currentCaretListener: com.intellij.openapi.editor.event.CaretListener? = null

    // Global unique selection listener reference
    private var currentSelectionListener: com.intellij.openapi.editor.event.SelectionListener? = null
    private var currentEditor: Editor? = null
    private var messageBusConnection: MessageBusConnection? = null


    /**
     * Setup editor listeners
     */
    fun setupEditorListeners() {
        log.info("Setting up editor listeners")
        messageBusConnection = project.messageBus.connect();
        messageBusConnection?.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    if (!fileUtils.isRegularFile(file)) {
                        log.info("Event - File opened: ${file.path} - Irregular file, ignored")
                        return
                    }
                    log.info("Event - File opened: ${file.path}")
                    val fileEditor = source.getSelectedEditor(file)
                    val editor = if (fileEditor is com.intellij.openapi.fileEditor.TextEditor) {
                        fileEditor.editor
                    } else {
                        null
                    }
                    editor?.let {
                        val state = editorStateManager.createEditorState(
                            it, file, ActionType.OPEN, windowStateManager.isWindowActive()
                        )
                        log.info("Preparing to send open message: $state")
                        editorStateManager.updateState(state)
                        setupCaretListener(it)
                        setupSelectionListener(it)
                        currentEditor = it
                    }
                }

                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    if (!fileUtils.isRegularFile(file)) {
                        log.info("Event - File closed: ${file.path} - Irregular file, ignored")
                        return
                    }
                    log.info("Event - File closed: ${file.path}")

                    // Check if file is still open in other editors
                    val isStillOpen = fileUtils.isFileOpenInOtherTabs(file)
                    if (isStillOpen) {
                        log.info("File is still open in other editors, skipping close message: ${file.path}")
                        return
                    }

                    // Create close state and send to queue (no dependency on editor object)
                    val state = editorStateManager.createCloseState(file.path, windowStateManager.isWindowActive())
                    log.info("Preparing to send close message: $state")
                    editorStateManager.updateState(state)
                }

                override fun selectionChanged(event: FileEditorManagerEvent) {
                    if (event.newFile != null) {
                        if (!fileUtils.isRegularFile(event.newFile!!)) {
                            log.info("Event - File changed: ${event.newFile!!.path} - Irregular file, ignored")
                            return
                        }
                        log.info("Event - File changed: ${event.newFile!!.path}")
                        val fileEditor = event.newEditor
                        val editor = if (fileEditor is com.intellij.openapi.fileEditor.TextEditor) {
                            fileEditor.editor
                        } else {
                            null
                        }
                        editor?.let {
                            val state = editorStateManager.createEditorState(
                                it, event.newFile!!, ActionType.OPEN, windowStateManager.isWindowActive()
                            )
                            log.info("Preparing to send open message: ${state.filePath}，${state.getCursorLog()}，${state.getSelectionLog()}")
                            editorStateManager.updateState(state)
                            setupCaretListener(it)
                            setupSelectionListener(it)
                            currentEditor = it
                        }
                    }
                }
            }
        )
        log.info("Editor listeners setup completed")
    }


    /**
     * Unified handling of editor cursor and selection events
     * @param editor Editor instance
     * @param eventType Event type ("cursor changed" or "selection changed")
     */
    private fun handleEditorPositionOrSelectionEvent(editor: Editor, eventType: String) {
        log.info("Event-$eventType")

        // Dynamically get current actual file
        val currentFile = editor.virtualFile
        if (currentFile == null) {
            log.warn("Event-$eventType: Unable to get current file, skipping processing")
            return
        }
        if (!fileUtils.isRegularFile(currentFile)) {
            log.info("Event-$eventType: ${currentFile.path} - Irregular file, ignored")
            return
        }

        // Get cursor position information
        val caretModel = editor.caretModel
        val logicalPosition = caretModel.logicalPosition
        var logMessage = "Event-$eventType: ${currentFile.path}，${LogFormatter.cursorLog(logicalPosition.line, logicalPosition.column)}"

        // Add selection information (regardless of event type)
        val selectionModel = editor.selectionModel
        val hasSelection = selectionModel.hasSelection()

        if (hasSelection) {
            val startPosition = editor.offsetToLogicalPosition(selectionModel.selectionStart)
            val endPosition = editor.offsetToLogicalPosition(selectionModel.selectionEnd)
            logMessage += "，${LogFormatter.selectionLog(startPosition.line, startPosition.column, endPosition.line, endPosition.column)}"
        } else {
            logMessage += "，${LogFormatter.selectionLog(null, null, null, null)}"
        }

        log.info(logMessage)

        val state = editorStateManager.createEditorState(
            editor, currentFile, ActionType.NAVIGATE, windowStateManager.isWindowActive()
        )
        log.info("Preparing to send navigation message: ${state.filePath}，${state.getCursorLog()}，${state.getSelectionLog()}")
        editorStateManager.debouncedUpdateState(state)
    }


    /**
     * Setup cursor listener
     * Globally unique, destroys previous listener when setting up new one
     */
    private fun setupCaretListener(editor: Editor) {
        log.info("Starting to setup cursor listener")

        // Destroy previous cursor listener
        destroyCurrentCaretListener()

        // Create new cursor listener
        val newCaretListener = object : com.intellij.openapi.editor.event.CaretListener {
            override fun caretPositionChanged(event: com.intellij.openapi.editor.event.CaretEvent) {
                handleEditorPositionOrSelectionEvent(event.editor, "cursor changed")
            }
        }

        // Add new listener
        editor.caretModel.addCaretListener(newCaretListener)

        // Save reference for subsequent management
        currentCaretListener = newCaretListener

        log.info("Cursor listener setup completed")
    }

    /**
     * Setup selection listener
     * Globally unique, destroys previous listener when setting up new one
     */
    private fun setupSelectionListener(editor: Editor) {
        log.info("Starting to setup selection listener")

        // Destroy previous selection listener
        destroyCurrentSelectionListener()

        // Create new selection listener
        val newSelectionListener = object : com.intellij.openapi.editor.event.SelectionListener {
            override fun selectionChanged(event: com.intellij.openapi.editor.event.SelectionEvent) {
                handleEditorPositionOrSelectionEvent(event.editor, "selection changed")
            }
        }

        // Add new listener
        editor.selectionModel.addSelectionListener(newSelectionListener)

        // Save reference for subsequent management
        currentSelectionListener = newSelectionListener

        log.info("Selection listener setup completed")
    }

    /**
     * Destroy current cursor listener
     */
    private fun destroyCurrentCaretListener() {
        if (currentCaretListener != null && currentEditor != null) {
            log.info("Destroying previous cursor listener")
            try {
                currentEditor!!.caretModel.removeCaretListener(currentCaretListener!!)
                log.info("Cursor listener destroyed successfully")
            } catch (e: Exception) {
                log.warn("Exception occurred when destroying cursor listener: ${e.message}")
            }
            currentCaretListener = null
        }
    }

    /**
     * Destroy current selection listener
     */
    private fun destroyCurrentSelectionListener() {
        if (currentSelectionListener != null && currentEditor != null) {
            log.info("Destroying previous selection listener")
            try {
                currentEditor!!.selectionModel.removeSelectionListener(currentSelectionListener!!)
                log.info("Selection listener destroyed successfully")
            } catch (e: Exception) {
                log.warn("Exception occurred when destroying selection listener: ${e.message}")
            }
            currentSelectionListener = null
        }
    }


    /**
     * Clean up resources
     */
    fun dispose() {
        log.info("Starting to clean up EventListenerManager resources")
        messageBusConnection?.disconnect()
        messageBusConnection?.dispose()
        destroyCurrentCaretListener()
        destroyCurrentSelectionListener()
        currentEditor = null
        log.info("EventListenerManager resource cleanup completed")
    }
}
