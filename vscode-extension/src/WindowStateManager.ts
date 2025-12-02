import * as vscode from 'vscode';
import {Logger} from './Logger';

/**
 * Window state manager
 * Unified management of window active state, providing efficient and accurate state queries
 * Combines high performance of event listening with accuracy advantages of real-time queries
 */
export class WindowStateManager {
    private logger: Logger;

    // State cache maintained by event listening (high performance queries)
    private isActiveCache: boolean = true;

    // State change callback
    private onWindowStateChange?: (isActive: boolean) => void;

    // Cleanup function for event listeners
    private disposable?: vscode.Disposable;

    constructor(logger: Logger) {
        this.logger = logger;
    }

    /**
     * Get workspace name
     */
    private getWorkspaceName(): string {
        try {
            const workspaceFolders = vscode.workspace.workspaceFolders;
            if (workspaceFolders && workspaceFolders.length > 0) {
                return workspaceFolders[0].name;
            }
            return 'unknown-workspace';
        } catch (error) {
            return 'unknown-workspace';
        }
    }

    /**
     * Initialize window state listening
     */
    initialize(): void {
        const workspaceName = this.getWorkspaceName();
        this.logger.info(`Initializing window state manager: ${workspaceName}`);
        this.setupWindowFocusListener();
        // Get real-time state during initialization
        this.isActiveCache = this.getRealTimeWindowState();
        this.logger.info(`Window state manager initialization completed: ${workspaceName}, current state: ${this.isActiveCache}`);
    }

    /**
     * Set up window focus listener
     */
    private setupWindowFocusListener(): void {
        const workspaceName = this.getWorkspaceName();
        this.disposable = vscode.window.onDidChangeWindowState((e) => {
            this.updateWindowState(e.focused);
            if (e.focused) {
                this.logger.info(`VSCode window gained focus: ${workspaceName}`);
            } else {
                this.logger.info(`VSCode window lost focus: ${workspaceName}`);
            }
        });
    }

    /**
     * Update window state and trigger callback
     */
    private updateWindowState(isActive: boolean): void {
        const previousState = this.isActiveCache;
        this.isActiveCache = isActive;

        // Trigger callback when state changes
        if (previousState !== isActive) {
            this.onWindowStateChange?.(isActive);
        }
    }

    /**
     * Get window active state (high performance version)
     * Uses cache state maintained by event listening in most cases
     * @param forceRealTime Whether to force real-time query, defaults to false
     * @return Whether window is active
     */
    isWindowActive(forceRealTime: boolean = false): boolean {
        if (forceRealTime) {
            // Force real-time query, used for critical operations or state validation
            const realTimeState = this.getRealTimeWindowState();

            // If cache state is inconsistent with real-time state, update cache
            const cachedState = this.isActiveCache;
            if (cachedState !== realTimeState) {
                this.logger.warn(`Detected state inconsistency, cache: ${cachedState}, real-time: ${realTimeState}, syncing`);
                this.updateWindowState(realTimeState);
            }

            return realTimeState;
        } else {
            // Use high-performance cache state
            return this.isActiveCache;
        }
    }

    /**
     * Get window state in real-time
     * Directly get from VSCode API to ensure state accuracy
     */
    private getRealTimeWindowState(): boolean {
        try {
            return vscode.window.state.focused;
        } catch (error) {
            this.logger.warn('Failed to get real-time window state:', error as Error);
            // Return cache state when getting fails
            return this.isActiveCache;
        }
    }

    /**
     * Set window state change callback
     * @param callback Callback function when state changes, parameter is new active state
     */
    setOnWindowStateChangeCallback(callback: (isActive: boolean) => void): void {
        this.onWindowStateChange = callback;
    }

    /**
     * Clean up resources
     */
    dispose(): void {
        const workspaceName = this.getWorkspaceName()
        this.logger.info(`Starting to clean up window state manager resources: ${workspaceName}`);

        this.disposable?.dispose();
        this.logger.info(`Window state manager resource cleanup completed`);
    }
}