package com.vscode.jetbrainssync

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Message Processor
 * Responsible for WebSocket message serialization, deserialization and routing
 */
class MessageProcessor(
    private val fileOperationHandler: FileOperationHandler,
    private val localIdentifierManager: LocalIdentifierManager
) {
    private val log: Logger = Logger.getInstance(MessageProcessor::class.java)
    private val gson = Gson()

    private val messageTimeOutMs = 5000

    // Multicast message deduplication related
    private val receivedMessages = mutableMapOf<String, Long>()
    private val maxReceivedMessagesSize = 1000
    private val messageCleanupIntervalMs = 300000 // 5 minutes

    // Scheduled cleanup related
    private val isShutdown = AtomicBoolean(false)
    private val executorService: ExecutorService = Executors.newSingleThreadExecutor()

    init {
        startMessageCleanupTask()
    }

    /**
     * Start message cleanup scheduled task
     */
    private fun startMessageCleanupTask() {
        executorService.submit {
            while (!isShutdown.get()) {
                try {
                    Thread.sleep(60000) // Clean up every minute
                    cleanupOldMessages()
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    log.warn("Error occurred while cleaning messages: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Handle multicast message
     * Contains message parsing, deduplication check, own message filtering and other logic
     */
    fun handleMessage(message: String): Boolean {
        try {
            val messageData = parseMessageData(message)
            if (messageData == null) return false

            // Get local identifier
            val localIdentifier = localIdentifierManager.identifier

            // Check if it's a message sent by oneself
            if (messageData.isOwnMessage(localIdentifier)) {
                log.debug("Ignoring own message")
                return false
            }
            log.info("Received multicast message: $message")

            // Check message deduplication
            if (isDuplicateMessage(messageData)) {
                log.debug("Ignoring duplicate message: ${messageData.messageId}")
                return false
            }

            // Record and process message
            recordMessage(messageData)
            // Process message content
            handleIncomingState(messageData.payload)
            return true
        } catch (e: Exception) {
            log.warn("Error occurred while processing multicast message: ${e.message}", e)
            return false
        }
    }

    /**
     * Parse message data
     */
    private fun parseMessageData(message: String): MessageWrapper? {
        return MessageWrapper.fromJsonString(message)
    }

    /**
     * Check if message is duplicate
     */
    private fun isDuplicateMessage(messageData: MessageWrapper): Boolean {
        return receivedMessages.containsKey(messageData.messageId)
    }

    /**
     * Record message ID
     */
    private fun recordMessage(messageData: MessageWrapper) {
        val currentTime = System.currentTimeMillis()
        receivedMessages[messageData.messageId] = currentTime

        if (receivedMessages.size > maxReceivedMessagesSize) {
            cleanupOldMessages()
        }
    }

    /**
     * Clean up expired message records
     */
    private fun cleanupOldMessages() {
        val currentTime = System.currentTimeMillis()
        val expireTime = currentTime - messageCleanupIntervalMs

        val iterator = receivedMessages.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value < expireTime) {
                iterator.remove()
            }
        }

        log.debug("Cleaned up expired message records, remaining: ${receivedMessages.size}")
    }

    /**
     * Handle received message (compatible with old interface)
     */
    fun handleIncomingMessage(message: String) {
        try {
            log.info("Received message: $message")
            val state = gson.fromJson(message, EditorState::class.java)
            log.info("\uD83C\uDF55Parsing message: ${state.action} ${state.filePath}, ${state.getCursorLog()}, ${state.getSelectionLog()}")

            // Validate message validity
            if (!isValidMessage(state)) {
                return
            }

            // Route to file operation handler
            fileOperationHandler.handleIncomingState(state)

        } catch (e: Exception) {
            log.warn("Failed to parse message: ${e.message}", e)
        }
    }

    /**
     * Handle received state (new interface)
     */
    private fun handleIncomingState(state: EditorState) {
        try {
            log.info("\uD83C\uDF55Parsing message: ${state.action} ${state.filePath}, ${state.getCursorLog()}, ${state.getSelectionLog()}")

            // Validate message validity
            if (!isValidMessage(state)) {
                return
            }

            // Route to file operation handler
            fileOperationHandler.handleIncomingState(state)

        } catch (e: Exception) {
            log.warn("Failed to process state: ${e.message}", e)
        }
    }

    /**
     * Validate message validity
     */
    private fun isValidMessage(state: EditorState): Boolean {
//        // Ignore messages from oneself
//        if (state.source == SourceType.JETBRAINS) {
//            return false
//        }

        // Only process messages from active IDE
        if (!state.isActive) {
            log.info("Ignoring message from inactive VSCode")
            return false
        }

        // Check message timeliness
        val messageTime = parseTimestamp(state.timestamp)
        val currentTime = System.currentTimeMillis()
        if (currentTime - messageTime > messageTimeOutMs) { // 5 seconds expiration
            log.info("Ignoring expired message, time difference: ${currentTime - messageTime}ms")
            return false
        }

        return true
    }
}
