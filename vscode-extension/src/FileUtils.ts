import * as vscode from 'vscode';
import * as path from 'path';
import {Logger} from './Logger';
import {LogFormatter} from './Type';

/**
 * File utility class
 * Provides file operation related utility methods
 */
export class FileUtils {
    private static logger: Logger;

    /**
     * Initialize utility class
     * @param logger Logger instance
     */
    static initialize(logger: Logger): void {
        this.logger = logger;
    }

    /**
     * Check if file is still open in other tabs
     */
    static isFileOpenInOtherTabs(filePath: string): boolean {
        for (const tabGroup of vscode.window.tabGroups.all) {
            for (const tab of tabGroup.tabs) {
                if (FileUtils.isRegularFileTab(tab)) {
                    const uri = (tab.input as vscode.TabInputText).uri;
                    if (uri.fsPath === filePath) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Check if it's a regular file tab (only allows regular file protocols)
     */
    static isRegularFileTab(tab: vscode.Tab): boolean {
        const input = tab.input;

        // Only accept TabInputText type, exclude all other types
        if (!(input instanceof vscode.TabInputText)) {
            return false;
        }

        const uri = input.uri;

        // Reuse isRegularFileUri logic
        return this.isRegularFileUri(uri);
    }

    /**
     * Check if it's a regular file URI (only allows regular file protocols)
     */
    static isRegularFileUri(uri: vscode.Uri): boolean {
        // Whitelist mechanism: only allow regular file protocols
        const allowedSchemes = [
            'file'              // Local file system
        ];

        return allowedSchemes.includes(uri.scheme);
    }

    /**
     * Check if editor is a valid regular file editor
     * @param editor Text editor
     * @returns Whether it's a regular file editor
     */
    static isRegularFileEditor(editor: vscode.TextEditor): boolean {
        return this.isRegularFileUri(editor.document.uri);
    }

    /**
     * Get all currently opened file paths
     * Only return regular file tabs, filter out special tab windows
     */
    static getAllOpenedFiles(): string[] {
        const openedFiles: string[] = [];

        for (const tabGroup of vscode.window.tabGroups.all) {
            for (const tab of tabGroup.tabs) {
                // Only handle regular text file tabs, filter out all special tab types
                if (this.isRegularFileTab(tab)) {
                    const tabInput = tab.input as vscode.TabInputText;
                    const uri = tabInput.uri;

                    // File protocol already validated in isRegularFileTab, add directly
                    openedFiles.push(uri.fsPath);
                }
            }
        }

        return openedFiles;
    }


    /**
     * Extract file name from file path
     * @param filePath File path
     * @returns File name
     */
    static extractFileName(filePath: string): string {
        return path.basename(filePath);
    }

    /**
     * Get file path of editor
     * @param editor Text editor
     * @returns File path
     */
    static getEditorFilePath(editor: vscode.TextEditor): string {
        return editor.document.uri.fsPath;
    }

    /**
     * Get cursor position of editor
     * @param editor Text editor
     * @returns Cursor position {line: number, column: number}
     */
    static getEditorCursorPosition(editor: vscode.TextEditor): { line: number, column: number } {
        const position = editor.selection.active;
        return {
            line: position.line,
            column: position.character
        };
    }

    /**
     * Get selection range coordinates of editor
     * @param editor Text editor
     * @returns Selection range coordinates {startLine, startColumn, endLine, endColumn}, returns null if no selection
     */
    static getSelectionCoordinates(editor: vscode.TextEditor): { startLine: number, startColumn: number, endLine: number, endColumn: number } | null {
        const selection = editor.selection;
        const hasSelection = !selection.isEmpty;

        if (hasSelection) {
            return {
                startLine: selection.start.line,
                startColumn: selection.start.character,
                endLine: selection.end.line,
                endColumn: selection.end.character
            };
        } else {
            return null;
        }
    }

    /**
     * Get current active editor
     * Use multi-layer strategy to ensure getting the current editing area's text editor even when focus is not on the editor
     * @returns Returns the currently active TextEditor, returns null if none
     */
    static getCurrentActiveEditor(): vscode.TextEditor | null {
        // Strategy 1: Prioritize using activeTextEditor (when focus is on editor)
        const activeEditor = vscode.window.activeTextEditor;
        if (activeEditor && this.isRegularFileEditor(activeEditor)) {
            this.logger.info(`Got editor via activeTextEditor: ${this.extractFileName(activeEditor.document.uri.fsPath)}`);
            return activeEditor;
        }

        // Strategy 2: Get from visible editors (fallback when focus is not on editor)
        const visibleEditors = vscode.window.visibleTextEditors;
        if (visibleEditors.length > 0) {
            // Find regular file editors, prioritize selecting the last one (usually the most recently active)
            const regularEditors = visibleEditors.filter(editor => this.isRegularFileEditor(editor));
            if (regularEditors.length > 0) {
                const selectedEditor = regularEditors[regularEditors.length - 1];
                this.logger.info(`Got editor via visible editors: ${this.extractFileName(selectedEditor.document.uri.fsPath)}`);
                return selectedEditor;
            }
        }

        // Strategy 3: Get editor corresponding to active tab from active tab group
        const activeTabGroup = vscode.window.tabGroups.activeTabGroup;
        if (activeTabGroup && activeTabGroup.activeTab) {
            const activeTab = activeTabGroup.activeTab;
            if (this.isRegularFileTab(activeTab)) {
                const tabInput = activeTab.input as vscode.TabInputText;
                const uri = tabInput.uri;

                // Find corresponding editor
                const correspondingEditor = vscode.window.visibleTextEditors.find(
                    editor => editor.document.uri.toString() === uri.toString()
                );
                if (correspondingEditor && this.isRegularFileEditor(correspondingEditor)) {
                    this.logger.info(`Got editor via active tab group: ${this.extractFileName(uri.fsPath)}`);
                    return correspondingEditor;
                }
            }
        }

        // Strategy 4: Find the most recent regular file tab from all tab groups
        for (const tabGroup of vscode.window.tabGroups.all) {
            if (tabGroup.activeTab && this.isRegularFileTab(tabGroup.activeTab)) {
                const tabInput = tabGroup.activeTab.input as vscode.TabInputText;
                const uri = tabInput.uri;

                // Find corresponding editor
                const correspondingEditor = vscode.window.visibleTextEditors.find(
                    editor => editor.document.uri.toString() === uri.toString()
                );
                if (correspondingEditor && this.isRegularFileEditor(correspondingEditor)) {
                    this.logger.info(`Got editor via tab group: ${this.extractFileName(uri.fsPath)}`);
                    return correspondingEditor;
                }
            }
        }

        this.logger.warn('Could not get any active editor, possibly no text files are open');
        return null;
    }

    /**
     * Check if current editor has focus
     * @returns Whether current editor has focus
     */
    static isEditorFocused(): boolean {
        // In VSCode, activeTextEditor only has a value when the editor has focus
        // If activeTextEditor exists and is a regular file editor, it means the editor has focus
        const activeEditor = vscode.window.activeTextEditor;
        if (activeEditor && this.isRegularFileEditor(activeEditor)) {
            this.logger.info(`Editor has focus: ${this.extractFileName(activeEditor.document.uri.fsPath)}`);
            return true;
        }

        // If activeTextEditor doesn't exist or is not a regular file editor, it means focus is elsewhere
        this.logger.info('Editor does not have focus, focus might be on other tool windows or panels');
        return false;
    }


    /**
     * Close file by file path
     * Uses two-phase close strategy: try tab method first, then textDocument method if it fails
     * Only uses exact path matching, not filename matching
     */
    static async closeFileByPath(filePath: string): Promise<void> {
        try {
            this.logger.info(`Preparing to close file: ${filePath}`);

            // Phase 1: Try to close via tabGroups API
            let targetTab: vscode.Tab | undefined;

            for (const tabGroup of vscode.window.tabGroups.all) {
                for (const tab of tabGroup.tabs) {
                    if (this.isRegularFileTab(tab)) {
                        const tabInput = tab.input as vscode.TabInputText;
                        if (tabInput.uri.fsPath === filePath) {
                            targetTab = tab;
                            break;
                        }
                    }
                }
                if (targetTab) break;
            }

            if (targetTab) {
                try {
                    await vscode.window.tabGroups.close(targetTab);
                    this.logger.info(`✅ Successfully closed file via tab method: ${filePath}`);
                    return;
                } catch (tabCloseError) {
                    this.logger.warn(`Tab method close failed, trying fallback: ${filePath}`, tabCloseError as Error);
                }
            } else {
                this.logger.warn(`❌ File not found in tabs: ${filePath}`);
            }

            // Phase 2: Fallback - use original textDocument method to close
            this.logger.info(`🔄 Trying to close via textDocument method: ${filePath}`);
            const editorToClose = vscode.workspace.textDocuments.find(doc => doc.uri.fsPath === filePath);

            if (editorToClose) {
                await vscode.window.showTextDocument(editorToClose);
                await vscode.commands.executeCommand('workbench.action.closeActiveEditor');
                this.logger.info(`✅ Successfully closed file via textDocument method: ${filePath}`);
            } else {
                this.logger.warn(`❌ File also not found in textDocument: ${filePath}`);
            }
        } catch (error) {
            this.logger.warn(`Failed to close file: ${filePath}`, error as Error);
        }
    }

    /**
     * Open file by file path
     * @param filePath File path
     * @param focusEditor Whether to get focus, defaults to true
     * @returns Returns the opened TextEditor, returns null if failed
     */
    static async openFileByPath(filePath: string, focusEditor: boolean = true): Promise<vscode.TextEditor | null> {
        try {
            this.logger.info(`Preparing to open file: ${filePath}`);
            const uri = vscode.Uri.file(filePath);
            const document = await vscode.workspace.openTextDocument(uri);
            const editor = await vscode.window.showTextDocument(document, {preview: false, preserveFocus: !focusEditor});
            this.logger.info(`✅ Successfully opened file: ${filePath}`);
            return editor;
        } catch (error) {
            this.logger.warn(`Failed to open file: ${filePath}`, error as Error);
            return null;
        }
    }


    /**
     * Unified handling of selection and cursor movement
     * Supports cursor at any position, not limited by selection range
     * @param editor Text editor
     * @param line Cursor line number
     * @param column Cursor column number
     * @param startLine Selection start line number ( optional)
     * @param startColumn Selection start column number ( optional)
     * @param endLine Selection end line number ( optional)
     * @param endColumn Selection end column number ( optional)
     */
    static handleSelectionAndNavigate(
        editor: vscode.TextEditor,
        line: number,
        column: number,
        startLine?: number,
        startColumn?: number,
        endLine?: number,
        endColumn?: number
    ): void {
        try {
            this.logger.info(`Preparing to handle selection and cursor navigation: ${LogFormatter.cursorLog(line, column)}, ${LogFormatter.selectionLog(startLine, startColumn, endLine, endColumn)}`);

            const cursorPosition = new vscode.Position(line, column);

            // Check if there are valid selection range parameters
            const hasValidSelection = startLine !== undefined && startColumn !== undefined &&
                endLine !== undefined && endColumn !== undefined;

            // Check if it's a valid non-zero length selection
            const hasNonZeroSelection = hasValidSelection &&
                !(startLine === endLine && startColumn === endColumn);

            if (hasNonZeroSelection) {
                // Handle valid selection range, need to correctly set cursor position
                const startPosition = new vscode.Position(startLine, startColumn);
                const endPosition = new vscode.Position(endLine, endColumn);

                // Determine selection direction by distance: calculate distance from cursor to selection start and end
                // If cursor is closer to start, it means selection is from bottom to top (anchor at end)
                // If cursor is closer to end, it means selection is from top to bottom (anchor at start)
                const distanceToStart = Math.abs((line - startLine) * 1000 + (column - startColumn));
                const distanceToEnd = Math.abs((line - endLine) * 1000 + (column - endColumn));

                if (distanceToStart < distanceToEnd) {
                    // Cursor is closer to start position, selecting from bottom to top
                    // VSCode Selection constructor: new Selection(anchor, active)
                    // anchor is the selection anchor point, active is the actual cursor position
                    editor.selection = new vscode.Selection(endPosition, cursorPosition);
                    this.logger.info(`✅ Successfully set selection range (bottom to top): ${LogFormatter.selection(startLine, startColumn, endLine, endColumn)}, ${LogFormatter.cursorLog(line, column)}`);
                } else {
                    // Cursor is closer to end position, selecting from top to bottom
                    editor.selection = new vscode.Selection(startPosition, cursorPosition);
                    this.logger.info(`✅ Successfully set selection range (top to bottom): ${LogFormatter.selection(startLine, startColumn, endLine, endColumn)}, ${LogFormatter.cursorLog(line, column)}`);
                }
            } else {
                // Clear selection state, only set cursor position
                editor.selection = new vscode.Selection(cursorPosition, cursorPosition);
                this.logger.info(`✅ Successfully cleared selection state, ${LogFormatter.cursorLog(line, column)}`);
            }

            // Ensure cursor position is within visible area
            const visibleRange = editor.visibleRanges[0];
            if (!visibleRange || !visibleRange.contains(cursorPosition)) {
                editor.revealRange(
                    new vscode.Range(cursorPosition, cursorPosition),
                    vscode.TextEditorRevealType.InCenter
                );
                this.logger.info(`✅ Cursor position not visible, scrolled to: ${LogFormatter.cursor(line, column)}`);
            } else {
                this.logger.info(`Cursor position already within visible area, no scrolling needed`);
            }

            this.logger.info(`✅ Selection and cursor navigation processing completed`);
        } catch (error) {
            this.logger.warn(`❌ Failed to handle selection and cursor navigation: ${LogFormatter.cursorLog(line, column)}`, error as Error);
        }
    }

} 