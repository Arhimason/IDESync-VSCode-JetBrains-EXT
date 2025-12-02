import * as vscode from 'vscode';

/**
 * Logger manager
 * Provides unified logging functionality
 */
export class Logger {
    private outputChannel: vscode.OutputChannel;

    constructor(channelName: string) {
        this.outputChannel = vscode.window.createOutputChannel(channelName);
    }

    private formatMessage(level: string, message: string): string {
        const timestamp = new Date().toISOString();
        return `[${timestamp}] [${level}] ${message}`;
    }

    info(message: string) {
        const formattedMessage = this.formatMessage('INFO', message);
        this.outputChannel.appendLine(formattedMessage);
    }

    warn(message: string, error?: Error) {
        const formattedMessage = this.formatMessage('WARN', message);
        this.outputChannel.appendLine(formattedMessage);
        if (error) {
            this.outputChannel.appendLine(this.formatMessage('ERROR', `Stack: ${error.stack}`));
        }
    }

    error(message: string, error?: Error) {
        const formattedMessage = this.formatMessage('ERROR', message);
        this.outputChannel.appendLine(formattedMessage);
        if (error) {
            this.outputChannel.appendLine(this.formatMessage('ERROR', `Stack: ${error.stack}`));
        }
    }

    debug(message: string) {
        const formattedMessage = this.formatMessage('DEBUG', message);
        this.outputChannel.appendLine(formattedMessage);
    }

    dispose() {
        this.outputChannel.dispose();
    }
} 