package com.vscode.jetbrainssync

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.*

class VSCodeJetBrainsSyncConfigurable(private val project: Project) : Configurable {
    private var useCustomPortCheckBox: JCheckBox? = null
    private var customPortSpinner: JSpinner? = null
    private var autoStartCheckBox: JCheckBox? = null
    private var autoLaunchCheckBox: JCheckBox? = null
    private var partnerPathField: JTextField? = null
    private var detectedIDEsComboBox: JComboBox<String>? = null
    private var settings: VSCodeJetBrainsSyncSettings = VSCodeJetBrainsSyncSettings.getInstance(project)
    private val idePathDetector = IDEPathDetector()
    private var detectedIDEs: List<IDEPathDetector.DetectedIDE> = emptyList()

    override fun getDisplayName(): String = "IDE Sync - Connect to VSCode"

    override fun createComponent(): JComponent {
        // Create custom port components
        useCustomPortCheckBox = JCheckBox("Use custom port")
        useCustomPortCheckBox?.addActionListener {
            customPortSpinner?.isEnabled = useCustomPortCheckBox?.isSelected == true
        }
        
        val customPortModel = SpinnerNumberModel(settings.state.customPort, 1024, 65535, 1)
        customPortSpinner = JSpinner(customPortModel)
        customPortSpinner?.isEnabled = false // Disabled by default

        // Configure spinner to not use thousand separators
        val editor = customPortSpinner?.editor as? JSpinner.NumberEditor
        editor?.let {
            val format = it.format
            format.isGroupingUsed = false
            it.textField.columns = 5
        }

        // Create auto start checkbox
        autoStartCheckBox = JCheckBox("Automatically start sync when IDE opens (default: disabled, sync must be manually enabled).")

        val panel = JPanel(BorderLayout())

        // Create content panel with all components
        val contentPanel = JPanel()
        contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)

        // Add description label
        val descriptionLabel = JLabel("Configure the connection settings for synchronization with VSCode.")
        descriptionLabel.alignmentX = Component.LEFT_ALIGNMENT
        contentPanel.add(descriptionLabel)
        contentPanel.add(Box.createVerticalStrut(10))

        // Add port settings section
        val portLabel = JLabel("Connection Port Settings:")
        portLabel.alignmentX = Component.LEFT_ALIGNMENT
        contentPanel.add(portLabel)
        contentPanel.add(Box.createVerticalStrut(8))

        // Add use custom port checkbox
        useCustomPortCheckBox?.alignmentX = Component.LEFT_ALIGNMENT
        contentPanel.add(useCustomPortCheckBox)
        contentPanel.add(Box.createVerticalStrut(4))

        // Add custom port input panel
        val portPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        portPanel.alignmentX = Component.LEFT_ALIGNMENT
        portPanel.add(JLabel("Custom Port: "))
        portPanel.add(Box.createHorizontalStrut(10))
        portPanel.add(customPortSpinner)
        contentPanel.add(portPanel)
        contentPanel.add(Box.createVerticalStrut(8))

        // Add hint
        val hintLabel = JLabel("<html><i>Tip: Leave 'Use custom port' disabled to automatically select an available port and avoid conflicts</i></html>")
        hintLabel.alignmentX = Component.LEFT_ALIGNMENT
        contentPanel.add(hintLabel)
        contentPanel.add(Box.createVerticalStrut(16))

        // Add auto start checkbox immediately after port
        autoStartCheckBox?.alignmentX = Component.LEFT_ALIGNMENT
        contentPanel.add(autoStartCheckBox)
        contentPanel.add(Box.createVerticalStrut(16))

        // Add separator
        contentPanel.add(JSeparator())
        contentPanel.add(Box.createVerticalStrut(16))

        // Add auto launch section
        val autoLaunchLabel = JLabel("Partner IDE Auto-Launch Settings:")
        autoLaunchLabel.alignmentX = Component.LEFT_ALIGNMENT
        contentPanel.add(autoLaunchLabel)
        contentPanel.add(Box.createVerticalStrut(8))

        // Add auto launch checkbox
        autoLaunchCheckBox = JCheckBox("Automatically launch VSCode/Cursor/Windsurf if not found when sync starts")
        autoLaunchCheckBox?.alignmentX = Component.LEFT_ALIGNMENT
        contentPanel.add(autoLaunchCheckBox)
        contentPanel.add(Box.createVerticalStrut(12))

        // Add detected IDEs dropdown
        val detectedLabel = JLabel("Detected IDEs (select to use):")
        detectedLabel.alignmentX = Component.LEFT_ALIGNMENT
        contentPanel.add(detectedLabel)
        contentPanel.add(Box.createVerticalStrut(4))

        // Create combo box with detected IDEs
        detectedIDEsComboBox = JComboBox<String>()
        detectedIDEsComboBox?.alignmentX = Component.LEFT_ALIGNMENT
        detectedIDEsComboBox?.maximumSize = detectedIDEsComboBox?.preferredSize
        
        val comboPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        comboPanel.alignmentX = Component.LEFT_ALIGNMENT
        comboPanel.add(detectedIDEsComboBox)
        
        // Add refresh button
        val refreshButton = JButton("Refresh")
        refreshButton.addActionListener {
            refreshDetectedIDEs()
        }
        comboPanel.add(Box.createHorizontalStrut(8))
        comboPanel.add(refreshButton)
        contentPanel.add(comboPanel)
        contentPanel.add(Box.createVerticalStrut(12))

        // Add partner path input (manual override)
        val pathLabel = JLabel("Partner IDE Path (manual override or selected from above):")
        pathLabel.alignmentX = Component.LEFT_ALIGNMENT
        contentPanel.add(pathLabel)
        contentPanel.add(Box.createVerticalStrut(4))

        partnerPathField = JTextField(40)
        partnerPathField?.alignmentX = Component.LEFT_ALIGNMENT
        val pathPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        pathPanel.alignmentX = Component.LEFT_ALIGNMENT
        pathPanel.add(partnerPathField)
        
        // Add browse button
        val browseButton = JButton("Browse...")
        browseButton.addActionListener {
            val fileChooser = JFileChooser()
            fileChooser.fileSelectionMode = JFileChooser.FILES_ONLY
            if (fileChooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                partnerPathField?.text = fileChooser.selectedFile.absolutePath
            }
        }
        pathPanel.add(Box.createHorizontalStrut(8))
        pathPanel.add(browseButton)
        contentPanel.add(pathPanel)
        
        // Add hint
        val pathHintLabel = JLabel("<html><i>Tip: You can also enter command names like 'windsurf', 'cursor', or 'code'</i></html>")
        pathHintLabel.alignmentX = Component.LEFT_ALIGNMENT
        contentPanel.add(Box.createVerticalStrut(4))
        contentPanel.add(pathHintLabel)

        // Setup combo box selection listener - writes selected path to manual field
        detectedIDEsComboBox?.addActionListener {
            val selectedIndex = detectedIDEsComboBox?.selectedIndex ?: -1
            if (selectedIndex > 0 && selectedIndex <= detectedIDEs.size) {
                // Index 0 is "-- Select IDE --", so actual IDEs start at index 1
                val selectedIDE = detectedIDEs[selectedIndex - 1]
                partnerPathField?.text = selectedIDE.path
            }
        }

        // Add content panel to the top of BorderLayout
        panel.add(contentPanel, BorderLayout.NORTH)

        // Initial load of detected IDEs
        refreshDetectedIDEs()

        reset()
        return panel
    }

    /**
     * Refresh the list of detected IDEs
     */
    private fun refreshDetectedIDEs() {
        ApplicationManager.getApplication().executeOnPooledThread {
            detectedIDEs = idePathDetector.detectAllVSCodePaths()
            
            ApplicationManager.getApplication().invokeLater {
                detectedIDEsComboBox?.removeAllItems()
                detectedIDEsComboBox?.addItem("-- Select IDE --")
                
                for (ide in detectedIDEs) {
                    detectedIDEsComboBox?.addItem("${ide.name} (${ide.path})")
                }
                
                // Try to select the currently configured path
                val currentPath = partnerPathField?.text ?: ""
                if (currentPath.isNotBlank()) {
                    val matchIndex = detectedIDEs.indexOfFirst { it.path == currentPath }
                    if (matchIndex >= 0) {
                        detectedIDEsComboBox?.selectedIndex = matchIndex + 1
                    }
                }
            }
        }
    }

    override fun isModified(): Boolean {
        return try {
            val useCustomPortChanged = useCustomPortCheckBox?.isSelected != settings.state.useCustomPort
            val customPortChanged = customPortSpinner?.value as? Int != settings.state.customPort
            val autoStartChanged = autoStartCheckBox?.isSelected != settings.state.autoStartSync
            val autoLaunchChanged = autoLaunchCheckBox?.isSelected != settings.state.autoLaunchPartner
            val partnerPathChanged = partnerPathField?.text != settings.state.partnerIDEPath
            useCustomPortChanged || customPortChanged || autoStartChanged || autoLaunchChanged || partnerPathChanged
        } catch (e: NumberFormatException) {
            true
        }
    }

    override fun apply() {
        settings.state.useCustomPort = useCustomPortCheckBox?.isSelected ?: false
        settings.state.customPort = customPortSpinner?.value as? Int ?: 3000
        settings.state.autoStartSync = autoStartCheckBox?.isSelected ?: false
        settings.state.autoLaunchPartner = autoLaunchCheckBox?.isSelected ?: false
        settings.state.partnerIDEPath = partnerPathField?.text ?: ""
        // Restart TCP server to apply new port settings
        project.service<VSCodeJetBrainsSyncService>().restartConnection()
    }

    override fun reset() {
        useCustomPortCheckBox?.isSelected = settings.state.useCustomPort
        customPortSpinner?.value = settings.state.customPort
        customPortSpinner?.isEnabled = settings.state.useCustomPort
        autoStartCheckBox?.isSelected = settings.state.autoStartSync
        autoLaunchCheckBox?.isSelected = settings.state.autoLaunchPartner
        partnerPathField?.text = settings.state.partnerIDEPath
    }
} 