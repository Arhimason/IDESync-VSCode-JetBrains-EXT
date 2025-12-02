package com.vscode.jetbrainssync

import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Operation queue processor
 * Responsible for handling asynchronous operation queues, ensuring atomicity and order of file operations
 * Includes queue capacity management and operation addition logic
 */
class OperationQueueProcessor(
    private val messageSender: MessageSender,
    private val localIdentifierManager: LocalIdentifierManager
) {
    private val log: Logger = Logger.getInstance(OperationQueueProcessor::class.java)

    // Internal queue management
    private val operationQueue = LinkedBlockingQueue<EditorState>()
    private val maxQueueSize = 100

    // Thread pool
    private val executorService: ExecutorService = Executors.newSingleThreadExecutor { r ->
        val thread = Thread(r, "Operation-Queue-Processor")
        thread.isDaemon = true
        thread
    }

    // Processing state
    private val isShutdown = AtomicBoolean(false)

    init {
        // Automatically start queue processor in constructor
        start()
    }

    /**
     * Add operation to queue
     * Includes queue capacity management logic
     */
    fun addOperation(state: EditorState) {
        if (operationQueue.size >= maxQueueSize) {
            operationQueue.poll()
            log.warn("Operation queue is full, removing oldest operation")
        }

        operationQueue.offer(state)
        log.info("Operation pushed to queue: ${state.action} ${state.filePath}, ${state.getCursorLog()}, ${state.getSelectionLog()}")
    }

    /**
     * Start queue processor
     */
    fun start() {
        log.info("Starting operation queue processor")
        executorService.submit(this::processQueue)
    }

    /**
     * Queue processing main loop
     */
    private fun processQueue() {
        while (!isShutdown.get() && !Thread.currentThread().isInterrupted) {
            try {
                // Block and wait for tasks in queue
                val state = operationQueue.take()
                processOperation(state)

                // Avoid overly frequent operations
                Thread.sleep(50)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                log.warn("Queue processor error: ${e.message}", e)
            }
        }
    }

    /**
     * Process single operation
     */
    private fun processOperation(state: EditorState) {
        try {
            sendStateUpdate(state)
        } catch (e: Exception) {
            log.warn("Failed to process operation: ${e.message}", e)
        }
    }

    /**
     * Send state update
     */
    private fun sendStateUpdate(state: EditorState) {
        val messageWrapper = MessageWrapper.create(
            localIdentifierManager.generateMessageId(),
            localIdentifierManager.identifier,
            state
        )
        val success = messageSender.sendMessage(messageWrapper)
        if (success) {
            log.info("✅ Message sent: ${state.action} ${state.filePath}, ${state.getCursorLog()}, ${state.getSelectionLog()}")
        } else {
            log.info("❌ Failed to send message: ${state.action} ${state.filePath}, ${state.getCursorLog()}, ${state.getSelectionLog()}")
        }
    }


    /**
     * Stop processor
     */
    fun dispose() {
        log.info("Starting to shutdown operation queue processor")

        isShutdown.set(true)
        executorService.shutdown()

        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executorService.shutdownNow()
        }

        // Clear queue
        operationQueue.clear()

        log.info("Operation queue processor shutdown completed")
    }
}
