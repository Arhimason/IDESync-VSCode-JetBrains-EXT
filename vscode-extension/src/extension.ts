import * as vscode from 'vscode';
import {ConnectionCallback, ConnectionState, EditorState} from './Type';
import {Logger} from './Logger';
import {FileUtils} from './FileUtils';
import {EditorStateManager} from './EditorStateManager';
import {FileOperationHandler} from './FileOperationHandler';
import {EventListenerManager} from './EventListenerManager';
import {MessageProcessor} from './MessageProcessor';
import {TcpClientManager} from './TcpClientManager';
import {OperationQueueProcessor} from './OperationQueueProcessor';
import {WindowStateManager} from './WindowStateManager';
import {PartnerLauncher} from './PartnerLauncher';
import {IDEPathDetector} from './IDEPathDetector';

/**
 * VSCode and JetBrains Sync Class (Refactored Version)
 *
 * Adopting modular design with main components:
 * - Window State Manager: Unified management of window active state
 * - WebSocket Server Manager: Responsible for server management and client connections
 * - Editor State Manager: Manages state caching, debouncing and deduplication
 * - File Operation Handler: Handles file opening, closing and navigation
 * - Event Listener Manager: Unified management of various event listeners
 * - Message Processor: Handles message serialization and deserialization
 * - Operation Queue Processor: Ensures operation atomicity and ordering
 */
export class VSCodeJetBrainsSync {
    // Core Components
    private logger: Logger;
    private windowStateManager!: WindowStateManager;
    private editorStateManager!: EditorStateManager;
    private fileOperationHandler!: FileOperationHandler;
    private messageProcessor!: MessageProcessor;
    private tcpClientManager!: TcpClientManager;
    private eventListenerManager!: EventListenerManager;
    private operationQueueProcessor!: OperationQueueProcessor;
    private partnerLauncher!: PartnerLauncher;

    // UI Components
    private statusBarItem: vscode.StatusBarItem;

    constructor() {
        this.logger = new Logger('IDE Sync');
        this.statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
        this.statusBarItem.command = 'vscode-jetbrains-sync.toggleAutoReconnect';

        this.initializeComponents();
        this.setupComponentCallbacks();
        this.eventListenerManager.setupEditorListeners();

        // Check auto-start configuration
        this.checkAutoStartConfig();

        this.updateStatusBarWidget();
        this.statusBarItem.show();

        this.logger.info('VSCode-JetBrains sync service initialization completed');
    }

    /**
     * Initialize various components
     */
    private initializeComponents() {
        // First initialize FileUtils
        FileUtils.initialize(this.logger);

        this.windowStateManager = new WindowStateManager(this.logger);
        this.windowStateManager.initialize();

        this.editorStateManager = new EditorStateManager(this.logger);
        this.eventListenerManager = new EventListenerManager(this.logger, this.editorStateManager, this.windowStateManager);
        this.fileOperationHandler = new FileOperationHandler(this.logger, this.editorStateManager, this.windowStateManager);
        this.messageProcessor = new MessageProcessor(this.logger, this.fileOperationHandler);
        this.tcpClientManager = new TcpClientManager(this.logger, this.messageProcessor);
        this.operationQueueProcessor = new OperationQueueProcessor(
            this.logger, this.tcpClientManager
        );
        this.partnerLauncher = new PartnerLauncher(this.logger);
    }

    /**
     * Setup callback relationships between components
     */
    private setupComponentCallbacks() {
        // Window state change callback
        this.windowStateManager.setOnWindowStateChangeCallback((isActive: boolean) => {
            if (!isActive) {
                // Send workspace sync state when window loses focus
                const workspaceSyncState = this.editorStateManager.createWorkspaceSyncState(true);
                this.logger.info(`Window lost focus, sending workspace sync state with ${workspaceSyncState.openedFiles?.length || 0} opened files`);
                this.editorStateManager.updateState(workspaceSyncState);
            }
        });

        // Connection state change callback
        const connectionCallback: ConnectionCallback = {
            onConnected: () => {
                this.logger.info('TCP connection state changed: Connected');
                this.updateStatusBarWidget();
                this.editorStateManager.sendCurrentState(this.windowStateManager.isWindowActive());
            },
            onDisconnected: () => {
                this.logger.info('TCP connection state changed: Disconnected');
                this.updateStatusBarWidget();
            },
            onReconnecting: () => {
                this.logger.info('TCP connection state changed: Scanning for servers');
                this.updateStatusBarWidget();
                // If no server found, prompt to launch partner IDE
                this.checkAndPromptPartnerLaunch();
            }
        };

        this.tcpClientManager.setConnectionCallback(connectionCallback);

        // State change callback
        this.editorStateManager.setStateChangeCallback((state: EditorState) => {
            if (state.isActive) {
                this.operationQueueProcessor.addOperation(state);
            }
        });
    }


    /**
     * Update status bar display
     */
    private updateStatusBarWidget() {
        const autoReconnect = this.tcpClientManager.isAutoReconnect();
        const connectionState = this.tcpClientManager.getConnectionState()

        // Reference Kotlin implementation icon status logic
        const icon = (() => {
            if (connectionState === ConnectionState.CONNECTED) {
                return '$(check)';
            } else if ((connectionState === ConnectionState.CONNECTING || connectionState === ConnectionState.DISCONNECTED) && autoReconnect) {
                return '$(sync~spin)';
            } else {
                return '$(circle-outline)';
            }
        })();

        // Reference Kotlin implementation text status logic
        const statusText = autoReconnect ? 'IDE Sync On' : 'Turn IDE Sync On';

        // Reference Kotlin implementation tooltip logic
        const tooltip = (() => {
            let tip = '';
            if (connectionState === ConnectionState.CONNECTED) {
                tip += 'Connected to JetBrains IDE\n';
            }
            tip += `Click to turn sync ${autoReconnect ? 'off' : 'on'}`;
            return tip;
        })();

        this.statusBarItem.text = `${icon} ${statusText}`;
        this.statusBarItem.tooltip = tooltip;
    }


    /**
     * Check auto-start configuration
     */
    private checkAutoStartConfig() {
        const autoStartSync = vscode.workspace.getConfiguration('vscode-jetbrains-sync').get('autoStartSync', false);
        if (autoStartSync) {
            this.logger.info('Auto-start configuration detected as enabled, starting sync functionality...');
            this.tcpClientManager.toggleAutoReconnect();
        } else {
            this.logger.info('Auto-start configuration is disabled, manual start of sync functionality required');
        }
    }

    /**
     * Check and prompt to launch partner IDE
     */
    private checkAndPromptPartnerLaunch() {
        // Delayed check, give time for server scanning
        setTimeout(() => {
            if (this.tcpClientManager.isConnecting() && !this.tcpClientManager.isConnected()) {
                this.partnerLauncher.checkAndPromptLaunch(() => {
                    // Re-scan after launch completes
                    this.tcpClientManager.restartConnection();
                });
            }
        }, 3000);
    }

    /**
     * Toggle auto reconnect state
     */
    public toggleAutoReconnect() {
        this.tcpClientManager.toggleAutoReconnect();
        this.updateStatusBarWidget();
    }

    /**
     * Detect available JetBrains IDEs and show them to user
     */
    public async detectIDEs() {
        try {
            this.logger.info('Detecting available JetBrains IDEs...');
            const idePathDetector = new IDEPathDetector(this.logger);
            const detectedIDEs = await idePathDetector.detectAllJetBrainsPaths();
            
            if (detectedIDEs.length === 0) {
                vscode.window.showInformationMessage('No JetBrains IDEs detected. Please install a JetBrains IDE or enter the path manually.');
                return;
            }
            
            // Create quick pick items
            const items = detectedIDEs.map(ide => ({
                label: ide.name,
                description: ide.path,
                detail: `Click to use ${ide.name}`
            }));
            
            // Show quick pick
            const selected = await vscode.window.showQuickPick(items, {
                placeHolder: 'Select a JetBrains IDE to use for synchronization',
                matchOnDescription: true,
                matchOnDetail: true
            });
            
            if (selected) {
                // Update settings with selected path
                const config = vscode.workspace.getConfiguration('vscode-jetbrains-sync');
                await config.update('partnerIDEPath', selected.description, vscode.ConfigurationTarget.Workspace);
                
                vscode.window.showInformationMessage(
                    `Selected ${selected.label} for synchronization. Path: ${selected.description}`,
                    'OK'
                );
                
                this.logger.info(`User selected IDE: ${selected.label} at ${selected.description}`);
            }
        } catch (error) {
            this.logger.error('Failed to detect IDEs:', error as Error);
            vscode.window.showErrorMessage(`Failed to detect IDEs: ${(error as Error).message}`);
        }
    }


    /**
     * Cleanup resources
     */
    public dispose() {
        this.logger.info('Starting cleanup of VSCode sync service resources (refactored version)');

        this.operationQueueProcessor.dispose();
        this.tcpClientManager.dispose();
        this.eventListenerManager.dispose();
        this.editorStateManager.dispose();
        this.windowStateManager.dispose();
        this.statusBarItem.dispose();
        this.logger.dispose();

        this.logger.info('VSCode sync service resource cleanup completed');
    }
}

// Export activation and deactivation functions
let syncInstance: VSCodeJetBrainsSync | null = null;

export function activate(context: vscode.ExtensionContext) {
    syncInstance = new VSCodeJetBrainsSync();

    context.subscriptions.push(
        vscode.commands.registerCommand('vscode-jetbrains-sync.toggleAutoReconnect', () => {
            syncInstance?.toggleAutoReconnect();
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('vscode-jetbrains-sync.detectIDEs', () => {
            syncInstance?.detectIDEs();
        })
    );

    context.subscriptions.push({
        dispose: () => syncInstance?.dispose()
    });
}

export function deactivate() {
    syncInstance?.dispose();
}
