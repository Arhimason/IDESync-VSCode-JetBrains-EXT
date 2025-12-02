package com.vscode.jetbrainssync

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.net.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

// ==================== Internal Data Classes ====================


/**
 * Multicast Manager
 * Responsible for sending and receiving UDP multicast messages, implementing decentralized editor synchronization
 */
class MulticastManager(
    private val project: Project,
    private val messageProcessor: MessageProcessor,
) {
    private val log: Logger = Logger.getInstance(MulticastManager::class.java)

    // ==================== Configuration Constants ====================
    private val multicastAddress = "224.0.0.1" // Local link multicast address, local machine communication only
    private var multicastPort: Int // Multicast port (read from configuration)
    private val maxMessageSize = 8192 // Maximum message size (8KB)

    // ==================== Network Components ====================
    private var multicastSocket: MulticastSocket? = null
    private var networkInterface: NetworkInterface? = null
    private var group: InetSocketAddress? = null

    // ==================== State Management ====================
    private val connectionState = AtomicReference(ConnectionState.DISCONNECTED)
    private val autoReconnect = AtomicBoolean(false)
    private var connectionCallback: ConnectionCallback? = null

    // ==================== Thread Management ====================
    private val executorService: ExecutorService = Executors.newCachedThreadPool { r ->
        val thread = Thread(r, "Multicast-Manager-Worker")
        thread.isDaemon = true
        thread
    }
    private val connectionLock = ReentrantLock()
    private val isShutdown = AtomicBoolean(false)
    private var receiverThread: Thread? = null

    init {
        // Read multicast port from configuration
        val settings = VSCodeJetBrainsSyncSettings.getInstance(project)
        multicastPort = if (settings.state.useCustomPort) {
            settings.state.customPort
        } else {
            3000 // Default port for multicast when auto-detection is used
        }
        log.info("Initializing multicast manager - Address: $multicastAddress:$multicastPort")
    }

    // ==================== Initialization Related Methods ====================

    /**
     * Update multicast port configuration
     */
    fun updateMulticastPort() {
        val settings = VSCodeJetBrainsSyncSettings.getInstance(project)
        val newPort = if (settings.state.useCustomPort) {
            settings.state.customPort
        } else {
            3000 // Default port for multicast when auto-detection is used
        }
        
        if (newPort != multicastPort) {
            log.info("Multicast port configuration changed: $multicastPort -> $newPort")

            // Update port
            multicastPort = newPort

            // If auto-reconnect is currently enabled, restart connection
            if (this.autoReconnect.get()) {
                this.restartConnection();
            }
        }
    }

    // ==================== Public Interface Methods ====================

    /**
     * Set connection state callback
     */
    fun setConnectionCallback(callback: ConnectionCallback) {
        this.connectionCallback = callback
    }

    /**
     * Toggle auto-reconnect state
     */
    fun toggleAutoReconnect() {
        connectionLock.lock()
        try {
            val currentState = autoReconnect.get()
            val newState = !currentState

            if (!autoReconnect.compareAndSet(currentState, newState)) {
                log.warn("Auto-reconnect state has been modified by another thread, operation cancelled")
                return
            }

            log.info("Multicast sync state toggled to: ${if (newState) "enabled" else "disabled"}")

            if (!newState) {
                disconnectAndCleanup()
                log.info("Multicast sync has been disabled")
            } else {
                connectMulticast()
                log.info("Multicast sync has been enabled, starting connection...")
            }
        } finally {
            connectionLock.unlock()
        }
    }

    /**
     * Restart connection
     */
    fun restartConnection() {
        log.info("Manually restarting multicast connection")
        disconnectAndCleanup()
        if (autoReconnect.get()) {
            connectMulticast()
        }
    }

    // ==================== Connection Management Methods ====================

    /**
     * Connect to multicast group
     */
    private fun connectMulticast() {
        if (isShutdown.get() || !autoReconnect.get() || connectionState.get() != ConnectionState.DISCONNECTED) {
            return
        }

        executorService.submit {
            try {
                if (!autoReconnect.get() || isShutdown.get()) {
                    return@submit
                }

                if (!connectionState.compareAndSet(ConnectionState.DISCONNECTED, ConnectionState.CONNECTING)) {
                    log.info("Connection state is not DISCONNECTED, skipping connection attempt")
                    return@submit
                }

                setConnectionState(ConnectionState.CONNECTING)
                log.info("Connecting to multicast group...")

                // Clean up existing connection
                cleanUp()

                // Find available network interface
                networkInterface = findAvailableNetworkInterface()
                if (networkInterface == null) {
                    throw RuntimeException("No available network interface found")
                }

                log.info("Using network interface: ${networkInterface!!.displayName}")

                // Create multicast socket
                multicastSocket = MulticastSocket(multicastPort)
                multicastSocket!!.reuseAddress = true
                multicastSocket!!.networkInterface = networkInterface

                // Join multicast group
                group = InetSocketAddress(InetAddress.getByName(multicastAddress), multicastPort)
                multicastSocket!!.joinGroup(group, networkInterface)

                setConnectionState(ConnectionState.CONNECTED)
                log.info("Successfully joined multicast group: $multicastAddress:$multicastPort")

                // Start message receiver thread
                startMessageReceiver()

            } catch (e: Exception) {
                log.warn("Failed to connect to multicast group: ${e.message}", e)
                handleConnectionError()
            }
        }
    }

    /**
     * Find available network interface
     */
    private fun findAvailableNetworkInterface(): NetworkInterface? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (!interfaces.hasMoreElements()) {
                log.error("No network interfaces found in the system")
                return null
            }

            val availableInterfaces = mutableListOf<String>()
            
            // Prioritize loopback interface to ensure local machine communication only
            for (netInterface in interfaces) {
                val interfaceInfo = "Interface: ${netInterface.displayName}, Loopback: ${netInterface.isLoopback}, Up: ${netInterface.isUp}, Multicast: ${netInterface.supportsMulticast()}"
                availableInterfaces.add(interfaceInfo)
                
                if (netInterface.isLoopback &&
                    netInterface.isUp &&
                    netInterface.supportsMulticast()
                ) {
                    log.info("Using loopback network interface: ${netInterface.displayName}")
                    return netInterface
                }
            }

            log.warn("Loopback interface not available, available interface list: ${availableInterfaces.joinToString("; ")}")
            
            // Reset enumerator
            val interfaces2 = NetworkInterface.getNetworkInterfaces()
            
            // If loopback interface is not available, try using any available interface as fallback
            for (netInterface in interfaces2) {
                if (netInterface.isUp && netInterface.supportsMulticast()) {
                    // More relaxed address check - try using as long as there's an IP address
                    val addresses = netInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        // Exclude IPv6 addresses (if multicast address is IPv4)
                        if (address is Inet4Address) {
                            log.info("Using network interface: ${netInterface.displayName}, Address: ${address.hostAddress}")
                            return netInterface
                        }
                    }
                }
            }

            // Final fallback: try using any enabled interface even if it doesn't support multicast
            log.warn("No multicast-supporting interface found, trying any enabled interface")
            val interfaces3 = NetworkInterface.getNetworkInterfaces()
            for (netInterface in interfaces3) {
                if (netInterface.isUp && netInterface.inetAddresses.hasMoreElements()) {
                    log.warn("Fallback using network interface: ${netInterface.displayName} (may not support multicast)")
                    return netInterface
                }
            }

            log.error("No available network interface found, checked system interfaces: ${availableInterfaces.joinToString("; ")}")
            return null

        } catch (e: Exception) {
            log.warn("Error occurred while finding network interface: ${e.message}", e)
            return null
        }
    }

    /**
     * Start message receiver thread
     */
    private fun startMessageReceiver() {
        receiverThread = thread(name = "Multicast-Message-Receiver") {
            val buffer = ByteArray(maxMessageSize)

            while (!isShutdown.get() && isConnected()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    multicastSocket?.receive(packet)

                    if (packet.length > 0) {
                        val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        handleReceivedMessage(message)
                    }

                } catch (e: SocketTimeoutException) {
                    // Timeout is normal, continue loop
                    continue
                } catch (e: Exception) {
                    if (!isShutdown.get()) {
                        log.warn("Error occurred while receiving multicast message: ${e.message}", e)
                        handleConnectionError()
                        break
                    }
                }
            }

            log.info("Message receiver thread has exited")
        }
    }

    // ==================== Message Processing Methods ====================

    /**
     * Handle received message
     */
    private fun handleReceivedMessage(message: String) {
        try {
            messageProcessor.handleMessage(message)
        } catch (e: Exception) {
            log.warn("Error occurred while handling received message: ${e.message}", e)
        }
    }


    /**
     * Send message to multicast group
     */
    fun sendMessage(messageWrapper: MessageWrapper): Boolean {
        if (!isConnected() || !autoReconnect.get()) {
            log.warn("Currently not connected, discarding message: ${messageWrapper.toJsonString()}")
            return false
        }

        return try {
            val messageString = messageWrapper.toJsonString()
            val messageBytes = messageString.toByteArray(Charsets.UTF_8)

            if (messageBytes.size > maxMessageSize) {
                log.warn("Message too large, cannot send: ${messageBytes.size} bytes")
                return false
            }

            val packet = DatagramPacket(
                messageBytes,
                messageBytes.size,
                InetAddress.getByName(multicastAddress),
                multicastPort
            )

            multicastSocket?.send(packet)
            log.info("Sent multicast message content: $messageString")
            true

        } catch (e: Exception) {
            log.warn("Failed to send multicast message: ${e.message}", e)
            handleConnectionError()
            false
        }
    }

    // ==================== State Management Methods ====================

    /**
     * Handle connection error
     */
    private fun handleConnectionError() {
        setConnectionState(ConnectionState.DISCONNECTED)

        if (autoReconnect.get() && !isShutdown.get()) {
            executorService.submit {
                try {
                    Thread.sleep(5000) // Wait 5 seconds before reconnecting
                    if (autoReconnect.get() && !isShutdown.get()) {
                        connectMulticast()
                    }
                } catch (e: InterruptedException) {
                    // Thread was interrupted, exit
                }
            }
        }
    }

    /**
     * Set connection state and trigger callback
     */
    private fun setConnectionState(state: ConnectionState) {
        if (connectionState.get() == state) {
            return
        }

        connectionState.set(state)

        when (state) {
            ConnectionState.CONNECTED -> connectionCallback?.onConnected()
            ConnectionState.CONNECTING -> connectionCallback?.onReconnecting()
            ConnectionState.DISCONNECTED -> connectionCallback?.onDisconnected()
        }
    }

    // ==================== Resource Cleanup Methods ====================

    /**
     * Disconnect and clean up resources
     */
    fun disconnectAndCleanup() {
        cleanUp()
        setConnectionState(ConnectionState.DISCONNECTED)
    }

    /**
     * Clean up resources
     */
    private fun cleanUp() {
        connectionLock.lock()
        try {
            // Stop receiver thread
            receiverThread?.interrupt()
            receiverThread = null

            // Leave multicast group
            if (multicastSocket != null && group != null && networkInterface != null) {
                try {
                    multicastSocket!!.leaveGroup(group, networkInterface)
                    log.info("Left multicast group")
                } catch (e: Exception) {
                    log.warn("Error occurred while leaving multicast group: ${e.message}")
                }
            }

            // Close socket
            multicastSocket?.close()
            multicastSocket = null
            group = null
            networkInterface = null

        } finally {
            connectionLock.unlock()
        }
    }

    /**
     * Clean up resources
     */
    fun dispose() {
        log.info("Starting to clean up multicast manager resources")

        isShutdown.set(true)
        autoReconnect.set(false)

        // Clean up connection
        disconnectAndCleanup()

        // Close thread pool
        executorService.shutdown()
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executorService.shutdownNow()
        }

        log.info("Multicast manager resource cleanup completed")
    }

    // ==================== Status Query Methods ====================

    fun isConnected(): Boolean = connectionState.get() == ConnectionState.CONNECTED
    fun isAutoReconnect(): Boolean = autoReconnect.get()
    fun isConnecting(): Boolean = connectionState.get() == ConnectionState.CONNECTING
    fun isDisconnected(): Boolean = connectionState.get() == ConnectionState.DISCONNECTED
    fun getConnectionState(): ConnectionState = connectionState.get()
} 