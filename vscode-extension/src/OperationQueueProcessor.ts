import {EditorState, MessageWrapper, MessageSender} from './Type';
import {Logger} from './Logger';
import {LocalIdentifierManager} from './LocalIdentifierManager';

/**
 * Operation queue processor
 * Ensures atomicity and order of operations
 * Includes queue capacity management and operation addition logic
 */
export class OperationQueueProcessor {
    private logger: Logger;
    private messageSender: MessageSender;
    private processingInterval: NodeJS.Timeout | null = null;

    // Internal queue management
    private operationQueue: EditorState[] = [];
    private maxQueueSize = 100;

    // Processing state
    private isShutdown: boolean = false;

    constructor(
        logger: Logger,
        messageSender: MessageSender,
    ) {
        this.logger = logger;
        this.messageSender = messageSender;

        // Automatically start queue processor in constructor
        this.start();
    }

    /**
     * Add operation to queue
     * Includes queue capacity management logic
     */
    addOperation(state: EditorState) {
        if (this.operationQueue.length >= this.maxQueueSize) {
            this.operationQueue.shift();
            this.logger.warn('Operation queue is full, removing oldest operation');
        }

        this.operationQueue.push(state);
        this.logger.info(`Operation pushed to queue: ${state.action} ${state.filePath}, ${state.getCursorLog()}, ${state.getSelectionLog()}`)
    }

    /**
     * Start queue processor
     */
    start() {
        if (!this.isShutdown) {
            this.logger.info('Starting VSCode queue processor');
            this.processingInterval = setInterval(() => {
                this.processQueue();
            }, 100); // Check queue every 100ms
        }
    }

    /**
     * Process operation queue
     */
    async processQueue() {
        if (this.isShutdown) {
            return;
        }

        while (this.operationQueue.length > 0 && !this.isShutdown) {
            try {
                const state = this.operationQueue.shift();
                if (!state) {
                    continue;
                }
                await this.processOperation(state);

                // Avoid overly frequent operations
                await new Promise(resolve => setTimeout(resolve, 50));
            } catch (error) {
                this.logger.warn(`Queue processor error occurred:`, error as Error);
            }
        }
    }

    /**
     * Process single operation
     */
    private async processOperation(state: EditorState) {
        try {
            this.sendStateUpdate(state);
        } catch (error) {
            this.logger.warn('Failed to process operation:', error as Error);
        }
    }

    /**
     * Send state update
     */
    private sendStateUpdate(state: EditorState) {
        const messageWrapper = MessageWrapper.create(
            LocalIdentifierManager.getInstance().identifier,
            state
        );

        const success = this.messageSender.sendMessage(messageWrapper);
        if (success) {
            this.logger.info(`✅ Sent message: ${state.action} ${state.filePath}, ${state.getCursorLog()}, ${state.getSelectionLog()}`)
        } else {
            this.logger.info(`❌ Failed to send message: ${state.action} ${state.filePath}, ${state.getCursorLog()}, ${state.getSelectionLog()}`)
        }
    }


    /**
     * Stop queue processor
     */
    dispose() {
        this.logger.info('Starting to shut down VSCode queue processor');

        this.isShutdown = true;

        if (this.processingInterval) {
            clearInterval(this.processingInterval);
            this.processingInterval = null;
        }

        // Clear queue
        this.operationQueue.length = 0;

        this.logger.info('VSCode queue processor has been shut down');
    }
}
