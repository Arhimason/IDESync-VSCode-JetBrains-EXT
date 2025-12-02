import * as net from 'net';
import * as vscode from 'vscode';
import {ConnectionCallback, ConnectionState, MessageWrapper, MessageSender} from './Type';
import {Logger} from './Logger';
import {MessageProcessor} from './MessageProcessor';

/**
 * Handshake message interface
 */
interface HandshakeMessage {
    type: string;
    projectPath: string;
    ideType: string;
    ideName: string;
    port: number;
}

/**
 * Partner information interface
 */
interface PartnerInfo {
    ideType: string;
    ideName: string;
    projectPath: string;
    port: number;
    connectedAt: number;
}

/**
 * TCP Client Manager
 * VSCode acts as client, scans and connects to JetBrains server
 * Supports multiple projects running simultaneously
 */
export class TcpClientManager implements MessageSender {
    // === Core Dependencies ===
    private readonly logger: Logger;
    private readonly messageProcessor: MessageProcessor;

    // === Network Configuration ===
    private readonly portRangeStart = 3000;
    private readonly portRangeEnd = 4000;
    private readonly scanTimeoutMs = 500;    // Timeout for scanning each port
    private readonly rescanIntervalMs = 5000; // Rescan interval
    private readonly heartbeatIntervalMs = 2000; // Heartbeat interval
    private readonly heartbeatTimeoutMs = 6000;  // Heartbeat timeout

    // === Connection State ===
    private socket: net.Socket | null = null;
    private connectionState: ConnectionState = ConnectionState.DISCONNECTED;
    private autoReconnect = false;
    private connectionCallback: ConnectionCallback | null = null;
    private isShutdown = false;
    private partnerInfo: PartnerInfo | null = null;
    private lastHeartbeatReceived = 0;

    // === Buffer ===
    private messageBuffer = '';

    // === Timers ===
    private rescanTimer: NodeJS.Timeout | null = null;
    private heartbeatTimer: NodeJS.Timeout | null = null;
    private heartbeatCheckTimer: NodeJS.Timeout | null = null;

    constructor(logger: Logger, messageProcessor: MessageProcessor) {
        this.logger = logger;
        this.messageProcessor = messageProcessor;

        this.logger.info('Initializing TCP client manager');
    }

    // ==================== Public Interface Methods ====================

    /**
     * Set connection state callback
     */
    setConnectionCallback(callback: ConnectionCallback): void {
        this.connectionCallback = callback;
    }

    /**
     * Toggle auto reconnect state (start/stop client)
     */
    toggleAutoReconnect(): void {
        this.autoReconnect = !this.autoReconnect;
        this.logger.info(`TCP client state changed to: ${this.autoReconnect ? 'enabled' : 'disabled'}`);

        if (!this.autoReconnect) {
            this.stopClient();
            this.logger.info('TCP client has been closed');
        } else {
            this.startClient();
            this.logger.info('TCP client has been enabled, starting to scan for servers...');
        }
    }

    /**
     * Get partner info
     */
    getPartnerInfo(): PartnerInfo | null {
        return this.partnerInfo;
    }

    /**
     * Get workspace path
     */
    private getWorkspacePath(): string {
        const workspaceFolders = vscode.workspace.workspaceFolders;
        if (!workspaceFolders || workspaceFolders.length === 0) {
            return '';
        }
        return workspaceFolders[0].uri.fsPath;
    }

    // ==================== Client Management Methods ====================

    /**
     * Start client
     */
    private startClient(): void {
        if (this.isShutdown || !this.autoReconnect) {
            return;
        }

        this.setConnectionState(ConnectionState.CONNECTING);
        this.scanAndConnect();
    }

    /**
     * Scan ports and connect
     */
    private async scanAndConnect(): Promise<void> {
        if (this.isShutdown || !this.autoReconnect) {
            return;
        }

        const myPath = this.getWorkspacePath();
        if (!myPath) {
            this.logger.warn('Unable to get workspace path');
            this.scheduleRescan();
            return;
        }

        // Get port configuration
        const config = vscode.workspace.getConfiguration('vscode-jetbrains-sync');
        const useCustomPort = config.get<boolean>('useCustomPort', false);
        const customPort = config.get<number>('customPort', 3000);

        this.logger.info(`Starting to scan for servers... (Workspace: ${myPath})`);

        // If custom port is enabled, try it first
        if (useCustomPort && customPort >= 1024 && customPort <= 65535) {
            if (this.isShutdown || !this.autoReconnect || this.connectionState === ConnectionState.CONNECTED) {
                return;
            }
            
            this.logger.info(`Trying custom port: ${customPort}`);
            const connected = await this.tryConnect(customPort, myPath);
            if (connected) {
                return;
            }
            this.logger.warn(`Custom port ${customPort} not available, falling back to automatic scan`);
        }

        // Automatic port scanning
        for (let port = this.portRangeStart; port <= this.portRangeEnd; port++) {
            if (this.isShutdown || !this.autoReconnect || this.connectionState === ConnectionState.CONNECTED) {
                return;
            }

            const match = await this.tryConnect(port, myPath);
            if (match) {
                this.logger.info(`Successfully connected to JetBrains server (Port: ${port})`);
                return;
            }
        }

        this.logger.info('No matching JetBrains server found, will retry in 5 seconds...');
        this.scheduleRescan();
    }

    /**
     * Try to connect to specified port
     */
    private async tryConnect(port: number, myPath: string): Promise<boolean> {
        return new Promise((resolve) => {
            const socket = new net.Socket();
            socket.setTimeout(this.scanTimeoutMs);

            let resolved = false;
            const cleanup = () => {
                if (!resolved) {
                    resolved = true;
                    socket.destroy();
                    resolve(false);
                }
            };

            socket.connect(port, '127.0.0.1', () => {
                // Wait for server to send handshake message
                socket.once('data', (data) => {
                    try {
                        const message = data.toString().trim();
                        const handshake = JSON.parse(message) as HandshakeMessage;

                        if (handshake.type === 'HANDSHAKE' && this.pathsMatch(handshake.projectPath, myPath)) {
                            // Match found, keep connection
                            resolved = true;
                            this.handleSuccessfulConnection(socket, handshake, myPath);
                            resolve(true);
                        } else {
                            cleanup();
                        }
                    } catch {
                        cleanup();
                    }
                });
            });

            socket.on('error', cleanup);
            socket.on('timeout', cleanup);
        });
    }

    /**
     * Handle successful connection
     */
    private handleSuccessfulConnection(socket: net.Socket, handshake: HandshakeMessage, myPath: string): void {
        this.socket = socket;
        this.partnerInfo = {
            ideType: handshake.ideType,
            ideName: handshake.ideName,
            projectPath: handshake.projectPath,
            port: handshake.port,
            connectedAt: Date.now()
        };
        this.lastHeartbeatReceived = Date.now();

        // Send handshake acknowledgment
        this.sendHandshakeAck(myPath);

        // Set up message handler
        this.setupMessageHandler(socket);

        // Start heartbeat
        this.startHeartbeat();

        this.setConnectionState(ConnectionState.CONNECTED);
        this.logger.info(`Connected to ${handshake.ideName} (Project: ${handshake.projectPath})`);
    }

    /**
     * Send handshake acknowledgment
     */
    private sendHandshakeAck(myPath: string): void {
        const ack = {
            type: 'HANDSHAKE_ACK',
            projectPath: myPath,
            ideType: 'vscode',
            ideName: vscode.env.appName
        };
        this.socket?.write(JSON.stringify(ack) + '\n');
        this.logger.info('Handshake acknowledgment sent');
    }

    /**
     * Set message handler
     */
    private setupMessageHandler(socket: net.Socket): void {
        socket.on('data', (data) => {
            this.messageBuffer += data.toString();

            // Handle possible multiple messages (separated by newline)
            const lines = this.messageBuffer.split('\n');
            this.messageBuffer = lines.pop() || ''; // Keep unfinished part

            for (const line of lines) {
                if (line.trim()) {
                    this.handleReceivedMessage(line.trim());
                }
            }
        });

        socket.on('close', () => {
            this.handleDisconnected();
        });

        socket.on('error', (error) => {
            this.logger.warn(`Socket error: ${error.message}`);
            this.handleDisconnected();
        });
    }

    /**
     * Handle received message
     */
    private handleReceivedMessage(message: string): void {
        try {
            const json = JSON.parse(message);
            const type = json.type;

            switch (type) {
                case 'HEARTBEAT':
                    this.handleHeartbeat();
                    break;
                case 'HEARTBEAT_ACK':
                    this.handleHeartbeatAck();
                    break;
                default:
                    // Ordinary synchronization message
                    this.messageProcessor.handleMessage(message);
            }
        } catch (error) {
            this.logger.warn(`Error handling message: ${error}`);
        }
    }

    /**
     * Handle heartbeat message
     */
    private handleHeartbeat(): void {
        this.lastHeartbeatReceived = Date.now();
        // Send heartbeat acknowledgment
        const ack = {
            type: 'HEARTBEAT_ACK',
            timestamp: Date.now()
        };
        this.socket?.write(JSON.stringify(ack) + '\n');
    }

    /**
     * Handle heartbeat acknowledgment
     */
    private handleHeartbeatAck(): void {
        this.lastHeartbeatReceived = Date.now();
    }

    /**
     * Start heartbeat
     */
    private startHeartbeat(): void {
        // Send heartbeat
        this.heartbeatTimer = setInterval(() => {
            if (this.socket && this.connectionState === ConnectionState.CONNECTED) {
                const heartbeat = {
                    type: 'HEARTBEAT',
                    timestamp: Date.now(),
                    projectPath: this.getWorkspacePath()
                };
                this.socket.write(JSON.stringify(heartbeat) + '\n');
            }
        }, this.heartbeatIntervalMs);

        // Check heartbeat timeout
        this.heartbeatCheckTimer = setInterval(() => {
            if (this.lastHeartbeatReceived > 0 &&
                Date.now() - this.lastHeartbeatReceived > this.heartbeatTimeoutMs) {
                this.logger.warn('Heartbeat timeout, server may have disconnected');
                this.handleDisconnected();
            }
        }, this.heartbeatIntervalMs);
    }

    /**
     * Stop heartbeat
     */
    private stopHeartbeat(): void {
        if (this.heartbeatTimer) {
            clearInterval(this.heartbeatTimer);
            this.heartbeatTimer = null;
        }
        if (this.heartbeatCheckTimer) {
            clearInterval(this.heartbeatCheckTimer);
            this.heartbeatCheckTimer = null;
        }
    }

    /**
     * Check if paths match
     */
    private pathsMatch(path1: string, path2: string): boolean {
        const norm1 = this.normalizePath(path1);
        const norm2 = this.normalizePath(path2);
        return norm1 === norm2 || norm1.startsWith(norm2) || norm2.startsWith(norm1);
    }

    /**
     * Normalize path
     */
    private normalizePath(path: string): string {
        return path.replace(/\\/g, '/').toLowerCase().replace(/\/+$/, '');
    }

    /**
     * Send message
     */
    sendMessage(messageWrapper: MessageWrapper): boolean {
        if (!this.isConnected() || !this.socket) {
            this.logger.warn('Not connected, discarding message');
            return false;
        }

        try {
            const messageString = messageWrapper.toJsonString();
            this.socket.write(messageString + '\n');
            this.logger.info(`Sent message: ${messageString}`);
            return true;
        } catch (error) {
            this.logger.warn(`Failed to send message: ${error}`);
            this.handleDisconnected();
            return false;
        }
    }

    // ==================== Connection Management Methods ====================

    /**
     * Handle disconnection
     */
    private handleDisconnected(): void {
        this.stopHeartbeat();
        this.closeConnection();
        this.partnerInfo = null;
        this.lastHeartbeatReceived = 0;
        this.messageBuffer = '';

        if (this.autoReconnect && !this.isShutdown) {
            this.setConnectionState(ConnectionState.CONNECTING);
            this.logger.info('Connection lost, will rescan for servers...');
            this.scheduleRescan();
        } else {
            this.setConnectionState(ConnectionState.DISCONNECTED);
        }
    }

    /**
     * Close connection
     */
    private closeConnection(): void {
        if (this.socket) {
            this.socket.destroy();
            this.socket = null;
        }
    }

    /**
     * Schedule rescan
     */
    private scheduleRescan(): void {
        if (this.rescanTimer) {
            clearTimeout(this.rescanTimer);
        }

        this.rescanTimer = setTimeout(() => {
            if (this.autoReconnect && !this.isShutdown) {
                this.scanAndConnect();
            }
        }, this.rescanIntervalMs);
    }

    /**
     * Set connection state and trigger callback
     */
    private setConnectionState(state: ConnectionState): void {
        if (this.connectionState === state) {
            return;
        }

        const oldState = this.connectionState;
        this.connectionState = state;
        this.logger.info(`Connection state changed: ${oldState} -> ${state}`);

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

    /**
     * Stop client
     */
    private stopClient(): void {
        this.stopHeartbeat();
        this.closeConnection();

        if (this.rescanTimer) {
            clearTimeout(this.rescanTimer);
            this.rescanTimer = null;
        }

        this.partnerInfo = null;
        this.lastHeartbeatReceived = 0;
        this.messageBuffer = '';

        this.setConnectionState(ConnectionState.DISCONNECTED);
    }

    /**
     * Cleanup resources
     */
    dispose(): void {
        this.logger.info('Starting cleanup of TCP client manager resources');

        this.isShutdown = true;
        this.autoReconnect = false;

        this.stopClient();

        this.logger.info('TCP client manager resource cleanup completed');
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

    // Compatibility with old interface
    disconnectAndCleanup(): void {
        this.stopClient();
    }

    restartConnection(): void {
        this.logger.info('Restarting TCP client');
        this.stopClient();
        if (this.autoReconnect) {
            this.startClient();
        }
    }
}
