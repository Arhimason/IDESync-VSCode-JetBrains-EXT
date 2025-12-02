package com.vscode.jetbrainssync

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

/**
 * TCP Server Manager
 * JetBrains acts as server, VSCode connects as client
 * Supports multiple projects running simultaneously (each project has independent port)
 */
class TcpServerManager(
    private val project: Project,
    private val messageProcessor: MessageProcessor,
) : MessageSender {
    private val log: Logger = Logger.getInstance(TcpServerManager::class.java)
    private val gson = Gson()

    // ==================== Configuration Constants ====================
    private val portRangeStart = 3000
    private val portRangeEnd = 4000
    private val heartbeatIntervalMs = 2000L // Heartbeat interval 2 seconds
    private val heartbeatTimeoutMs = 6000L  // Heartbeat timeout 6 seconds (3 heartbeats)

    // ==================== Network Components ====================
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var clientWriter: PrintWriter? = null
    private var clientReader: BufferedReader? = null
    private var serverPort: Int = 0

    // ==================== Connection State ====================
    private val connectionState = AtomicReference(ConnectionState.DISCONNECTED)
    private val autoReconnect = AtomicBoolean(false)
    private var connectionCallback: ConnectionCallback? = null
    private var partnerInfo: PartnerInfo? = null
    private var lastHeartbeatReceived: Long = 0

    // ==================== Thread Management ====================
    private val executorService: ExecutorService = Executors.newCachedThreadPool { r ->
        val thread = Thread(r, "TCP-Server-Manager-Worker")
        thread.isDaemon = true
        thread
    }
    private val connectionLock = ReentrantLock()
    private val isShutdown = AtomicBoolean(false)
    private var acceptThread: Thread? = null
    private var readerThread: Thread? = null
    private var heartbeatThread: Thread? = null

    init {
        log.info("Initializing TCP server manager - Project: ${project.basePath}")
    }

    // ==================== Public Interface Methods ====================

    /**
     * Set connection state callback
     */
    fun setConnectionCallback(callback: ConnectionCallback) {
        this.connectionCallback = callback
    }

    /**
     * Toggle auto reconnect state (start/stop server)
     */
    fun toggleAutoReconnect() {
        connectionLock.lock()
        try {
            val currentState = autoReconnect.get()
            val newState = !currentState

            if (!autoReconnect.compareAndSet(currentState, newState)) {
                log.warn("Auto reconnect state has been modified by another thread, operation cancelled")
                return
            }

            log.info("TCP server state changed to: ${if (newState) "enabled" else "disabled"}")

            if (!newState) {
                stopServer()
                log.info("TCP server has been closed")
            } else {
                startServer()
                log.info("TCP server has been enabled, starting to listen for client connections...")
            }
        } finally {
            connectionLock.unlock()
        }
    }

    /**
     * Get current server port
     */
    fun getServerPort(): Int = serverPort

    /**
     * Get project path
     */
    fun getProjectPath(): String = project.basePath ?: ""

    /**
     * Get partner info
     */
    fun getPartnerInfo(): PartnerInfo? = partnerInfo

    // ==================== Server Management Methods ====================

    /**
     * Start server
     */
    private fun startServer() {
        if (isShutdown.get() || !autoReconnect.get()) {
            return
        }

        executorService.submit {
            try {
                if (!autoReconnect.get() || isShutdown.get()) {
                    return@submit
                }

                setConnectionState(ConnectionState.CONNECTING)
                log.info("Starting TCP server...")

                // Find available port
                serverPort = findAvailablePort()
                if (serverPort == 0) {
                    log.error("Unable to find available port in range $portRangeStart-$portRangeEnd")
                }

                // Create server socket
                serverSocket = ServerSocket(serverPort)
                log.info("TCP server started successfully, listening on port: $serverPort, Project: ${project.basePath}")

                // Start accept thread
                startAcceptThread()

                // Start heartbeat thread
                startHeartbeatThread()

            } catch (e: Exception) {
                log.warn("Failed to start TCP server: ${e.message}", e)
                handleConnectionError()
            }
        }
    }

    /**
     * Find available port
     */
    private fun findAvailablePort(): Int {
        val settings = VSCodeJetBrainsSyncSettings.getInstance(project)
        
        // If custom port is enabled, try to use it
        if (settings.state.useCustomPort) {
            val customPort = settings.state.customPort
            if (customPort in 1024..65535) {
                try {
                    ServerSocket(customPort).use { testSocket ->
                        log.info("Using custom port: $customPort")
                        return customPort
                    }
                } catch (e: Exception) {
                    log.warn("Custom port $customPort is not available, falling back to automatic selection")
                }
            }
        }
        
        // Automatic port selection
        for (port in portRangeStart..portRangeEnd) {
            try {
                ServerSocket(port).use { testSocket ->
                    log.info("Automatically selected port: $port")
                    return port
                }
            } catch (e: Exception) {
                // Port is occupied, try next port
                continue
            }
        }
        return 0
    }

    /**
     * Start accept thread
     */
    private fun startAcceptThread() {
        acceptThread = thread(name = "TCP-Server-Accept-${project.name}") {
            while (!isShutdown.get() && autoReconnect.get()) {
                try {
                    log.info("Waiting for client connection... (Port: $serverPort)")
                    val socket = serverSocket?.accept() ?: break
                    
                    log.info("Client connected: ${socket.inetAddress.hostAddress}:${socket.port}")
                    handleNewClient(socket)

                } catch (e: SocketException) {
                    if (!isShutdown.get() && autoReconnect.get()) {
                        log.warn("Error accepting connection: ${e.message}")
                    }
                    break
                } catch (e: Exception) {
                    if (!isShutdown.get()) {
                        log.warn("Error accepting connection: ${e.message}", e)
                    }
                }
            }
            log.info("Accept thread exited")
        }
    }

    /**
     * Handle new client connection
     */
    private fun handleNewClient(socket: Socket) {
        connectionLock.lock()
        try {
            // Close old connection
            closeClientConnection()

            clientSocket = socket
            clientWriter = PrintWriter(socket.getOutputStream(), true)
            clientReader = BufferedReader(InputStreamReader(socket.inputStream))

            // Send handshake message
            sendHandshake()

            // Start reader thread
            startReaderThread()

        } finally {
            connectionLock.unlock()
        }
    }

    /**
     * Send handshake message
     */
    private fun sendHandshake() {
        val handshake = HandshakeMessage(
            type = "HANDSHAKE",
            projectPath = project.basePath ?: "",
            ideType = "jetbrains",
            ideName = com.intellij.openapi.application.ApplicationInfo.getInstance().fullApplicationName,
            port = serverPort
        )

        val json = gson.toJson(handshake)
        clientWriter?.println(json)
        log.info("Sending handshake message: $json")
    }

    /**
     * Start reader thread
     */
    private fun startReaderThread() {
        readerThread = thread(name = "TCP-Server-Reader-${project.name}") {
            try {
                while (!isShutdown.get() && clientSocket?.isConnected == true) {
                    val line = clientReader?.readLine() ?: break
                    handleReceivedMessage(line)
                }
            } catch (e: SocketException) {
                if (!isShutdown.get()) {
                    log.info("Client disconnected")
                }
            } catch (e: Exception) {
                if (!isShutdown.get()) {
                    log.warn("Error reading message: ${e.message}", e)
                }
            } finally {
                handleClientDisconnected()
            }
            log.info("Reader thread exited")
        }
    }

    /**
     * Start heartbeat thread
     */
    private fun startHeartbeatThread() {
        heartbeatThread = thread(name = "TCP-Server-Heartbeat-${project.name}") {
            while (!isShutdown.get() && autoReconnect.get()) {
                try {
                    Thread.sleep(heartbeatIntervalMs)

                    if (clientSocket?.isConnected == true && connectionState.get() == ConnectionState.CONNECTED) {
                        // Send heartbeat
                        sendHeartbeat()

                        // Check heartbeat timeout
                        if (lastHeartbeatReceived > 0 && 
                            System.currentTimeMillis() - lastHeartbeatReceived > heartbeatTimeoutMs) {
                            log.warn("Heartbeat timeout, client may have disconnected")
                            handleClientDisconnected()
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    log.warn("Error sending heartbeat: ${e.message}")
                }
            }
            log.info("Heartbeat thread exited")
        }
    }

    /**
     * Send heartbeat message
     */
    private fun sendHeartbeat() {
        val heartbeat = JsonObject().apply {
            addProperty("type", "HEARTBEAT")
            addProperty("timestamp", System.currentTimeMillis())
            addProperty("projectPath", project.basePath)
        }
        clientWriter?.println(gson.toJson(heartbeat))
    }

    // ==================== Message Processing Methods ====================

    /**
     * Handle received message
     */
    private fun handleReceivedMessage(message: String) {
        try {
            val json = gson.fromJson(message, JsonObject::class.java)
            val type = json.get("type")?.asString

            when (type) {
                "HANDSHAKE_ACK" -> handleHandshakeAck(json)
                "HEARTBEAT" -> handleHeartbeat(json)
                "HEARTBEAT_ACK" -> handleHeartbeatAck()
                else -> {
                    // Normal sync message
                    messageProcessor.handleMessage(message)
                }
            }
        } catch (e: Exception) {
            log.warn("Error processing message: ${e.message}", e)
        }
    }

    /**
     * Handle handshake acknowledgment
     */
    private fun handleHandshakeAck(json: JsonObject) {
        val projectPath = json.get("projectPath")?.asString ?: ""
        val ideType = json.get("ideType")?.asString ?: ""
        
        // Verify project path match
        if (pathsMatch(projectPath, project.basePath ?: "")) {
            partnerInfo = PartnerInfo(
                ideType = ideType,
                projectPath = projectPath,
                connectedAt = System.currentTimeMillis()
            )
            lastHeartbeatReceived = System.currentTimeMillis()
            setConnectionState(ConnectionState.CONNECTED)
            log.info("Handshake successful, connected to $ideType (Project: $projectPath)")
        } else {
            log.warn("Project path mismatch: Local=${project.basePath}, Remote=$projectPath")
            closeClientConnection()
        }
    }

    /**
     * Handle heartbeat message
     */
    private fun handleHeartbeat(json: JsonObject) {
        lastHeartbeatReceived = System.currentTimeMillis()
        // Send heartbeat acknowledgment
        val ack = JsonObject().apply {
            addProperty("type", "HEARTBEAT_ACK")
            addProperty("timestamp", System.currentTimeMillis())
        }
        clientWriter?.println(gson.toJson(ack))
    }

    /**
     * Handle heartbeat acknowledgment
     */
    private fun handleHeartbeatAck() {
        lastHeartbeatReceived = System.currentTimeMillis()
    }

    /**
     * Check if paths match
     */
    private fun pathsMatch(path1: String, path2: String): Boolean {
        val norm1 = normalizePath(path1)
        val norm2 = normalizePath(path2)
        return norm1 == norm2 || norm1.startsWith(norm2) || norm2.startsWith(norm1)
    }

    /**
     * Normalize path
     */
    private fun normalizePath(path: String): String {
        return path.replace('\\', '/').lowercase().trimEnd('/')
    }

    /**
     * Send message to client
     */
    override fun sendMessage(messageWrapper: MessageWrapper): Boolean {
        if (connectionState.get() != ConnectionState.CONNECTED || clientWriter == null) {
            log.warn("Not connected, discarding message")
            return false
        }

        return try {
            val messageString = messageWrapper.toJsonString()
            clientWriter?.println(messageString)
            log.info("Sending message: $messageString")
            true
        } catch (e: Exception) {
            log.warn("Failed to send message: ${e.message}", e)
            handleClientDisconnected()
            false
        }
    }

    // ==================== Connection Management Methods ====================

    /**
     * Handle client disconnection
     */
    private fun handleClientDisconnected() {
        connectionLock.lock()
        try {
            closeClientConnection()
            partnerInfo = null
            lastHeartbeatReceived = 0
            setConnectionState(ConnectionState.CONNECTING) // Back to connecting state
            log.info("Client disconnected, waiting for new connection...")
        } finally {
            connectionLock.unlock()
        }
    }

    /**
     * Close client connection
     */
    private fun closeClientConnection() {
        try {
            readerThread?.interrupt()
            readerThread = null
            clientWriter?.close()
            clientWriter = null
            clientReader?.close()
            clientReader = null
            clientSocket?.close()
            clientSocket = null
        } catch (e: Exception) {
            log.warn("Error closing client connection: ${e.message}")
        }
    }

    /**
     * Handle connection error
     */
    private fun handleConnectionError() {
        setConnectionState(ConnectionState.DISCONNECTED)
        
        if (autoReconnect.get() && !isShutdown.get()) {
            executorService.submit {
                try {
                    Thread.sleep(5000) // Wait 5 seconds before restarting
                    if (autoReconnect.get() && !isShutdown.get()) {
                        startServer()
                    }
                } catch (e: InterruptedException) {
                    // Thread interrupted, exit
                }
            }
        }
    }

    /**
     * Set connection state and trigger callback
     */
    private fun setConnectionState(state: ConnectionState) {
        val oldState = connectionState.get()
        if (oldState == state) {
            return
        }

        connectionState.set(state)
        log.info("Connection state changed: $oldState -> $state")

        when (state) {
            ConnectionState.CONNECTED -> connectionCallback?.onConnected()
            ConnectionState.CONNECTING -> connectionCallback?.onReconnecting()
            ConnectionState.DISCONNECTED -> connectionCallback?.onDisconnected()
        }
    }

    /**
     * Stop server
     */
    private fun stopServer() {
        connectionLock.lock()
        try {
            // Stop heartbeat thread
            heartbeatThread?.interrupt()
            heartbeatThread = null

            // Close client connection
            closeClientConnection()

            // Stop accept thread
            acceptThread?.interrupt()
            acceptThread = null

            // Close server socket
            serverSocket?.close()
            serverSocket = null
            serverPort = 0

            partnerInfo = null
            lastHeartbeatReceived = 0

            setConnectionState(ConnectionState.DISCONNECTED)
        } finally {
            connectionLock.unlock()
        }
    }

    /**
     * Cleanup resources
     */
    fun dispose() {
        log.info("Starting cleanup of TCP server manager resources")

        isShutdown.set(true)
        autoReconnect.set(false)

        stopServer()

        // Close thread pool
        executorService.shutdown()
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executorService.shutdownNow()
        }

        log.info("TCP server manager resource cleanup completed")
    }

    // ==================== Status query methods ====================

    fun isConnected(): Boolean = connectionState.get() == ConnectionState.CONNECTED
    fun isAutoReconnect(): Boolean = autoReconnect.get()
    fun isConnecting(): Boolean = connectionState.get() == ConnectionState.CONNECTING
    fun isDisconnected(): Boolean = connectionState.get() == ConnectionState.DISCONNECTED
    fun getConnectionState(): ConnectionState = connectionState.get()

    // Compatibility with old interface
    fun updateMulticastPort() {
        // TCP mode does not need port configuration, automatically selects
        log.info("TCP mode automatically selects port, ignoring port configuration update")
    }

    fun disconnectAndCleanup() {
        stopServer()
    }

    fun restartConnection() {
        log.info("Restarting TCP server")
        stopServer()
        if (autoReconnect.get()) {
            startServer()
        }
    }
}

/**
 * Handshake message data class
 */
data class HandshakeMessage(
    val type: String,
    val projectPath: String,
    val ideType: String,
    val ideName: String,
    val port: Int
)

/**
 * Partner information data class
 */
data class PartnerInfo(
    val ideType: String,
    val projectPath: String,
    val connectedAt: Long
)
