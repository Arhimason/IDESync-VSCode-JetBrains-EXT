import * as vscode from 'vscode';
import {spawn} from 'child_process';
import {Logger} from './Logger';
import {IDEPathDetector} from './IDEPathDetector';

/**
 * Partner IDE Launcher
 * Responsible for detecting and launching JetBrains IDEs
 */
export class PartnerLauncher {
    private readonly logger: Logger;
    private readonly idePathDetector: IDEPathDetector;
    private launchAttempted = false;

    constructor(logger: Logger) {
        this.logger = logger;
        this.idePathDetector = new IDEPathDetector(logger);
    }

    /**
     * Check and prompt to launch partner IDE
     * @param onLaunchComplete Callback after launch is complete (used for re-scanning)
     */
    async checkAndPromptLaunch(onLaunchComplete: () => void): Promise<void> {
        // Avoid repeated prompts
        if (this.launchAttempted) {
            return;
        }

        const settings = vscode.workspace.getConfiguration('vscode-jetbrains-sync');
        const autoLaunch = settings.get<boolean>('autoLaunchPartner', false);

        if (autoLaunch) {
            // Auto-launch mode
            await this.launchPartnerIDE(onLaunchComplete);
        } else {
            // Prompt user
            await this.promptLaunch(onLaunchComplete);
        }
    }

    /**
     * Prompt user to launch partner IDE
     */
    private async promptLaunch(onLaunchComplete: () => void): Promise<void> {
        // Mark as attempted - will be reset after timeout to allow subsequent notifications
        this.launchAttempted = true;
        
        // Reset after 30 seconds to allow re-prompting on continued failure
        setTimeout(() => {
            this.launchAttempted = false;
        }, 30000);

        const action = await vscode.window.showInformationMessage(
            'No JetBrains IDE found with matching project. Would you like to launch it?',
            'Launch JetBrains IDE',
            'Configure Path',
            'Cancel'
        );

        switch (action) {
            case 'Launch JetBrains IDE':
                await this.launchPartnerIDE(onLaunchComplete);
                break;
            case 'Configure Path':
                vscode.commands.executeCommand(
                    'workbench.action.openSettings',
                    'vscode-jetbrains-sync.partnerIDEPath'
                );
                break;
            case 'Cancel':
            default:
                this.logger.info('User cancelled launching partner IDE');
        }
    }

    /**
     * Launch partner IDE
     */
    async launchPartnerIDE(onLaunchComplete?: () => void): Promise<boolean> {
        try {
            const idePath = await this.getPartnerIDEPath();
            
            if (!idePath) {
                vscode.window.showErrorMessage(
                    'Could not find JetBrains IDE. Please configure the path manually.',
                    'Open Settings'
                ).then(action => {
                    if (action === 'Open Settings') {
                        vscode.commands.executeCommand(
                            'workbench.action.openSettings',
                            'vscode-jetbrains-sync.partnerIDEPath'
                        );
                    }
                });
                return false;
            }

            const workspacePath = this.getWorkspacePath();
            if (!workspacePath) {
                vscode.window.showErrorMessage('No workspace folder open');
                return false;
            }

            this.logger.info(`Launching JetBrains IDE: ${idePath} ${workspacePath}`);

            // Launch IDE
            const process = spawn(idePath, [workspacePath], {
                detached: true,
                stdio: 'ignore',
                shell: false
            });

            process.unref();

            // Listen for errors
            process.on('error', (error) => {
                this.logger.warn(`Failed to launch JetBrains IDE: ${error.message}`);
                vscode.window.showErrorMessage(`Failed to launch JetBrains IDE: ${error.message}`);
            });

            // Show notification
            vscode.window.showInformationMessage(
                'Launching JetBrains IDE... Sync will connect automatically when ready.'
            );

            // Delayed callback (give IDE time to launch)
            if (onLaunchComplete) {
                setTimeout(() => {
                    this.logger.info('Launch waiting complete, triggering re-scan');
                    onLaunchComplete();
                }, 8000); // Wait 8 seconds
            }

            return true;

        } catch (error) {
            this.logger.error(`Failed to launch partner IDE: ${error}`);
            return false;
        }
    }

    /**
     * Get partner IDE path
     */
    private async getPartnerIDEPath(): Promise<string | null> {
        // First check user configuration
        const settings = vscode.workspace.getConfiguration('vscode-jetbrains-sync');
        const configuredPath = settings.get<string>('partnerIDEPath', '');

        if (configuredPath && configuredPath.trim() !== '') {
            this.logger.info(`Using configured IDE path: ${configuredPath}`);
            // Use resolveIDEPath to support both full paths and command names (e.g., "idea", "pycharm")
            const resolvedPath = await this.idePathDetector.resolveIDEPath(configuredPath);
            if (resolvedPath) {
                this.logger.info(`Resolved IDE path: ${resolvedPath}`);
                return resolvedPath;
            } else {
                this.logger.warn(`Could not resolve configured IDE path: ${configuredPath}`);
                // Show error and offer to detect IDEs
                const detect = await vscode.window.showErrorMessage(
                    `Could not find JetBrains IDE at: ${configuredPath}`,
                    'Detect IDEs', 'Cancel'
                );
                if (detect === 'Detect IDEs') {
                    vscode.commands.executeCommand('vscode-jetbrains-sync.detectIDEs');
                }
            }
        }

        // Auto-detect
        this.logger.info('No IDE path configured or configured path not found, trying auto-detection...');
        const detectedPath = await this.idePathDetector.detectJetBrainsPath();
        
        // Save detected path to settings for future use
        if (detectedPath && (!configuredPath || configuredPath.trim() === '')) {
            try {
                await settings.update('partnerIDEPath', detectedPath, vscode.ConfigurationTarget.Workspace);
                this.logger.info(`Saved auto-detected IDE path to settings: ${detectedPath}`);
            } catch (e) {
                this.logger.warn(`Failed to save detected path to settings: ${e}`);
            }
        }
        
        return detectedPath;
    }

    /**
     * Get workspace path
     */
    private getWorkspacePath(): string | null {
        const workspaceFolders = vscode.workspace.workspaceFolders;
        if (!workspaceFolders || workspaceFolders.length === 0) {
            return null;
        }
        return workspaceFolders[0].uri.fsPath;
    }

    /**
     * Reset launch attempt state (allow prompting again)
     */
    resetLaunchAttempt(): void {
        this.launchAttempted = false;
    }
}
