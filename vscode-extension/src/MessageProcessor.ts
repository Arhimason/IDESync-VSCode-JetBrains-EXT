import {EditorState, MessageWrapper, parseTimestamp} from './Type';
import {Logger} from './Logger';
import {FileOperationHandler} from './FileOperationHandler';
import {LocalIdentifierManager} from './LocalIdentifierManager';

/**
 * Message Processor
 * Responsible for message serialization and deserialization
 */
export class MessageProcessor {
    private logger: Logger;
    private fileOperationHandler: FileOperationHandler;
    private readonly messageTimeoutMs = 5000;

    // Multicast message deduplication related
    private receivedMessages = new Map<string, number>();
    private readonly maxReceivedMessagesSize = 1000;
    private readonly messageCleanupIntervalMs = 300000; // 5 minutes

    // Scheduled cleanup related
    private isShutdown = false;
    private cleanupTimer: NodeJS.Timeout | null = null;

    constructor(logger: Logger, fileOperationHandler: FileOperationHandler) {
        this.logger = logger;
        this.fileOperationHandler = fileOperationHandler;
        this.startMessageCleanupTask();
    }

    /**
     * Start message cleanup scheduled task
     */
    private startMessageCleanupTask(): void {
        this.cleanupTimer = setInterval(() => {
            if (!this.isShutdown) {
                this.cleanupOldMessages();
            }
        }, 60000); // Clean up every minute
    }

    /**
     * Stop message cleanup scheduled task
     */
    public stopMessageCleanupTask(): void {
        this.isShutdown = true;
        if (this.cleanupTimer) {
            clearInterval(this.cleanupTimer);
            this.cleanupTimer = null;
        }
    }

    /**
     * Handle multicast message
     * Contains message parsing, deduplication check, own message filtering and other logic
     */
    handleMessage(message: string): boolean {
        try {
            const messageData = this.parseMessageData(message);
            if (!messageData) return false;

            // Get local identifier
            const localIdentifier = LocalIdentifierManager.getInstance().identifier;

            // Check if it's a message sent by oneself
            if (messageData.isOwnMessage(localIdentifier)) {
                this.logger.debug('Ignoring own message');
                return false;
            }
            this.logger.info(`Received multicast message: ${message}`);

            // Check message deduplication
            if (this.isDuplicateMessage(messageData)) {
                this.logger.debug(`Ignoring duplicate message: ${messageData.messageId}`);
                return false;
            }

            // Record and process message
            this.recordMessage(messageData);
            // Process message content
            this.handleIncomingState(messageData.payload);
            return true;
        } catch (error) {
            this.logger.warn('Error occurred while processing multicast message:', error as Error);
            return false;
        }
    }

    /**
     * Parse message data
     */
    private parseMessageData(message: string): MessageWrapper | null {
        return MessageWrapper.fromJsonString(message);
    }

    /**
     * Check if message is duplicate
     */
    private isDuplicateMessage(messageData: MessageWrapper): boolean {
        return this.receivedMessages.has(messageData.messageId);
    }

    /**
     * Record message ID
     */
    private recordMessage(messageData: MessageWrapper): void {
        this.receivedMessages.set(messageData.messageId, Date.now());

        if (this.receivedMessages.size > this.maxReceivedMessagesSize) {
            this.cleanupOldMessages();
        }
    }


    /**
     * Clean up expired message records
     */
    private cleanupOldMessages(): void {
        const currentTime = Date.now();
        const expireTime = currentTime - this.messageCleanupIntervalMs;

        for (const [messageId, timestamp] of this.receivedMessages.entries()) {
            if (timestamp < expireTime) {
                this.receivedMessages.delete(messageId);
            }
        }

        this.logger.debug(`Cleaned up expired message records, remaining: ${this.receivedMessages.size}`);
    }

    /**
     * Handle received message (compatible with old interface)
     */
    async handleIncomingMessage(message: string): Promise<void> {
        try {
            this.logger.info(`Received message: ${message}`);
            const rawData = JSON.parse(message);
            const state = this.deserializeEditorState(rawData);
            this.logger.info(`🍕Parsing message: ${state.action} ${state.filePath}, ${state.getCursorLog()}, ${state.getSelectionLog()}`)

            // Validate message validity
            if (!this.isValidMessage(state)) {
                return;
            }

            // Route to file operation handler
            await this.fileOperationHandler.handleIncomingState(state)

        } catch (error) {
            this.logger.warn(`Failed to parse message: `, error as Error);
        }
    }

    /**
     * Handle received state (new interface)
     */
    private async handleIncomingState(state: EditorState): Promise<void> {
        try {
            this.logger.info(`🍕Parsing message: ${state.action} ${state.filePath}, ${state.getCursorLog()}, ${state.getSelectionLog()}`)

            // Validate message validity
            if (!this.isValidMessage(state)) {
                return;
            }

            // Route to file operation handler
            await this.fileOperationHandler.handleIncomingState(state)

        } catch (error) {
            this.logger.warn(`Failed to process state: `, error as Error);
        }
    }

    /**
     * Deserialize JSON object to EditorState instance
     */
    private deserializeEditorState(rawData: any): EditorState {
        return new EditorState(
            rawData.action,
            rawData.filePath,
            rawData.line,
            rawData.column,
            rawData.source,
            rawData.isActive,
            rawData.timestamp,
            rawData.openedFiles,
            rawData.selectionStartLine,
            rawData.selectionStartColumn,
            rawData.selectionEndLine,
            rawData.selectionEndColumn
        );
    }

    /**
     * Validate message validity
     */
    private isValidMessage(state: EditorState): boolean {
        // // Ignore messages from oneself
        // if (state.source === SourceType.VSCODE) {
        //     return false;
        // }

        // Only process messages from active IDE
        if (!state.isActive) {
            this.logger.info('Ignoring message from inactive JetBrains IDE');
            return false;
        }

        // Check message timeliness
        const messageTime = parseTimestamp(state.timestamp);
        const currentTime = Date.now();
        if (currentTime - messageTime > this.messageTimeoutMs) { // 5 seconds expiration
            this.logger.info(`Ignoring expired message, time difference: ${currentTime - messageTime}ms`);
            return false;
        }

        return true;
    }
}
