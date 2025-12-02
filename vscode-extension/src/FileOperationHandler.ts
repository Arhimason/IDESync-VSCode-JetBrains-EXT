import {ActionType, EditorState} from './Type';
import {Logger} from './Logger';
import {FileUtils} from './FileUtils';
import {EditorStateManager} from './EditorStateManager';
import {WindowStateManager} from './WindowStateManager';

/**
 * File operation handler
 * Responsible for file open, close and navigation operations
 */
export class FileOperationHandler {
    private logger: Logger;
    private editorStateManager: EditorStateManager;
    private windowStateManager: WindowStateManager;

    constructor(logger: Logger, editorStateManager: EditorStateManager, windowStateManager: WindowStateManager) {
        this.logger = logger;
        this.editorStateManager = editorStateManager;
        this.windowStateManager = windowStateManager;
    }


    async handleIncomingState(state: EditorState): Promise<void> {
        try {
            if (state.action === ActionType.CLOSE) {
                return this.handleFileClose(state);
            } else if (state.action === ActionType.WORKSPACE_SYNC) {
                return this.handleWorkspaceSync(state);
            } else if (state.action === ActionType.OPEN) {
                return this.handleFileOpenOrNavigate(state, false);
            } else {
                return this.handleFileOpenOrNavigate(state);
            }
        } catch (error) {
            this.logger.warn('Failed to handle message operation:', error as Error);
        }
    }


    /**
     * Handle file close operation
     */
    async handleFileClose(state: EditorState): Promise<void> {
        this.logger.info(`Performing file close operation: ${state.filePath}`);
        const compatiblePath = state.getCompatiblePath();
        await FileUtils.closeFileByPath(compatiblePath);
    }

    /**
     * Handle workspace sync operation
     */
    async handleWorkspaceSync(state: EditorState): Promise<void> {
        this.logger.info(`Performing workspace sync operation: target file count: ${state.openedFiles?.length || 0}`);

        if (!state.openedFiles || state.openedFiles.length === 0) {
            this.logger.info('No opened files in workspace sync message, skipping processing');
            return;
        }

        try {
            // Get current editor active state
            let currentActiveState = await this.isCurrentWindowActive();
            this.logger.info(`Current editor active state: ${currentActiveState}`);
            // If current editor is active, save current editor state
            let savedActiveEditorState: EditorState | null = this.editorStateManager.getCurrentActiveEditorState(this.windowStateManager.isWindowActive(true));
            if (savedActiveEditorState) {
                this.logger.info(`Saved current active editor state: ${savedActiveEditorState.filePath}, ${savedActiveEditorState.getCursorLog()}, ${savedActiveEditorState.getSelectionLog()}`);
            } else {
                this.logger.info('No active editor currently');
            }

            // Get all currently opened files
            const currentOpenedFiles = FileUtils.getAllOpenedFiles();
            const targetFiles = state.openedFiles.map(filePath => {
                // Create temporary EditorState to use path conversion logic
                const tempState = new EditorState(ActionType.OPEN, filePath, 0, 0);
                return tempState.getCompatiblePath();
            });

            this.logger.info(`Currently opened files: ${currentOpenedFiles.length}`);
            this.logger.info(`Target files: ${targetFiles.length}`);
            this.logger.info(`Current opened regular file list: ${currentOpenedFiles.map(f => FileUtils.extractFileName(f)).join(', ')}`);

            // Close excess files (currently opened but not in target)
            const filesToClose = currentOpenedFiles.filter((file: string) => !targetFiles.includes(file));
            for (const fileToClose of filesToClose) {
                await FileUtils.closeFileByPath(fileToClose);
            }

            // Open missing files (exist in target but not currently opened)
            const filesToOpen = targetFiles.filter((file: string) => !currentOpenedFiles.includes(file));
            for (const fileToOpen of filesToOpen) {
                await FileUtils.openFileByPath(fileToOpen, false);
            }

            // Get current editor active state again (to prevent delayed state changes)
            currentActiveState = await this.isCurrentWindowActive();
            if (currentActiveState) {
                if (savedActiveEditorState && filesToOpen.length > 0) {
                    await this.restoreLocalState(savedActiveEditorState, true);
                } else {
                    this.logger.info('No active editor state, not performing restoration');
                }
            } else {
                await this.followRemoteState(state);
            }

            this.logger.info(`✅ Workspace sync completed`);
        } catch (error) {
            this.logger.warn('Workspace sync failed:', error as Error);
        }
    }


    async restoreLocalState(state: EditorState, focusEditor: boolean = true): Promise<void> {
        this.logger.info(`Restoring local state: ${state.filePath}, focused=${focusEditor}, ${state.getCursorLog()}, ${state.getSelectionLog()}`);
        await this.handleFileOpenOrNavigate(state, focusEditor);
    }

    async followRemoteState(state: EditorState): Promise<void> {
        this.logger.info(`Following remote state: ${state.filePath}, ${state.getCursorLog()}, ${state.getSelectionLog()}`);
        await this.handleFileOpenOrNavigate(state);
    }


    /**
     * Handle file open and navigation operations
     */
    async handleFileOpenOrNavigate(state: EditorState, focusEditor: boolean = true): Promise<void> {
        if (state.hasSelection()) {
            this.logger.info(`Performing file selection and navigation operation: ${state.filePath}, navigate to: ${state.getCursor()} ${state.getSelectionLog()}`);
        } else {
            this.logger.info(`Performing file navigation operation: ${state.filePath}, navigate to: ${state.getCursor()}`);
        }

        try {
            const editor = await FileUtils.openFileByPath(state.getCompatiblePath(), focusEditor);
            if (editor) {
                // Use unified selection and cursor handling logic
                FileUtils.handleSelectionAndNavigate(
                    editor,
                    state.line,
                    state.column,
                    state.selectionStartLine,
                    state.selectionStartColumn,
                    state.selectionEndLine,
                    state.selectionEndColumn
                );
            } else {
                this.logger.warn(`Unable to open file for navigation: ${state.getCompatiblePath()}`);
            }
        } catch (error) {
            this.logger.warn('Failed to handle received state:', error as Error);
        }
    }


    /**
     * Check if current editor is in active state
     * For critical editor state checks, use retry mechanism to ensure accuracy
     */
    private async isCurrentWindowActive(): Promise<boolean> {
        let attempts = 0;
        const maxAttempts = 5;
        const delay = 100; // Delay between each attempt

        while (attempts < maxAttempts) {
            // For critical editor state checks, use forced real-time query to ensure accuracy
            const isActive = this.windowStateManager.isWindowActive(true);
            if (isActive) {
                return true;
            }
            this.logger.warn(`Check active editor state failed, attempt ${attempts + 1}/${maxAttempts}...`);
            await new Promise(resolve => setTimeout(resolve, delay));
            attempts++;
        }
        return false;
    }
}
