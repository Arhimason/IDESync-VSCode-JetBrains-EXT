package com.vscode.jetbrainssync

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import java.awt.Frame
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Timer

/**
 * Window state manager
 * Unifies management of window active state, providing efficient and accurate state queries
 * Combines the high performance of event listening with the accuracy of real-time queries
 */
class WindowStateManager(private val project: Project) {
    private val log: Logger = Logger.getInstance(WindowStateManager::class.java)

    // State cache maintained by event listening (high performance query)
    private val isActiveCache = AtomicBoolean(true)

    // State change callback
    private var onWindowStateChange: ((Boolean) -> Unit)? = null

    // Frame acquisition retry configuration
    private val maxRetryCount = 10  // Maximum retry count
    private val retryDelayMs = 500  // Retry interval (milliseconds)
    private var currentRetryCount = 0
    private var retryTimer: Timer? = null

    // Mark whether listener has been successfully set up
    private var isListenerSetup = false

    /**
     * Get project name
     */
    private fun getWorkspaceName(): String {
        return try {
            project.name
        } catch (e: Exception) {
            "unknown-project"
        }
    }

    /**
     * Initialize window state listening
     */
    fun initialize() {
        val workspaceName = getWorkspaceName()
        log.info("Initializing window state manager: $workspaceName")

        // Delay initialization of Frame listener to ensure window is fully created
        ApplicationManager.getApplication().invokeLater {
            setupWindowFocusListenerWithRetry()
        }

        // Get real state during initialization
        isActiveCache.set(getRealTimeWindowState())
        log.info("Window state manager initialization completed: $workspaceName, current state: ${isActiveCache.get()}")
    }

    /**
     * Window focus listener setup with retry mechanism
     */
    private fun setupWindowFocusListenerWithRetry() {
        val workspaceName = getWorkspaceName()
        if (isListenerSetup) {
            log.info("Window focus listener already set up, skipping duplicate setup: $workspaceName")
            return
        }

        val frame = WindowManager.getInstance().getFrame(project)
        if (frame != null) {
            log.info("Successfully obtained window Frame, setting up focus listener: $workspaceName")
            setupWindowFocusListener(frame)
            isListenerSetup = true
            currentRetryCount = 0

            // Stop retry timer
            retryTimer?.stop()
            retryTimer = null
        } else {
            currentRetryCount++
            log.warn("Unable to obtain window Frame: $workspaceName, retry count: $currentRetryCount/$maxRetryCount")

            if (currentRetryCount < maxRetryCount) {
                // Set timer for retry
                retryTimer = Timer(retryDelayMs) {
                    setupWindowFocusListenerWithRetry()
                }
                retryTimer?.isRepeats = false
                retryTimer?.start()
                log.info("Will retry obtaining Frame in ${retryDelayMs}ms: $workspaceName")
            } else {
                log.error("Reached maximum retry count, giving up on setting up window focus listener: $workspaceName")
            }
        }
    }

    /**
     * Set up window focus listener (actual setup logic)
     */
    private fun setupWindowFocusListener(frame: Frame) {
        val workspaceName = getWorkspaceName()
        log.info("Setting up window focus listener for project: $workspaceName")

        frame.addWindowFocusListener(object : java.awt.event.WindowFocusListener {
            override fun windowGainedFocus(e: java.awt.event.WindowEvent?) {
                if (frame.isVisible && frame.state != Frame.ICONIFIED && frame.isFocused) {
                    updateWindowState(true)
                    log.info("JetBrains window gained focus: $workspaceName")
                }
            }

            override fun windowLostFocus(e: java.awt.event.WindowEvent?) {
                updateWindowState(false)
                log.info("JetBrains window lost focus: $workspaceName")
            }
        })

        log.info("Window focus listener setup successful: $workspaceName")
    }

    /**
     * Update window state and trigger callback
     */
    private fun updateWindowState(isActive: Boolean) {
        val previousState = isActiveCache.get()
        isActiveCache.set(isActive)

        // Trigger callback when state changes
        if (previousState != isActive) {
            onWindowStateChange?.invoke(isActive)
        }
    }

    /**
     * Get window active state (high performance version)
     * In most cases, use cache state maintained by event listening
     * @param forceRealTime Whether to force real-time query, default false
     * @return Whether window is active
     */
    fun isWindowActive(forceRealTime: Boolean = false): Boolean {
        return if (forceRealTime) {
            // Force real-time query, used for critical operations or state verification
            val realTimeState = getRealTimeWindowState()

            // If the cache state is inconsistent with the real-time state, update the cache
            val cachedState = isActiveCache.get()
            if (cachedState != realTimeState) {
                log.warn("Detected state inconsistency, cache: $cachedState, real-time: $realTimeState, syncing")
                updateWindowState(realTimeState)
            }

            realTimeState
        } else {
            // Use high performance cache state
            isActiveCache.get()
        }
    }

    /**
     * Get window state in real-time
     * Get directly from system API to ensure state accuracy
     */
    private fun getRealTimeWindowState(): Boolean {
        return try {
            val frame = WindowManager.getInstance().getFrame(project)
            frame?.isFocused == true && frame.isVisible && frame.state != Frame.ICONIFIED
        } catch (e: Exception) {
            log.warn("Failed to get real-time window state: ${e.message}")
            isActiveCache.get()
        }
    }

    /**
     * Set window state change callback
     * @param callback Callback function when state changes, parameter is new active state
     */
    fun setOnWindowStateChangeCallback(callback: (Boolean) -> Unit) {
        this.onWindowStateChange = callback
    }

    /**
     * Clean up resources
     */
    fun dispose() {
        val workspaceName = getWorkspaceName()
        log.info("Starting to clean up window state manager resources: $workspaceName")

        // Stop retry timer
        retryTimer?.stop()
        retryTimer = null

        // Clean up callback
        onWindowStateChange = null

        // Reset state
        isListenerSetup = false
        currentRetryCount = 0

        log.info("Window state manager resource cleanup completed: $workspaceName")
    }

}