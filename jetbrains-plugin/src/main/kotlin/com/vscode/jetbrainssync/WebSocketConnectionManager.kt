package com.vscode.jetbrainssync

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/**
 * WebSocket connection manager
 * Responsible for WebSocket connection establishment, maintenance, reconnection and state management
 */
class WebSocketConnectionManager(
    private val project: Project,
    private val messageProcessor: MessageProcessor
) {
    private val log: Logger = Logger.getInstance(WebSocketConnectionManager::class.java)

    private var webSocket: WebSocketClient? = null
    private val connectionState = AtomicReference(ConnectionState.DISCONNECTED)
    private val autoReconnect = AtomicBoolean(false)
    private var connectionCallback: ConnectionCallback? = null

    // Synchronization lock, protecting thread safety of critical operations
    private val connectionLock = ReentrantLock()

    // Loop thread pool and timer
    private val scheduleExecutorService: ExecutorService = Executors.newSingleThreadExecutor { r ->
        val thread = Thread(r, "WebSocket-Schedule-Connection-Worker")
        thread.isDaemon = true
        thread
    }

    // Thread pool and timer
    private val executorService: ExecutorService = Executors.newSingleThreadExecutor { r ->
        val thread = Thread(r, "WebSocket-Connection-Worker")
        thread.isDaemon = true
        thread
    }


    // Configuration parameters
    private val reconnectDelayMs = 5000L


    fun setConnectionCallback(callback: ConnectionCallback) {
        this.connectionCallback = callback
    }


    init {
        loopConnectWebSocket()
    }

    /**
     * Toggle auto reconnect status
     * Use lock to protect atomicity of status switching, avoiding race conditions
     */
    fun toggleAutoReconnect() {
        connectionLock.lock()
        try {
            val currentState = autoReconnect.get()
            val newState = !currentState

            // Use compareAndSet to ensure atomicity of status change
            if (!autoReconnect.compareAndSet(currentState, newState)) {
                log.warn("Auto reconnect status has been modified by another thread, operation cancelled")
                return
            }

            log.info("Auto reconnect status toggled to: ${if (newState) "enabled" else "disabled"}")

            if (!newState) {
                disconnectAndCleanup()
                log.info("Sync disabled, connection disconnected")
            } else {
                connectWebSocket()
                log.info("Sync enabled, starting connection...")
            }
        } finally {
            connectionLock.unlock()
        }
    }


    /**
     * Loop to create WebSocket client and attempt connection
     */
    private fun loopConnectWebSocket() {
        scheduleExecutorService.submit {
            while (true) {
                if (autoReconnect.get().not()) {
                    Thread.sleep(reconnectDelayMs)
                    continue;
                }
                connectWebSocket()
                Thread.sleep(reconnectDelayMs)
            }
        }
    }


    /**
     */
    private fun connectWebSocket() {
        executorService.submit {
            try {
                if (autoReconnect.get().not()) {
                    return@submit
                }
                if (!connectionState.compareAndSet(ConnectionState.DISCONNECTED, ConnectionState.CONNECTING)) {
                    return@submit
                }
                // Disconnect and clean up
                cleanUp()

                ApplicationManager.getApplication().invokeLater { connectionCallback?.onReconnecting() }
                val port = VSCodeJetBrainsSyncSettings.getInstance(project).state.port
                log.info("Attempting to connect to VSCode, port: $port")

                webSocket = createWebSocketClient(port)
                webSocket?.connectionLostTimeout = 0

                val connectResult = webSocket?.connectBlocking()
                if (connectResult != true) {
                    handleConnectionError()
                }
            } catch (e: InterruptedException) {
                log.warn("Thread was interrupted")
                return@submit
            } catch (e: Exception) {
                try {
                    log.warn("WebSocket server error: ${e.message}", e)
                    handleConnectionError()
                } catch (e: Exception) {

                }
            }
        }
    }

    /**
     * Create WebSocket client
     */
    private fun createWebSocketClient(port: Int): WebSocketClient {
        return object : WebSocketClient(URI("ws://localhost:${port}/jetbrains")) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                log.info("Successfully connected to VSCode, port: $port")
                connectionState.set(ConnectionState.CONNECTED)
                ApplicationManager.getApplication().invokeLater {
                    connectionCallback?.onConnected()
                    log.info("JetBrains IDE client connected")
                    showNotification("Connected to VSCode", NotificationType.INFORMATION)
                }
            }

            override fun onMessage(message: String?) {
                message?.let { messageProcessor.handleIncomingMessage(it) }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                log.info("Disconnected from VSCode - Code: $code, Reason: $reason, Remote: $remote")
                showNotification("Disconnected from VSCode", NotificationType.WARNING)
                handleConnectionError()
            }

            override fun onError(ex: Exception?) {
                log.warn("WebSocket connection error: ${ex?.message}")
                handleConnectionError()
            }
        }
    }

    /**
     * Send message
     */
    fun sendMessage(message: String): Boolean {
        if (isConnected().not() || isAutoReconnect().not()) {
            log.warn("Currently not connected, discarding message: $message")
            return false
        }
        return webSocket?.let { client ->
            if (client.isOpen) {
                try {
                    if (!isConnected()) {
                        log.info("Currently not connected, discarding message: $message")
                    }
                    client.send(message)
                    true
                } catch (e: Exception) {
                    log.warn("Failed to send message: ${e.message}", e)
                    false
                }
            } else {
                log.warn("WebSocket not connected, status: ${client.readyState}")
                if (connectionState.get() != ConnectionState.CONNECTING) {
                    connectWebSocket()
                }
                false
            }
        } ?: run {
            log.warn("WebSocket client is null, attempting to reconnect...")
            connectWebSocket()
            false
        }
    }

    /**
     * Handle connection error
     * Use lock to protect state changes, ensuring atomicity of error handling
     */
    private fun handleConnectionError() {
        connectionState.set(ConnectionState.DISCONNECTED)
        ApplicationManager.getApplication().invokeLater {
            connectionCallback?.onDisconnected()
        }
        Thread.sleep(reconnectDelayMs)
        // Attempt to reconnect
        connectWebSocket()
    }


    /**
     * Disconnect and clean up resources
     * Use lock to protect atomicity of cleanup operations
     */
    fun disconnectAndCleanup() {
        cleanUp()
        connectionState.set(ConnectionState.DISCONNECTED)
        ApplicationManager.getApplication().invokeLater {
            connectionCallback?.onDisconnected()
        }
    }


    fun cleanUp() {
        connectionLock.lock()
        try {
            webSocket?.let { client ->
                if (client.isOpen) {
                    client.close()
                    log.info("WebSocket connection closed")
                }
                webSocket = null
            }
        } finally {
            connectionLock.unlock()
        }
    }

    /**
     * Restart connection
     * Implement restart by calling thread-safe methods
     */
    fun restartConnection() {
        log.info("Manually restarting connection")
        connectWebSocket()
    }

    /**
     * Show notification
     */
    private fun showNotification(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("VSCode JetBrains Sync")
            .createNotification(message, type)
            .notify(project)
    }

    // Status query methods
    fun isConnected(): Boolean = connectionState.get() == ConnectionState.CONNECTED
    fun isAutoReconnect(): Boolean = autoReconnect.get()
    fun isConnecting(): Boolean = connectionState.get() == ConnectionState.CONNECTING
    fun isDisconnected(): Boolean = connectionState.get() == ConnectionState.DISCONNECTED
    fun getConnectionState(): ConnectionState = connectionState.get()

    /**
     * Clean up resources
     */
    fun dispose() {
        log.info("Starting to clean up WebSocket connection manager resources")

        autoReconnect.set(false)
        executorService.shutdown()
        try {
            if (!executorService.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                executorService.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executorService.shutdownNow()
        }
        scheduleExecutorService.shutdown()
        try {
            if (!scheduleExecutorService.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                scheduleExecutorService.shutdownNow()
            }
        } catch (e: InterruptedException) {
            scheduleExecutorService.shutdownNow()
        }

        disconnectAndCleanup()
        log.info("WebSocket connection manager resource cleanup completed")
    }
}
