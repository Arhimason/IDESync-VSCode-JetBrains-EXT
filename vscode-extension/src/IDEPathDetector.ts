import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import {Logger} from './Logger';

/**
 * IDE Path Detector
 * Automatically detects installation paths for JetBrains IDEs
 */
export class IDEPathDetector {
    private readonly logger: Logger;
    private readonly platform: NodeJS.Platform;

    constructor(logger: Logger) {
        this.logger = logger;
        this.platform = process.platform;
    }

    /**
     * Detect JetBrains IDE path
     * Try different IDEs by priority (IntelliJ IDEA, WebStorm, PyCharm, Rider, GoLand, PhpStorm, CLion, RubyMine)
     */
    async detectJetBrainsPath(): Promise<string | null> {
        this.logger.info('Detecting JetBrains IDE path...');

        const detectedPath = await this.findFirstAvailableIDE();
        
        if (detectedPath) {
            this.logger.info(`Detected JetBrains IDE: ${detectedPath}`);
        } else {
            this.logger.warn('No JetBrains IDE detected');
        }

        return detectedPath;
    }

    /**
     * Find first available IDE
     */
    private async findFirstAvailableIDE(): Promise<string | null> {
        const candidates = this.getIDECandidates();

        for (const candidate of candidates) {
            const expandedPaths = await this.expandPath(candidate);
            for (const expandedPath of expandedPaths) {
                if (await this.fileExists(expandedPath)) {
                    return expandedPath;
                }
            }
        }

        return null;
    }

    /**
     * Get IDE candidate path list
     */
    private getIDECandidates(): string[] {
        switch (this.platform) {
            case 'darwin':
                return this.getMacOSCandidates();
            case 'win32':
                return this.getWindowsCandidates();
            case 'linux':
                return this.getLinuxCandidates();
            default:
                return [];
        }
    }

    /**
     * macOS IDE candidate paths
     */
    private getMacOSCandidates(): string[] {
        const home = os.homedir();
        return [
            // JetBrains Toolbox installation paths
            `${home}/Library/Application Support/JetBrains/Toolbox/scripts/idea`,
            `${home}/Library/Application Support/JetBrains/Toolbox/scripts/webstorm`,
            `${home}/Library/Application Support/JetBrains/Toolbox/scripts/pycharm`,
            `${home}/Library/Application Support/JetBrains/Toolbox/scripts/rider`,
            `${home}/Library/Application Support/JetBrains/Toolbox/scripts/goland`,
            `${home}/Library/Application Support/JetBrains/Toolbox/scripts/phpstorm`,
            `${home}/Library/Application Support/JetBrains/Toolbox/scripts/clion`,
            `${home}/Library/Application Support/JetBrains/Toolbox/scripts/rubymine`,
            
            // Standard installation paths - IntelliJ IDEA
            '/Applications/IntelliJ IDEA.app/Contents/MacOS/idea',
            '/Applications/IntelliJ IDEA CE.app/Contents/MacOS/idea',
            '/Applications/IntelliJ IDEA Ultimate.app/Contents/MacOS/idea',
            
            // Standard installation paths - WebStorm
            '/Applications/WebStorm.app/Contents/MacOS/webstorm',
            
            // Standard installation paths - PyCharm
            '/Applications/PyCharm.app/Contents/MacOS/pycharm',
            '/Applications/PyCharm CE.app/Contents/MacOS/pycharm',
            '/Applications/PyCharm Professional.app/Contents/MacOS/pycharm',
            
            // Standard installation paths - Rider
            '/Applications/Rider.app/Contents/MacOS/rider',
            
            // Standard installation paths - GoLand
            '/Applications/GoLand.app/Contents/MacOS/goland',
            
            // Standard installation paths - PhpStorm
            '/Applications/PhpStorm.app/Contents/MacOS/phpstorm',
            
            // Standard installation paths - CLion
            '/Applications/CLion.app/Contents/MacOS/clion',
            
            // Standard installation paths - RubyMine
            '/Applications/RubyMine.app/Contents/MacOS/rubymine',
            
            // User installation paths
            `${home}/Applications/IntelliJ IDEA.app/Contents/MacOS/idea`,
            `${home}/Applications/WebStorm.app/Contents/MacOS/webstorm`,
            `${home}/Applications/PyCharm.app/Contents/MacOS/pycharm`,
            `${home}/Applications/Rider.app/Contents/MacOS/rider`,
        ];
    }

    /**
     * Windows IDE candidate paths
     */
    private getWindowsCandidates(): string[] {
        const home = os.homedir();
        const programFiles = process.env['ProgramFiles'] || 'C:\\Program Files';
        const programFilesX86 = process.env['ProgramFiles(x86)'] || 'C:\\Program Files (x86)';
        const localAppData = process.env['LOCALAPPDATA'] || `${home}\\AppData\\Local`;

        return [
            // JetBrains Toolbox
            `${localAppData}\\JetBrains\\Toolbox\\scripts\\idea.cmd`,
            `${localAppData}\\JetBrains\\Toolbox\\scripts\\webstorm.cmd`,
            `${localAppData}\\JetBrains\\Toolbox\\scripts\\pycharm.cmd`,
            `${localAppData}\\JetBrains\\Toolbox\\scripts\\rider.cmd`,
            `${localAppData}\\JetBrains\\Toolbox\\scripts\\goland.cmd`,
            `${localAppData}\\JetBrains\\Toolbox\\scripts\\phpstorm.cmd`,
            
            // Standard installation paths (using wildcard patterns)
            `${programFiles}\\JetBrains\\IntelliJ IDEA*\\bin\\idea64.exe`,
            `${programFiles}\\JetBrains\\WebStorm*\\bin\\webstorm64.exe`,
            `${programFiles}\\JetBrains\\PyCharm*\\bin\\pycharm64.exe`,
            `${programFiles}\\JetBrains\\Rider*\\bin\\rider64.exe`,
            `${programFiles}\\JetBrains\\GoLand*\\bin\\goland64.exe`,
            `${programFiles}\\JetBrains\\PhpStorm*\\bin\\phpstorm64.exe`,
            `${programFiles}\\JetBrains\\CLion*\\bin\\clion64.exe`,
            
            // x86 paths
            `${programFilesX86}\\JetBrains\\IntelliJ IDEA*\\bin\\idea64.exe`,
            `${programFilesX86}\\JetBrains\\WebStorm*\\bin\\webstorm64.exe`,
        ];
    }

    /**
     * Linux IDE candidate paths
     */
    private getLinuxCandidates(): string[] {
        const home = os.homedir();

        return [
            // JetBrains Toolbox
            `${home}/.local/share/JetBrains/Toolbox/scripts/idea`,
            `${home}/.local/share/JetBrains/Toolbox/scripts/webstorm`,
            `${home}/.local/share/JetBrains/Toolbox/scripts/pycharm`,
            `${home}/.local/share/JetBrains/Toolbox/scripts/rider`,
            `${home}/.local/share/JetBrains/Toolbox/scripts/goland`,
            `${home}/.local/share/JetBrains/Toolbox/scripts/phpstorm`,
            
            // Snap installations
            '/snap/intellij-idea-ultimate/current/bin/idea.sh',
            '/snap/intellij-idea-community/current/bin/idea.sh',
            '/snap/webstorm/current/bin/webstorm.sh',
            '/snap/pycharm-professional/current/bin/pycharm.sh',
            '/snap/pycharm-community/current/bin/pycharm.sh',
            
            // Manual installation paths
            `/opt/idea/bin/idea.sh`,
            `/opt/idea-*/bin/idea.sh`,
            `/opt/webstorm/bin/webstorm.sh`,
            `/opt/pycharm/bin/pycharm.sh`,
            `${home}/idea/bin/idea.sh`,
            `${home}/.local/idea/bin/idea.sh`,
            
            // Standard binary paths
            '/usr/bin/idea',
            '/usr/bin/webstorm',
            '/usr/bin/pycharm',
            '/usr/local/bin/idea',
            '/usr/local/bin/webstorm',
            '/usr/local/bin/pycharm',
        ];
    }

    /**
     * Expand paths containing wildcards
     */
    private async expandPath(pattern: string): Promise<string[]> {
        if (!pattern.includes('*')) {
            return [pattern];
        }

        const results: string[] = [];
        const parts = pattern.split(path.sep);
        let currentPaths = [''];

        for (const part of parts) {
            if (part === '') {
                currentPaths = [path.sep];
                continue;
            }

            const newPaths: string[] = [];

            for (const currentPath of currentPaths) {
                if (part.includes('*')) {
                    // Expand wildcard
                    const dir = currentPath || '.';
                    try {
                        const entries = await fs.promises.readdir(dir);
                        const regex = new RegExp('^' + part.replace(/\*/g, '.*') + '$');
                        
                        for (const entry of entries) {
                            if (regex.test(entry)) {
                                newPaths.push(path.join(currentPath, entry));
                            }
                        }
                    } catch {
                        // Directory doesn't exist, skip
                    }
                } else {
                    newPaths.push(path.join(currentPath, part));
                }
            }

            currentPaths = newPaths;
        }

        return currentPaths;
    }

    /**
     * Check if file is executable
     */
    private async fileExists(filePath: string): Promise<boolean> {
        try {
            await fs.promises.access(filePath, fs.constants.X_OK);
            return true;
        } catch {
            return false;
        }
    }

    /**
     * Detect VSCode path (for JetBrains use)
     */
    async detectVSCodePath(): Promise<string | null> {
        const candidates = this.getVSCodeCandidates();

        for (const candidate of candidates) {
            const expandedPaths = await this.expandPath(candidate);
            for (const expandedPath of expandedPaths) {
                if (await this.fileExists(expandedPath)) {
                    return expandedPath;
                }
            }
        }

        return null;
    }

    /**
     * Get VSCode/Cursor/Windsurf candidate paths
     */
    private getVSCodeCandidates(): string[] {
        const home = os.homedir();

        switch (this.platform) {
            case 'darwin':
                return [
                    '/Applications/Visual Studio Code.app/Contents/Resources/app/bin/code',
                    '/Applications/Cursor.app/Contents/Resources/app/bin/cursor',
                    '/Applications/Windsurf.app/Contents/Resources/app/bin/windsurf',
                    `${home}/Applications/Visual Studio Code.app/Contents/Resources/app/bin/code`,
                    `${home}/Applications/Cursor.app/Contents/Resources/app/bin/cursor`,
                    `${home}/Applications/Windsurf.app/Contents/Resources/app/bin/windsurf`,
                    // CLI commands
                    '/usr/local/bin/code',
                    '/usr/local/bin/cursor',
                    '/usr/local/bin/windsurf',
                ];

            case 'win32':
                const localAppData = process.env['LOCALAPPDATA'] || `${home}\\AppData\\Local`;
                return [
                    `${localAppData}\\Programs\\Microsoft VS Code\\bin\\code.cmd`,
                    `${localAppData}\\Programs\\cursor\\Cursor.exe`,
                    `${localAppData}\\Programs\\Windsurf\\Windsurf.exe`,
                    'C:\\Program Files\\Microsoft VS Code\\bin\\code.cmd',
                ];

            case 'linux':
                return [
                    '/usr/bin/code',
                    '/usr/bin/cursor',
                    '/usr/bin/windsurf',
                    '/snap/code/current/bin/code',
                    `${home}/.local/bin/code`,
                ];

            default:
                return [];
        }
    }

    /**
     * Detect all available JetBrains IDEs
     * Returns a list of detected IDEs with names and paths
     */
    async detectAllJetBrainsPaths(): Promise<DetectedIDE[]> {
        this.logger.info('Detecting all JetBrains IDEs...');
        const results: DetectedIDE[] = [];
        const candidates = this.getIDECandidates();

        for (const candidate of candidates) {
            const expandedPaths = await this.expandPath(candidate);
            for (const expandedPath of expandedPaths) {
                if (await this.fileExists(expandedPath)) {
                    const name = this.getIDENameFromPath(expandedPath);
                    // Avoid duplicates by name
                    if (!results.find(r => r.name === name)) {
                        results.push({ name, path: expandedPath });
                        this.logger.info(`Detected IDE: ${name} at ${expandedPath}`);
                    }
                }
            }
        }

        return results;
    }

    /**
     * Get IDE name from path
     */
    private getIDENameFromPath(idePath: string): string {
        const lowerPath = idePath.toLowerCase();
        
        if (lowerPath.includes('rider')) return 'Rider';
        if (lowerPath.includes('webstorm')) return 'WebStorm';
        if (lowerPath.includes('pycharm')) return 'PyCharm';
        if (lowerPath.includes('goland')) return 'GoLand';
        if (lowerPath.includes('phpstorm')) return 'PhpStorm';
        if (lowerPath.includes('clion')) return 'CLion';
        if (lowerPath.includes('rubymine')) return 'RubyMine';
        if (lowerPath.includes('idea')) return 'IntelliJ IDEA';
        
        // Extract filename
        const basename = path.basename(idePath);
        const name = basename.replace(/\.(exe|cmd|sh|app)$/i, '');
        return name.charAt(0).toUpperCase() + name.slice(1);
    }

    /**
     * Resolve a command name or path to an executable path
     * Supports:
     * - Full paths (e.g., "/opt/idea/bin/idea.sh")
     * - Command names (e.g., "idea", "webstorm", "pycharm")
     */
    async resolveIDEPath(pathOrCommand: string): Promise<string | null> {
        if (!pathOrCommand || pathOrCommand.trim() === '') {
            return null;
        }

        // First, check if it's already a valid executable path
        if (await this.fileExists(pathOrCommand)) {
            this.logger.info(`Path is already executable: ${pathOrCommand}`);
            return pathOrCommand;
        }

        // Check if it looks like a command name (no path separators)
        if (!pathOrCommand.includes(path.sep) && !pathOrCommand.includes('/') && !pathOrCommand.includes('\\')) {
            this.logger.info(`Attempting to resolve command name: ${pathOrCommand}`);
            
            // Try to find in PATH using which/where
            const resolvedPath = await this.findCommandInPath(pathOrCommand);
            if (resolvedPath) {
                this.logger.info(`Resolved command '${pathOrCommand}' to: ${resolvedPath}`);
                return resolvedPath;
            }

            // Try to find in known IDE paths
            const knownPath = await this.findInKnownPaths(pathOrCommand);
            if (knownPath) {
                this.logger.info(`Found '${pathOrCommand}' in known paths: ${knownPath}`);
                return knownPath;
            }
        }

        this.logger.warn(`Could not resolve IDE path: ${pathOrCommand}`);
        return null;
    }

    /**
     * Find command in system PATH using which (Unix) or where (Windows)
     */
    private async findCommandInPath(command: string): Promise<string | null> {
        const { exec } = require('child_process');
        const util = require('util');
        const execAsync = util.promisify(exec);

        try {
            const cmd = this.platform === 'win32' ? `where ${command}` : `which ${command}`;
            const { stdout } = await execAsync(cmd);
            const result = stdout.trim().split('\n')[0];
            
            if (result && await this.fileExists(result)) {
                return result;
            }
        } catch {
            // Command not found in PATH
        }

        return null;
    }

    /**
     * Find command in known IDE installation paths
     */
    private async findInKnownPaths(command: string): Promise<string | null> {
        const lowerCommand = command.toLowerCase();
        const candidates = this.getIDECandidates();

        for (const candidate of candidates) {
            const expandedPaths = await this.expandPath(candidate);
            for (const expandedPath of expandedPaths) {
                const lowerPath = expandedPath.toLowerCase();
                // Match by command name in the path
                if (lowerPath.includes(lowerCommand) && await this.fileExists(expandedPath)) {
                    return expandedPath;
                }
            }
        }

        return null;
    }
}

/**
 * Detected IDE information export
 */
export interface DetectedIDE {
    name: string;
    path: string;
}
