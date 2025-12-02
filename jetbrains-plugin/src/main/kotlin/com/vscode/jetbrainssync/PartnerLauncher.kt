package com.vscode.jetbrainssync

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Partner IDE Launcher
 * Responsible for detecting and launching VSCode/Cursor/Windsurf
 */
class PartnerLauncher(private val project: Project) {
    private val log: Logger = Logger.getInstance(PartnerLauncher::class.java)
    private val idePathDetector = IDEPathDetector()
    private var launchAttempted = false

    /**
     * Check and prompt to launch partner IDE
     * @param onLaunchComplete Callback after launch is complete (used for re-scanning)
     */
    fun checkAndPromptLaunch(onLaunchComplete: () -> Unit) {
        // Avoid repeated prompts
        if (launchAttempted) {
            return
        }

        val settings = VSCodeJetBrainsSyncSettings.getInstance(project)
        val autoLaunch = settings.state.autoLaunchPartner

        if (autoLaunch) {
            // Automatic launch mode
            launchPartnerIDE(onLaunchComplete)
        } else {
            // Prompt user
            promptLaunch(onLaunchComplete)
        }
    }

    /**
     * Prompt user to launch partner IDE
     */
    private fun promptLaunch(onLaunchComplete: () -> Unit) {
        // Mark as attempted - will be reset after timeout to allow subsequent notifications
        launchAttempted = true
        
        // Reset after 30 seconds to allow re-prompting on continued failure
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                TimeUnit.SECONDS.sleep(30)
                launchAttempted = false
            } catch (e: InterruptedException) {
                // Ignore
            }
        }

        ApplicationManager.getApplication().invokeLater {
            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup("VSCode JetBrains Sync")
                .createNotification(
                    "IDE Sync",
                    "No VSCode/Cursor/Windsurf found with matching project. Would you like to launch it?",
                    NotificationType.INFORMATION
                )

            notification.addAction(object : com.intellij.notification.NotificationAction("Launch VSCode") {
                override fun actionPerformed(
                    e: AnActionEvent,
                    notification: com.intellij.notification.Notification
                ) {
                    notification.expire()
                    launchPartnerIDE(onLaunchComplete)
                }
            })

            notification.addAction(object : com.intellij.notification.NotificationAction("Configure Path") {
                override fun actionPerformed(
                    e: AnActionEvent,
                    notification: com.intellij.notification.Notification
                ) {
                    notification.expire()
                    com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                        .showSettingsDialog(project, "IDE Sync - Connect to VSCode")
                }
            })

            notification.addAction(object : com.intellij.notification.NotificationAction("Cancel") {
                override fun actionPerformed(
                    e: AnActionEvent,
                    notification: com.intellij.notification.Notification
                ) {
                    notification.expire()
                    log.info("User cancelled launching partner IDE")
                }
            })

            notification.notify(project)
        }
    }

    /**
     * Launch partner IDE
     */
    fun launchPartnerIDE(onLaunchComplete: (() -> Unit)? = null): Boolean {
        try {
            val idePath = getPartnerIDEPath()

            if (idePath == null) {
                showError("Could not find VSCode/Cursor/Windsurf. Please configure the path manually.")
                return false
            }

            val workspacePath = project.basePath
            if (workspacePath == null) {
                showError("No project path available")
                return false
            }

            log.info("Launching VSCode: $idePath $workspacePath")

            // Build launch command
            val processBuilder = ProcessBuilder(idePath, workspacePath)
            processBuilder.redirectErrorStream(true)
            
            // Launch process
            val process = processBuilder.start()

            // Show notification
            showInfo("Launching VSCode... Sync will connect automatically when ready.")

            // Delayed callback (give IDE time to launch)
            if (onLaunchComplete != null) {
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        TimeUnit.SECONDS.sleep(8)
                        ApplicationManager.getApplication().invokeLater {
                            log.info("Launch wait complete, triggering re-scan")
                            onLaunchComplete()
                        }
                    } catch (e: InterruptedException) {
                        // Ignore
                    }
                }
            }

            return true

        } catch (e: Exception) {
            log.error("Failed to launch partner IDE: ${e.message}", e)
            showError("Failed to launch VSCode: ${e.message}")
            return false
        }
    }

    /**
     * Get IDE path
     */
    private fun getPartnerIDEPath(): String? {
        // First check user configuration
        val settings = VSCodeJetBrainsSyncSettings.getInstance(project)
        val configuredPath = settings.state.partnerIDEPath

        if (configuredPath.isNotBlank()) {
            log.info("Using configured IDE path: $configuredPath")
            // Use resolveIDEPath to support both full paths and command names (e.g., "windsurf", "cursor")
            val resolvedPath = idePathDetector.resolveIDEPath(configuredPath)
            if (resolvedPath != null) {
                log.info("Resolved IDE path: $resolvedPath")
                return resolvedPath
            } else {
                log.warn("Could not resolve configured IDE path: $configuredPath")
            }
        }

        // Auto-detect
        log.info("No IDE path configured or configured path not found, trying auto-detection...")
        val detectedPath = idePathDetector.detectVSCodePath()
        
        // Save detected path to settings for future use
        if (detectedPath != null && configuredPath.isBlank()) {
            settings.state.partnerIDEPath = detectedPath
            log.info("Saved auto-detected IDE path to settings: $detectedPath")
        }
        
        return detectedPath
    }

    /**
     * Show error notification
     */
    private fun showError(message: String) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("VSCode JetBrains Sync")
                .createNotification("IDE Sync Error", message, NotificationType.ERROR)
                .notify(project)
        }
    }

    /**
     * Show info notification
     */
    private fun showInfo(message: String) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("VSCode JetBrains Sync")
                .createNotification("IDE Sync", message, NotificationType.INFORMATION)
                .notify(project)
        }
    }

    /**
     * Reset launch attempt state (allow prompting again)
     */
    fun resetLaunchAttempt() {
        launchAttempted = false
    }
}
