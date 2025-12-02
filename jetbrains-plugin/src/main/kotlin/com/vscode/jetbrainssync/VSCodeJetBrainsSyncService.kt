package com.vscode.jetbrainssync

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * VSCode and JetBrains Sync Service (Refactored Version)
 *
 * Adopting modular design with main components:
 * - Window State Manager: Unified management of window active state
 * - TCP Server Manager: Responsible for TCP server establishment, maintenance and client connections
 * - Editor State Manager: Manages state caching, debouncing and deduplication
 * - File Operation Handler: Handles file opening, closing and navigation
 * - Event Listener Manager: Unified management of various event listeners
 * - Message Processor: Handles message serialization and deserialization
 * - Operation Queue Processor: Ensures operation atomicity and ordering
 */
@Service(Service.Level.PROJECT)
class VSCodeJetBrainsSyncService(private val project: Project) : Disposable {
    private val log: Logger = Logger.getInstance(VSCodeJetBrainsSyncService::class.java)

    // Core Components
    private val fileUtils = FileUtils(project, log)
    private val localIdentifierManager = LocalIdentifierManager(project)
    private val windowStateManager = WindowStateManager(project)
    private val editorStateManager = EditorStateManager(project, fileUtils)
    private val eventListenerManager = EventListenerManager(project, editorStateManager, windowStateManager, fileUtils)
    private val fileOperationHandler = FileOperationHandler(editorStateManager, windowStateManager, fileUtils)
    private val messageProcessor = MessageProcessor(fileOperationHandler, localIdentifierManager)
    
    // Using TCP server manager to replace multicast manager
    private val tcpServerManager = TcpServerManager(project, messageProcessor)
    private val operationQueueProcessor = OperationQueueProcessor(tcpServerManager, localIdentifierManager)
    
    // Partner launcher
    private val partnerLauncher = PartnerLauncher(project)


    init {
        log.info("Initializing VSCode-JetBrains sync service (refactored version)")

        // FileUtils has been initialized during construction, no additional initialization needed

        // Initialize window state manager
        windowStateManager.initialize()

        // Setup status bar
        setupStatusBar()

        // Setup callback relationships between components
        setupComponentCallbacks()
        // Initialize event listeners
        eventListenerManager.setupEditorListeners()

        // Check auto-start configuration
        checkAutoStartConfig()

        log.info("Sync service initialization completed")
    }


    /**
     * Setup status bar component
     */
    private fun setupStatusBar() {
        ApplicationManager.getApplication().invokeLater {
            SyncStatusBarWidgetFactory().createWidget(project)
            log.info("Status bar component setup completed")
        }
    }

    /**
     * Setup callback relationships between components
     */
    private fun setupComponentCallbacks() {
        // Window state change callback
        windowStateManager.setOnWindowStateChangeCallback { isActive ->
            if (!isActive) {
                // Send workspace sync state when window loses focus
                val workspaceSyncState = editorStateManager.createWorkspaceSyncState(true)
                log.info("Window lost focus, sending workspace sync state with ${workspaceSyncState.openedFiles?.size ?: 0} opened files")
                editorStateManager.updateState(workspaceSyncState)
            }
        }

        // Connection state change callback
        tcpServerManager.setConnectionCallback(object : ConnectionCallback {
            override fun onConnected() {
                log.info("TCP connection state changed: Connected")
                updateStatusBarWidget()
                editorStateManager.sendCurrentState(windowStateManager.isWindowActive())
            }

            override fun onDisconnected() {
                log.info("TCP connection state changed: Disconnected")
                updateStatusBarWidget()
            }

            override fun onReconnecting() {
                log.info("TCP connection state changed: Waiting to connect")
                updateStatusBarWidget()
                // If no client connected, prompt to launch partner IDE
                checkAndPromptPartnerLaunch()
            }
        })

        // State change callback
        editorStateManager.setStateChangeCallback(object : EditorStateManager.StateChangeCallback {
            override fun onStateChanged(state: EditorState) {
                if (state.isActive) {
                    operationQueueProcessor.addOperation(state)
                }
            }
        })
    }

    /**
     * Check auto-start configuration
     */
    private fun checkAutoStartConfig() {
        val settings = VSCodeJetBrainsSyncSettings.getInstance(project)
        if (settings.state.autoStartSync) {
            log.info("Auto-start configuration detected as enabled, starting sync functionality...")
            tcpServerManager.toggleAutoReconnect()
        } else {
            log.info("Auto-start configuration is disabled, manual start of sync functionality required")
        }
    }

    /**
     * Check and prompt to launch partner IDE
     */
    private fun checkAndPromptPartnerLaunch() {
        // Delayed check, give time for client connection
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                java.util.concurrent.TimeUnit.SECONDS.sleep(3)
                if (tcpServerManager.isConnecting() && !tcpServerManager.isConnected()) {
                    partnerLauncher.checkAndPromptLaunch {
                        // Callback after launch completion - no special handling needed, client will connect automatically
                        log.info("Partner IDE launch process completed")
                    }
                }
            } catch (e: InterruptedException) {
                // Ignore
            }
        }
    }

    /**
     * Update status bar display
     */
    private fun updateStatusBarWidget() {
        ApplicationManager.getApplication().invokeLater {
            val statusBar = com.intellij.openapi.wm.WindowManager.getInstance().getStatusBar(project)
            val widget = statusBar?.getWidget(SyncStatusBarWidget.ID) as? SyncStatusBarWidget
            widget?.updateUI()
        }
    }


    /**
     * Toggle TCP sync state
     */
    fun toggleAutoReconnect() {
        tcpServerManager.toggleAutoReconnect()
        updateStatusBarWidget()
    }

    // Public interface methods (delegated to various modules)

    fun isConnected(): Boolean = tcpServerManager.isConnected()
    fun isAutoReconnect(): Boolean = tcpServerManager.isAutoReconnect()
    fun isConnecting(): Boolean = tcpServerManager.isConnecting()
    fun isDisconnected(): Boolean = tcpServerManager.isDisconnected()

    /**
     * Get server port
     */
    fun getServerPort(): Int = tcpServerManager.getServerPort()

    /**
     * Get partner info
     */
    fun getPartnerInfo(): PartnerInfo? = tcpServerManager.getPartnerInfo()

    /**
     * Restart connection
     */
    fun restartConnection() {
        log.info("Restarting TCP server connection")
        tcpServerManager.restartConnection()
        updateStatusBarWidget()
    }


    /**
     * Cleanup resources
     */
    override fun dispose() {
        log.info("Starting cleanup of sync service resources (refactored version)")

        // Clean up components in order
        operationQueueProcessor.dispose()
        tcpServerManager.dispose()
        editorStateManager.dispose()
        eventListenerManager.dispose()
        windowStateManager.dispose()

        log.info("Sync service resource cleanup completed")
    }
}
