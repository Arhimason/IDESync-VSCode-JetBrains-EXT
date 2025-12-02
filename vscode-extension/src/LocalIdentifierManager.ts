import * as os from 'os';
import * as crypto from 'crypto';
import * as vscode from 'vscode';

/**
 * Local identifier manager
 * Responsible for generating and managing local unique identifiers for use by various components
 * Supports multi-project instances, distinguishes different IDEA project windows by project path
 * Supports VSCode multi-version, distinguishes different processes of the same project by PID
 */
export class LocalIdentifierManager {
    private static _instance: LocalIdentifierManager | null = null;
    private readonly _identifier: string;

    private constructor() {
        this._identifier = this.generateLocalIdentifier();
    }

    /**
     * Get singleton instance
     */
    static getInstance(): LocalIdentifierManager {
        if (!LocalIdentifierManager._instance) {
            LocalIdentifierManager._instance = new LocalIdentifierManager();
        }
        return LocalIdentifierManager._instance;
    }

    /**
     * Get local unique identifier
     */
    get identifier(): string {
        return this._identifier;
    }

    /**
     * Generate local unique identifier
     * Format: hostname-projectHash-pid
     * - projectHash: Solves the problem of same PID for IDEA multi-project windows
     * - pid: Solves the problem of different PIDs for VSCode multi-version same project
     */
    private generateLocalIdentifier(): string {
        try {
            const hostname = os.hostname();
            const projectHash = this.generateProjectHash();
            const pid = process.pid;
            return `${hostname}-${projectHash}-${pid}`;
        } catch (e) {
            return `unknown-${Date.now()}-${Math.floor(Math.random() * 10000)}`;
        }
    }

    /**
     * Generate project hash
     * Generate short hash based on project path, used to distinguish different project instances
     */
    private generateProjectHash(): string {
        try {
            // Get VSCode workspace path
            const workspaceFolders = vscode.workspace.workspaceFolders;
            const projectPath = workspaceFolders && workspaceFolders.length > 0
                ? workspaceFolders[0].uri.fsPath
                : 'unknown-project';

            // Generate MD5 hash
            const hash = crypto.createHash('md5');
            hash.update(projectPath);
            const hashBytes = hash.digest();

            // Take first 3 bytes (6 hexadecimal characters) as project hash
            return hashBytes.subarray(0, 3).toString('hex');
        } catch (e) {
            // If hash generation fails, use last 6 digits of timestamp as fallback
            return Date.now().toString().slice(-6);
        }
    }
}