import * as dgram from 'dgram';
import * as vscode from 'vscode';
import {ConnectionCallback, ConnectionState, MessageWrapper} from './Type';
import {Logger} from './Logger';
import {MessageProcessor} from './MessageProcessor';
import {LocalIdentifierManager} from './LocalIdentifierManager';


/**
 * Multicast manager
 * Responsible for sending and receiving UDP multicast messages, implementing decentralized editor synchronization
 */
export class MulticastManager {
    // === Core Dependencies ===
    private readonly logger: Logger;
    private readonly messageProcessor: MessageProcessor;

    // === Network Configuration ===
    private readonly multicastAddress = '224.0.0.1'; // Local link multicast address, local machine communication only
    private multicastPort: number; // Multicast port (read from configuration)
    private readonly maxMessageSize = 8192; // Maximum message size (8KB)

    // === Connection State ===
    private socket: dgram.Socket | null = null;
    private connectionState: ConnectionState = ConnectionState.DISCONNECTED;
    private autoReconnect = false;
    private connectionCallback: ConnectionCallback | null = null;
    private isShutdown = false;

    // === Timers ===
    private reconnectTimer: NodeJS.Timeout | null = null;
    private cleanupTimer: NodeJS.Timeout | null = null;

    constructor(logger: Logger, messageProcessor: MessageProcessor) {
        this.logger = logger;
        this.messageProcessor = messageProcessor;
        this.multicastPort = vscode.workspace.getConfiguration('vscode-jetbrains-sync').get('port', 3000);

        this.logger.info(`Initializing multicast manager - Address: ${this.multicastAddress}:${this.multicastPort}`);

        this.setupConfigurationListener();
    }

    // ==================== Initialization Related Methods ====================

    /**
     * Set up configuration listener
     */
    private setupConfigurationListener(): void {
        vscode.workspace.onDidChangeConfiguration((event) => {
            if (event.affectsConfiguration('vscode-jetbrains-sync.port')) {
                this.updateMulticastPort();
            }
        });
    }

    /**
     * Handle configuration changes
     */
    private updateMulticastPort(): void {
        const newPort = vscode.workspace.getConfiguration('vscode-jetbrains-sync').get('port', 3000);
        if (newPort !== this.multicastPort) {
            this.logger.info(`Multicast port configuration changed: ${this.multicastPort} -> ${newPort}`);
            this.multicastPort = newPort;

            if (this.autoReconnect) {
                this.restartConnection();
            }
        }
    }

    // ==================== Public Interface Methods ====================

    /**
     * Set connection state callback
     */
    setConnectionCallback(callback: ConnectionCallback): void {
        this.connectionCallback = callback;
    }

    /**
     * Toggle auto-reconnect state
     */
    toggleAutoReconnect(): void {
        this.autoReconnect = !this.autoReconnect;
        this.logger.info(`Multicast sync state toggled to: ${this.autoReconnect ? 'enabled' : 'disabled'}`);

        if (!this.autoReconnect) {
            this.disconnectAndCleanup();
            this.logger.info('Multicast sync disabled');
        } else {
            this.connectMulticast();
            this.logger.info('Multicast sync enabled, starting connection...');
        }
    }

    /**
     * Restart connection
     */
    restartConnection(): void {
        this.logger.info('Manually restarting multicast connection');
        this.disconnectAndCleanup();
        if (this.autoReconnect) {
            this.connectMulticast();
        }
    }

    // ==================== Connection Management Methods ====================

    /**
     * Connect to multicast group
     */
    private connectMulticast(): void {
        if (this.isShutdown || !this.autoReconnect || this.connectionState !== ConnectionState.DISCONNECTED) {
            if (this.connectionState !== ConnectionState.DISCONNECTED) {
                this.logger.info('Connection state is not DISCONNECTED, skipping connection attempt');
            }
            return;
        }

        this.setConnectionState(ConnectionState.CONNECTING);
        this.logger.info('Connecting to multicast group...');

        try {
            this.cleanUp();
            this.createSocket();
            this.bindSocket();
        } catch (error) {
            this.logger.warn('Failed to create multicast connection:', error as Error);
            this.handleConnectionError();
        }
    }

    /**
     * Create UDP socket
     */
    private createSocket(): void {
        this.socket = dgram.createSocket({
            type: 'udp4',
            reuseAddr: true
        });

        this.setupSocketEventHandlers();
    }

    /**
     * Set up socket event handlers
     */
    private setupSocketEventHandlers(): void {
        if (!this.socket) return;

        this.socket.on('error', (error: Error) => {
            this.logger.warn('Multicast socket error:', error);
            this.handleConnectionError();
        });

        this.socket.on('message', (message: Buffer, rinfo: dgram.RemoteInfo) => {
            this.handleReceivedMessage(message.toString('utf8'));
        });

        this.socket.on('listening', () => {
            this.handleSocketListening();
        });
    }

    /**
     * Handle socket listening event
     */
    private handleSocketListening(): void {
        if (!this.socket) return;

        const address = this.socket.address();
        const isLoopback = address.address === '127.0.0.1' || address.address === '::1';
        const addressType = isLoopback ? 'loopback address' : 'non-loopback address';
        this.logger.info(`Multicast socket listening on ${addressType} ${address.address}:${address.port}`);

        this.joinMulticastGroup();
    }

    /**
     * Join multicast group
     */
    private joinMulticastGroup(): void {
        if (!this.socket) return;

        try {
            // Prioritize using loopback interface
            this.socket.addMembership(this.multicastAddress, '127.0.0.1');
            this.setConnectionState(ConnectionState.CONNECTED);
            this.logger.info(`Successfully joined multicast group (loopback interface): ${this.multicastAddress}:${this.multicastPort}`);
        } catch (error) {
            this.logger.warn('Failed to join multicast group (loopback interface), trying without specifying interface:', error as Error);
            this.tryJoinWithDefaultInterface();
        }
    }

    /**
     * Try to join multicast group using default interface
     */
    private tryJoinWithDefaultInterface(): void {
        if (!this.socket) return;

        try {
            this.socket.addMembership(this.multicastAddress);
            this.setConnectionState(ConnectionState.CONNECTED);
            this.logger.info(`Successfully joined multicast group (default interface): ${this.multicastAddress}:${this.multicastPort}`);
        } catch (error) {
            this.logger.warn('Failed to join multicast group completely:', error as Error);
            this.handleConnectionError();
        }
    }

    /**
     * Bind socket to port
     */
    private bindSocket(): void {
        if (!this.socket) return;

        try {
            // Prioritize binding to loopback address
            this.socket.bind(this.multicastPort, '127.0.0.1', () => {
                this.logger.info(`Bound to loopback address port: 127.0.0.1:${this.multicastPort}`);
            });
        } catch (bindError) {
            this.logger.warn('Failed to bind to loopback address, trying to bind to default address:', bindError as Error);
            this.tryBindToDefaultAddress();
        }
    }

    /**
     * Try to bind to default address
     */
    private tryBindToDefaultAddress(): void {
        if (!this.socket) return;

        try {
            this.socket.bind(this.multicastPort, () => {
                this.logger.info(`Bound to default address port: ${this.multicastPort}`);
            });
        } catch (error) {
            this.logger.warn('Failed to bind port completely:', error as Error);
            this.handleConnectionError();
        }
    }

    // ==================== Message Processing Methods ====================

    /**
     * Handle received message
     */
    private handleReceivedMessage(message: string): void {
        try {
            this.messageProcessor.handleMessage(message);
        } catch (error) {
            this.logger.warn('Error occurred while processing received message:', error as Error);
        }
    }


    /**
     * Send message to multicast group
     */
    sendMessage(messageWrapper: MessageWrapper): boolean {
        if (!this.isConnected() || !this.autoReconnect) {
            this.logger.warn(`Currently not connected, discarding message: ${messageWrapper.toJsonString()}`);
            return false;
        }

        try {
            const messageString = messageWrapper.toJsonString();
            const messageBuffer = Buffer.from(messageString, 'utf8');

            if (messageBuffer.length > this.maxMessageSize) {
                this.logger.warn(`Message too large, cannot send: ${messageBuffer.length} bytes`);
                return false;
            }

            this.socket!.send(
                messageBuffer,
                0,
                messageBuffer.length,
                this.multicastPort,
                this.multicastAddress,
                (error: Error | null) => {
                    if (error) {
                        this.logger.warn('Failed to send multicast message:', error);
                        this.handleConnectionError();
                    } else {
                        this.logger.info(`Sent multicast message content: ${messageString}`);
                    }
                }
            );

            return true;

        } catch (error) {
            this.logger.warn('Failed to send multicast message:', error as Error);
            this.handleConnectionError();
            return false;
        }
    }

    // ==================== State Management Methods ====================

    /**
     * Handle connection error
     */
    private handleConnectionError(): void {
        this.setConnectionState(ConnectionState.DISCONNECTED);

        if (this.autoReconnect && !this.isShutdown) {
            this.scheduleReconnect();
        }
    }

    /**
     * Schedule reconnection
     */
    private scheduleReconnect(): void {
        if (this.reconnectTimer) {
            clearTimeout(this.reconnectTimer);
        }

        this.logger.info('Will attempt to reconnect to multicast group in 5 seconds...');

        this.reconnectTimer = setTimeout(() => {
            if (this.autoReconnect && !this.isShutdown) {
                this.connectMulticast();
            }
        }, 5000);
    }

    /**
     * Set connection state and trigger callback
     */
    private setConnectionState(state: ConnectionState): void {
        if (this.connectionState === state) {
            return;
        }

        this.connectionState = state;

        switch (state) {
            case ConnectionState.CONNECTED:
                this.connectionCallback?.onConnected();
                break;
            case ConnectionState.CONNECTING:
                this.connectionCallback?.onReconnecting();
                break;
            case ConnectionState.DISCONNECTED:
                this.connectionCallback?.onDisconnected();
                break;
        }
    }

    // ==================== Resource Cleanup Methods ====================

    /**
     * Disconnect and clean up resources
     */
    disconnectAndCleanup(): void {
        this.cleanUp();
        this.setConnectionState(ConnectionState.DISCONNECTED);
    }

    /**
     * Clean up resources
     */
    private cleanUp(): void {
        // Clear timers
        if (this.reconnectTimer) {
            clearTimeout(this.reconnectTimer);
            this.reconnectTimer = null;
        }

        // Close socket
        if (this.socket) {
            try {
                this.socket.dropMembership(this.multicastAddress);
                this.logger.info('Left multicast group');
            } catch (error) {
                this.logger.warn('Error occurred while leaving multicast group:', error as Error);
            }

            this.socket.close(() => {
                this.logger.info('Multicast socket closed');
            });
            this.socket = null;
        }
    }

    /**
     * Clean up resources
     */
    dispose(): void {
        this.logger.info('Starting to clean up multicast manager resources');

        this.isShutdown = true;
        this.autoReconnect = false;

        // Clean up connection
        this.disconnectAndCleanup();

        // Clear cleanup timer
        if (this.cleanupTimer) {
            clearInterval(this.cleanupTimer);
            this.cleanupTimer = null;
        }

        this.logger.info('Multicast manager resource cleanup completed');
    }

    // ==================== Status Query Methods ====================

    isConnected(): boolean {
        return this.connectionState === ConnectionState.CONNECTED;
    }

    isAutoReconnect(): boolean {
        return this.autoReconnect;
    }

    isConnecting(): boolean {
        return this.connectionState === ConnectionState.CONNECTING;
    }

    isDisconnected(): boolean {
        return this.connectionState === ConnectionState.DISCONNECTED;
    }

    getConnectionState(): ConnectionState {
        return this.connectionState;
    }

}