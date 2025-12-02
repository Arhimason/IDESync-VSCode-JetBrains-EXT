import * as vscode from 'vscode';
import {ActionType, LogFormatter} from './Type';
import {Logger} from './Logger';
import {EditorStateManager} from './EditorStateManager';
import {FileUtils} from './FileUtils';
import {WindowStateManager} from './WindowStateManager';

/**
 * Pending TAB open events
 */
interface PendingTabEvent {
    uri: vscode.Uri;
    tab: vscode.Tab;
    timeout: NodeJS.Timeout;
}

/**
 * Event listener manager
 * Unified management of various VSCode editor event listeners
 */
export class EventListenerManager {
    private disposables: vscode.Disposable[] = [];
    private logger: Logger;
    private editorStateManager: EditorStateManager;
    private windowStateManager: WindowStateManager;

    // Pending TAB event queue, used to distinguish foreground/background opening
    private pendingTabEvents: Map<string, PendingTabEvent> = new Map();

    // Delay confirmation time (milliseconds)
    private readonly CONFIRMATION_DELAY = 150;

    constructor(
        logger: Logger,
        editorStateManager: EditorStateManager,
        windowStateManager: WindowStateManager
    ) {
        this.logger = logger;
        this.editorStateManager = editorStateManager;
        this.windowStateManager = windowStateManager;
    }


    /**
     * Set up editor listeners
     */
    setupEditorListeners() {
        this.logger.info('Setting up editor listeners');
        // Listen for active editor changes
        this.disposables.push(
            vscode.window.onDidChangeActiveTextEditor((editor) => {
                if (!editor) {
                    return;
                }
                if (!FileUtils.isRegularFileUri(editor.document.uri)) {
                    return;
                }

                const filePath = editor.document.uri.fsPath;

                // Check if there is a corresponding pending TAB event
                const pendingEvent = this.pendingTabEvents.get(filePath);
                if (pendingEvent) {
                    // Cancel pending TAB event because this is foreground opening
                    clearTimeout(pendingEvent.timeout);
                    this.pendingTabEvents.delete(filePath);
                    this.logger.info(`Canceled pending TAB event, confirmed as foreground opening: ${filePath}`);
                }

                this.logger.info(`Event - File foreground opened: ${filePath}`);
                const state = this.editorStateManager.createEditorState(
                    editor, ActionType.OPEN, this.windowStateManager.isWindowActive()
                );
                this.logger.info(`Preparing to send foreground open message: ${state.filePath}`);
                this.editorStateManager.updateState(state);
            })
        );

        // Listen for TAB closure
        this.disposables.push(
            vscode.window.tabGroups.onDidChangeTabs((event) => {
                // Handle newly opened TABs
                event.opened.forEach((tab, index) => {
                    // Detect if tab type is regular file, ignore other types
                    if (!FileUtils.isRegularFileTab(tab)) {
                        this.logger.info(`Opened TAB ${index}: Non-regular file type, ignored`);
                        return
                    }

                    const uri = (tab.input as vscode.TabInputText).uri;
                    const filePath = uri.fsPath;

                    this.logger.info(`Event - File TAB opened: ${filePath}`);

                    // Create timeout handler for delayed confirmation
                    const timeout = setTimeout(() => {
                        // Confirmed as background opening after timeout
                        this.pendingTabEvents.delete(filePath);
                        this.handleBackgroundOpen(uri);
                    }, this.CONFIRMATION_DELAY);

                    // Add to pending queue
                    this.pendingTabEvents.set(filePath, {
                        uri,
                        tab,
                        timeout
                    });

                    this.logger.info(`TAB event added to pending queue: ${filePath}`);
                });

                event.closed.forEach((tab, index) => {
                    // Detect if tab type is regular file, ignore other types
                    if (!FileUtils.isRegularFileTab(tab)) {
                        this.logger.info(`Closed TAB ${index}: Non-regular file type, ignored`);
                        return;
                    }
                    const uri = (tab.input as vscode.TabInputText).uri;
                    this.logger.info(`Event - File closed: ${uri.fsPath}`);
                    const filePath = uri.fsPath;

                    // Check if file is still open in other TABs
                    const isStillOpen = FileUtils.isFileOpenInOtherTabs(filePath);
                    if (isStillOpen) {
                        this.logger.info(`File still open in other TABs, skipping close message: ${filePath}`);
                        return;
                    }

                    this.logger.info(`File completely closed, sending close message: ${filePath}`);
                    const state = this.editorStateManager.createCloseState(
                        filePath,
                        this.windowStateManager.isWindowActive()
                    );
                    this.logger.info(`Preparing to send close message: ${state.filePath}`);
                    this.editorStateManager.updateState(state)
                });
            })
        )

        // Listen for cursor position and selection changes
        this.disposables.push(
            vscode.window.onDidChangeTextEditorSelection((event) => {
                if (!FileUtils.isRegularFileUri(event.textEditor.document.uri)) {
                    return;
                }

                const hasSelection = !event.textEditor.selection.isEmpty;
                const selection = event.textEditor.selection;
                const cursorPosition = selection.active;
                const filePath = event.textEditor.document.uri.fsPath;

                let logMessage = `Event - Selection changed: ${filePath}, ${LogFormatter.cursorLog(cursorPosition.line, cursorPosition.character)}`;

                if (hasSelection) {
                    logMessage += `，${LogFormatter.selectionLog(selection.start.line, selection.start.character, selection.end.line, selection.end.character)}`;
                } else {
                    logMessage += `，${LogFormatter.selectionLog()}`;
                }
                this.logger.info(logMessage);

                if (event.textEditor === FileUtils.getCurrentActiveEditor()) {
                    const state = this.editorStateManager.createEditorState(
                        event.textEditor, ActionType.NAVIGATE, this.windowStateManager.isWindowActive()
                    );
                    this.logger.info(`Preparing to send navigation message: ${state.action} ${state.filePath}, ${state.getSelectionLog()}, ${state.getSelectionLog()}`);
                    this.editorStateManager.debouncedUpdateState(state);
                }
            })
        );

        this.logger.info('Editor listeners setup completed');
    }


    /**
     * Handle background file opening
     */
    private handleBackgroundOpen(uri: vscode.Uri) {
        const filePath = uri.fsPath;

        this.logger.info(`Event - File background opened: ${filePath}`);

        // Send file open event
        const openState = this.editorStateManager.createEditorStateFromPath(
            filePath, ActionType.OPEN, this.windowStateManager.isWindowActive()
        );
        this.logger.info(`Preparing to send background open message: ${openState.filePath}`);
        this.editorStateManager.updateState(openState);


        const navigateState = this.editorStateManager.getCurrentActiveEditorState(this.windowStateManager.isWindowActive());
        if (navigateState) {
            this.logger.info(`Preparing to send current active editor navigation message: ${navigateState.filePath}`);
            this.editorStateManager.debouncedUpdateState(navigateState);
        }
    }


    /**
     * Clean up resources
     */
    dispose() {
        // Clean up all pending TAB events
        this.pendingTabEvents.forEach((pendingEvent) => {
            clearTimeout(pendingEvent.timeout);
        });
        this.pendingTabEvents.clear();

        this.disposables.forEach(d => d.dispose());
        this.disposables = [];
    }
}
