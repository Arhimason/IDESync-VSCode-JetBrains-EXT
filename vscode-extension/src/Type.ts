/**
 * Action type enumeration
 * Defines various operation types during editor synchronization
 * Uses string enumeration to ensure JSON serialization compatibility
 */
export enum ActionType {
    CLOSE = "CLOSE",        // Close file
    OPEN = "OPEN",          // Open file
    NAVIGATE = "NAVIGATE",  // Cursor navigation and code selection
    WORKSPACE_SYNC = "WORKSPACE_SYNC"  // Workspace state synchronization
}

/**
 * Message source enumeration
 * Defines the sender of the message
 */
export enum SourceType {
    VSCODE = "VSCODE",       // VSCode editor
    JETBRAINS = "JETBRAINS"  // JetBrains IDE
}

/**
 * Connection state enumeration
 * Defines various states of WebSocket connection
 */
export enum ConnectionState {
    DISCONNECTED = "DISCONNECTED",  // Disconnected
    CONNECTING = "CONNECTING",      // Connecting
    CONNECTED = "CONNECTED"         // Connected
}

/**
 * Editor state class
 * Used to pass editor state between VSCode and JetBrains
 */
export class EditorState {
    public action: ActionType;        // Action type enumeration (required)
    public filePath: string;          // File path
    public line: number;              // Line number (starts from 0)
    public column: number;            // Column number (starts from 0)
    public source: SourceType;        // Message source enumeration
    public isActive: boolean;         // Whether IDE is in active state
    public timestamp: string;         // Timestamp (yyyy-MM-dd HH:mm:ss.SSS)
    public openedFiles?: string[];    // All opened files in workspace (only used by WORKSPACE_SYNC type)
    // Selection range related fields (used by NAVIGATE type)
    public selectionStartLine?: number;    // Selection start line number (starts from 0)
    public selectionStartColumn?: number;  // Selection start column number (starts from 0)
    public selectionEndLine?: number;      // Selection end line number (starts from 0)
    public selectionEndColumn?: number;    // Selection end column number (starts from 0)

    // Platform compatible path cache
    private _compatiblePath?: string;

    constructor(
        action: ActionType,
        filePath: string,
        line: number,
        column: number,
        source: SourceType = SourceType.VSCODE,
        isActive: boolean = false,
        timestamp: string = formatTimestamp(),
        openedFiles?: string[],
        selectionStartLine?: number,
        selectionStartColumn?: number,
        selectionEndLine?: number,
        selectionEndColumn?: number
    ) {
        this.action = action;
        this.filePath = filePath;
        this.line = line;
        this.column = column;
        this.source = source;
        this.isActive = isActive;
        this.timestamp = timestamp;
        this.openedFiles = openedFiles;
        this.selectionStartLine = selectionStartLine;
        this.selectionStartColumn = selectionStartColumn;
        this.selectionEndLine = selectionEndLine;
        this.selectionEndColumn = selectionEndColumn;
    }

    /**
     * Get platform compatible file path
     * On first call, it cleans and transforms the original path and caches the result
     * Subsequent calls directly return the cached path
     */
    getCompatiblePath(): string {
        // If already cached, return directly
        if (this._compatiblePath) {
            return this._compatiblePath;
        }

        // First call, perform path cleaning and conversion
        const cleaned = this.cleanFilePath(this.filePath);
        const converted = this.convertToVSCodeFormat(cleaned);
        this._compatiblePath = converted;

        // Output log (if path has changed)
        if (converted !== this.filePath) {
            console.log(`EditorState: Path converted ${this.filePath} -> ${converted}`);
        }

        return converted;
    }

    /**
     * Clean file path, remove abnormal suffixes
     * Reference cleanFilePath method in FileOperationHandler
     */
    private cleanFilePath(path: string): string {
        let cleaned = path;

        // Remove abnormal .git suffix
        if (cleaned.endsWith('.git')) {
            cleaned = cleaned.slice(0, -4);
        }

        // Remove other possible abnormal suffixes
        const abnormalSuffixes = ['.tmp', '.bak', '.swp'];
        for (const suffix of abnormalSuffixes) {
            if (cleaned.endsWith(suffix)) {
                cleaned = cleaned.slice(0, -suffix.length);
                break;
            }
        }

        return cleaned;
    }

    /**
     * Convert path to VSCode format
     * Handle cross-platform path compatibility
     */
    private convertToVSCodeFormat(path: string): string {
        let vscodePath = path;

        // Detect operating system platform
        const isWindows = process.platform === 'win32';
        const isMacOS = process.platform === 'darwin';
        const isLinux = process.platform === 'linux';

        if (isWindows) {
            // Windows: Replace forward slashes with backslashes, drive letter to lowercase
            vscodePath = vscodePath.replace(/\//g, '\\');
            if (/^[A-Z]:\\/.test(vscodePath) || /^[A-Z]:/.test(vscodePath)) {
                vscodePath = vscodePath[0].toLowerCase() + vscodePath.substring(1);
            }
        } else if (isMacOS || isLinux) {
            // macOS/Linux: Ensure using forward slashes, remove Windows drive letter format
            vscodePath = vscodePath.replace(/\\/g, '/');

            // Remove Windows drive letter (if exists) and convert to Unix path
            if (/^[A-Za-z]:[\/\\]/.test(vscodePath)) {
                // For example: C:/Users/... -> /Users/... or c:\Users\... -> /Users/...
                vscodePath = vscodePath.substring(2).replace(/\\/g, '/');
            }

            // Ensure path starts with /
            if (!vscodePath.startsWith('/')) {
                vscodePath = '/' + vscodePath;
            }

            // Clean up duplicate slashes
            vscodePath = vscodePath.replace(/\/+/g, '/');
        }

        return vscodePath;
    }

    /**
     * Check if there is a selection range
     * @returns Returns true if there is a selection range, otherwise returns false
     */
    hasSelection(): boolean {
        return this.selectionStartLine !== undefined &&
            this.selectionEndLine !== undefined &&
            this.selectionStartColumn !== undefined &&
            this.selectionEndColumn !== undefined;
    }

    /**
     * Get formatted selection range log string
     * @returns Formatted selection range log string: "Selection range: X"
     */
    getSelectionLog(): string {
        return LogFormatter.selectionLog(this.selectionStartLine, this.selectionStartColumn, this.selectionEndLine, this.selectionEndColumn);
    }

    /**
     * Get formatted cursor position log string
     * @returns Formatted cursor position log string: "Cursor position: line X, column Y"
     */
    getCursorLog(): string {
        return LogFormatter.cursorLog(this.line, this.column);
    }

    /**
     * Get formatted cursor position string
     * @return Formatted cursor position string
     */
    getCursor(): string {
        return LogFormatter.cursor(this.line, this.column) || "none";
    }
}


/**
 * Format timestamp to standard format
 * @param timestamp Timestamp (milliseconds)
 * @returns Formatted time string (yyyy-MM-dd HH:mm:ss.SSS)
 */
export function formatTimestamp(timestamp?: number): string {
    const date = new Date(timestamp || Date.now());
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    const hours = String(date.getHours()).padStart(2, '0');
    const minutes = String(date.getMinutes()).padStart(2, '0');
    const seconds = String(date.getSeconds()).padStart(2, '0');
    const milliseconds = String(date.getMilliseconds()).padStart(3, '0');

    return `${year}-${month}-${day} ${hours}:${minutes}:${seconds}.${milliseconds}`;
}

/**
 * Position formatting utility class
 * Provides unified formatting methods for cursor position and selection range
 */
export class LogFormatter {

    /**
     * Format cursor position
     * @param line Line number (starts from 0)
     * @param column Column number (starts from 0)
     * @returns Formatted cursor position string: "line X, column Y", returns null if parameters are undefined
     */
    static cursor(line?: number, column?: number): string | null {
        if (line === undefined || column === undefined) {
            return null;
        }
        return `line${line + 1},column${column + 1}`;
    }

    /**
     * Format cursor position log information
     * @param line Line number (starts from 0)
     * @param column Column number (starts from 0)
     * @returns Formatted cursor position log string: "Cursor position: line X, column Y"
     */
    static cursorLog(line?: number, column?: number): string {
        return `Cursor position: ${this.cursor(line, column) || "none"}`;
    }

    /**
     * Format selection range
     * @param startLine Start line number (starts from 0)
     * @param startColumn Start column number (starts from 0)
     * @param endLine End line number (starts from 0)
     * @param endColumn End column number (starts from 0)
     * @returns Formatted selection range string: "startLine,startColumn-endLine,endColumn", returns null if parameters are undefined
     */
    static selection(startLine?: number, startColumn?: number, endLine?: number, endColumn?: number): string | null {
        if (startLine === undefined || startColumn === undefined || endLine === undefined || endColumn === undefined) {
            return null;
        }
        return `${startLine + 1},${startColumn + 1}-${endLine + 1},${endColumn + 1}`;
    }

    /**
     * Format selection range log information
     * @param startLine Start line number (starts from 0)
     * @param startColumn Start column number (starts from 0)
     * @param endLine End line number (starts from 0)
     * @param endColumn End column number (starts from 0)
     * @returns Formatted selection range log string: "Selection range: X"
     */
    static selectionLog(startLine?: number, startColumn?: number, endLine?: number, endColumn?: number): string {
        return `Selection range: ${this.selection(startLine, startColumn, endLine, endColumn) || "none"}`;
    }
}

/**
 * Parse timestamp string to milliseconds
 * @param timestampStr Timestamp string
 * @returns Milliseconds
 */
export function parseTimestamp(timestampStr: string): number {
    return new Date(timestampStr).getTime();
}


/**
 * Connection state callback interface
 * Reference Kotlin version ConnectionCallback interface
 */
export interface ConnectionCallback {
    onConnected(): void;

    onDisconnected(): void;

    onReconnecting(): void;
}

/**
 * Message sender interface
 * Used to abstract message sending functionality of MulticastManager and TcpClientManager
 */
export interface MessageSender {
    sendMessage(messageWrapper: MessageWrapper): boolean;
}

/**
 * Message wrapper class
 * Used for unified packaging and processing of multicast messages
 */
export class MessageWrapper {
    private static messageSequence = 0;

    public messageId: string;
    public senderId: string;
    public timestamp: number;
    public payload: EditorState;

    constructor(messageId: string, senderId: string, timestamp: number, payload: EditorState) {
        this.messageId = messageId;
        this.senderId = senderId;
        this.timestamp = timestamp;
        this.payload = payload;
    }

    /**
     * Generate message ID
     * Format: {localIdentifier}-{sequence}-{timestamp}
     */
    static generateMessageId(localIdentifier: string): string {
        MessageWrapper.messageSequence++;
        const timestamp = Date.now();
        return `${localIdentifier}-${MessageWrapper.messageSequence}-${timestamp}`;
    }

    /**
     * Create message wrapper
     */
    static create(localIdentifier: string, payload: EditorState): MessageWrapper {
        return new MessageWrapper(
            MessageWrapper.generateMessageId(localIdentifier),
            localIdentifier,
            Date.now(),
            payload
        );
    }

    /**
     * Convert to JSON string
     */
    toJsonString(): string {
        return JSON.stringify(this);
    }

    /**
     * Parse MessageWrapper from JSON string
     */
    static fromJsonString(jsonString: string): MessageWrapper | null {
        try {
            const data = JSON.parse(jsonString);
            const editorState = new EditorState(
                data.payload.action,
                data.payload.filePath,
                data.payload.line,
                data.payload.column,
                data.payload.source,
                data.payload.isActive,
                data.payload.timestamp,
                data.payload.openedFiles,
                data.payload.selectionStartLine,
                data.payload.selectionStartColumn,
                data.payload.selectionEndLine,
                data.payload.selectionEndColumn
            );

            return new MessageWrapper(
                data.messageId,
                data.senderId,
                data.timestamp,
                editorState
            );
        } catch (error) {
            return null;
        }
    }

    /**
     * Check if message was sent by self
     */
    isOwnMessage(localIdentifier: string): boolean {
        return this.senderId === localIdentifier;
    }
}
