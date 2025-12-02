import * as vscode from 'vscode';
import {ActionType, EditorState, formatTimestamp, SourceType} from './Type';
import {Logger} from "./Logger";
import {FileUtils} from './FileUtils';

/**
 * Editor state manager
 * Responsible for state caching, debouncing and deduplication
 */
export class EditorStateManager {
    private logger: Logger;
    // Debounce timers grouped by file path
    private debounceTimers: Map<string, NodeJS.Timeout> = new Map();
    // Debounce delay
    private readonly debounceDelayMs = 300;

    private stateChangeCallback: ((state: EditorState) => void) | null = null;

    constructor(logger: Logger) {
        this.logger = logger;
    }

    /**
     * Set state change callback
     */
    setStateChangeCallback(callback: (state: EditorState) => void) {
        this.stateChangeCallback = callback;
    }

    /**
     * Create editor state
     */
    createEditorState(
        editor: vscode.TextEditor,
        action: ActionType,
        isActive: boolean
    ): EditorState {
        const position = FileUtils.getEditorCursorPosition(editor);

        // Get selection range coordinates
        const selectionCoordinates = FileUtils.getSelectionCoordinates(editor);

        return new EditorState(
            action,
            FileUtils.getEditorFilePath(editor),
            position.line,
            position.column,
            SourceType.VSCODE,
            isActive,
            formatTimestamp(),
            undefined, // openedFiles
            selectionCoordinates?.startLine,
            selectionCoordinates?.startColumn,
            selectionCoordinates?.endLine,
            selectionCoordinates?.endColumn
        );
    }

    createCloseState(filePath: string, isActive: boolean): EditorState {
        return new EditorState(
            ActionType.CLOSE,
            filePath,
            0,
            0,
            SourceType.VSCODE,
            isActive,
            formatTimestamp()
        );
    }

    /**
     * Create editor state from file path
     */
    createEditorStateFromPath(
        path: string,
        action: ActionType,
        isActive: boolean
    ): EditorState {
        return new EditorState(
            action,
            path,
            0,
            0,
            SourceType.VSCODE,
            isActive,
            formatTimestamp()
        );
    }


    /**
     * Create workspace sync state
     */
    createWorkspaceSyncState(isActive: boolean): EditorState {
        const activeEditor = FileUtils.getCurrentActiveEditor();
        const openedFiles = FileUtils.getAllOpenedFiles();

        if (activeEditor) {
            const position = FileUtils.getEditorCursorPosition(activeEditor);
            return new EditorState(
                ActionType.WORKSPACE_SYNC,
                FileUtils.getEditorFilePath(activeEditor),
                position.line,
                position.column,
                SourceType.VSCODE,
                isActive,
                formatTimestamp(),
                openedFiles
            );
        } else {
            // When there is no active editor, use empty file path and position
            return new EditorState(
                ActionType.WORKSPACE_SYNC,
                '',
                0,
                0,
                SourceType.VSCODE,
                isActive,
                formatTimestamp(),
                openedFiles
            );
        }
    }

    /**
     * Clear debounce timer for specified file path
     */
    private clearDebounceTimer(filePath: string) {
        const timer = this.debounceTimers.get(filePath);
        if (timer) {
            clearTimeout(timer);
            this.debounceTimers.delete(filePath);
            this.logger.debug(`Cleared file debounce timer: ${filePath}`);
        }
    }

    /**
     * Debounced update state
     */
    debouncedUpdateState(state: EditorState) {
        const filePath = state.filePath;

        // Clear previous debounce timer for this file
        this.clearDebounceTimer(filePath);

        // Create new debounce timer
        const timer = setTimeout(() => {
            try {
                this.updateState(state);
            } catch (error) {
                this.logger.warn(`Error occurred while updating state: ${error}`);
            } finally {
                // Regardless of whether an exception occurs, clean up timer to prevent memory leaks
                this.debounceTimers.delete(filePath);
            }
        }, this.debounceDelayMs);

        this.debounceTimers.set(filePath, timer);
    }

    /**
     * Update state immediately
     */
    updateState(state: EditorState) {
        // If it's a file close operation, immediately clear debounce timer and handle directly
        if (state.action === ActionType.CLOSE) {
            this.clearDebounceTimer(state.filePath);
        }
        // Notify state change
        this.stateChangeCallback?.(state);
    }

    /**
     * Send current state
     * Get current active editor state and send
     */
    sendCurrentState(isActive: boolean) {
        const currentState = this.getCurrentActiveEditorState(isActive);
        if (currentState) {
            this.updateState(currentState);
            this.logger.info(`Sent current state: ${currentState.filePath}`);
        }
    }

    /**
     * Get current active editor state
     */
    getCurrentActiveEditorState(isActive: boolean): EditorState | null {
        try {
            const activeEditor = FileUtils.getCurrentActiveEditor();
            if (!activeEditor) {
                return null;
            }

            const position = FileUtils.getEditorCursorPosition(activeEditor);

            // Get selection range coordinates
            const selectionCoordinates = FileUtils.getSelectionCoordinates(activeEditor);

            return new EditorState(
                ActionType.NAVIGATE,
                FileUtils.getEditorFilePath(activeEditor),
                position.line,
                position.column,
                SourceType.VSCODE,
                isActive,
                formatTimestamp(),
                undefined, // openedFiles
                selectionCoordinates?.startLine,
                selectionCoordinates?.startColumn,
                selectionCoordinates?.endLine,
                selectionCoordinates?.endColumn
            );
        } catch (error) {
            this.logger.warn('Failed to get current active editor state:', error as Error);
            return null;
        }
    }

    /**
     * Clean up resources
     */
    dispose() {
        this.logger.info("Starting to clean up editor state manager resources")

        // Clear all debounce timers
        for (const [filePath, timer] of this.debounceTimers) {
            clearTimeout(timer);
            this.logger.debug(`Cleared debounce timer: ${filePath}`);
        }
        this.debounceTimers.clear();

        this.logger.info("Editor state manager resource cleanup completed")
    }
}
