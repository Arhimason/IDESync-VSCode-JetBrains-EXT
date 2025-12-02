# IDE Sync - VSCode-JetBrains IDE Sync

![IDE Sync Demo](https://github.com/ileeoyo/IDESync-VSCode-JetBrains/raw/main/20250724.gif)

## 🆕 Key Improvements from Original Project

This enhanced version includes significant improvements over the original IDE Sync:

### ✨ New Features
- **🚀 Auto Launch Partner IDE**: Automatically launches the partner IDE when sync starts if not already running
- **🔗 Multiple Synced Pairs Support**: Connect multiple VSCode and JetBrains IDE instances simultaneously with independent sync groups
- **📁 Workspace-Based Auto Discovery**: Automatically discovers and pairs IDEs based on workspace folder matching for precise project synchronization
- **🔌 Smart Port Selection**: Uses automatic port selection within a configurable range to avoid conflicts, or custom port configuration for advanced users

### 🌐 Localization & UX
- **🇬🇧 Fully Translated to English**: Complete translation of all UI elements, log messages, and documentation from Russian to English
- **🎨 Enhanced UI**: Improved settings interfaces with dropdown selections for detected IDEs and better user guidance
- **💡 Smart IDE Detection**: Automatic detection of available JetBrains IDEs and VSCode forks with one-click selection

### 🏗️ Architecture Improvements
- **🔄 Pure TCP Architecture**: Removed WebSocket dependencies for cleaner, more reliable communication
- **⚡ Better Error Handling**: Improved connection management with automatic reconnection and better error reporting
- **🛠️ Flexible Configuration**: Granular control over port settings, auto-launch behavior, and IDE path detection

A **decentralized synchronization system** based on **TCP technology** that enables seamless real-time synchronization between multiple VSCode, JetBrains IDE instances simultaneously. Compatible with VSCode forks (Cursor, Windsurf) and JetBrains IntelliJ-based IDEs (Rider, IntelliJ IDEA, WebStorm, PyCharm, etc.). No central server required - all IDEs communicate directly with each other in a peer-to-peer fashion.

## Key Features

-   **🔄 Decentralized Architecture**: No central server - direct peer-to-peer communication via TCP
-   **📂 File Operations Sync**: Real-time file opening and closing synchronization across all connected IDEs
-   **🎯 Cursor & Selection Sync**: Live cursor position and code selection synchronization with precise line/column accuracy
-   **🔄 Focus Compensation**: Full workspace synchronization when window loses focus, ensuring other IDEs receive the latest state
-   **🔗 Multi-Instance Support**: Connect unlimited VSCode and JetBrains IDE instances simultaneously
-   **⚡ Zero Configuration**: Automatic discovery and connection of IDE instances on the same network, with seamless departure from the network
-   **🔧 Flexible Port Configuration**: Automatic port selection or custom port configuration for advanced users

## Installation

### VSCode Extension

1. Visit [GitHub Releases](https://github.com/ileeoyo/IDESync-VSCode-JetBrains/releases)
2. Download the latest `.vsix` file for VSCode
3. Open VSCode and press `Ctrl+Shift+P`
4. Type "Extensions: Install from VSIX..." and select it
5. Choose the downloaded `.vsix` file
6. Restart VSCode

### JetBrains IDE Plugin

1. Visit [GitHub Releases](https://github.com/ileeoyo/IDESync-VSCode-JetBrains/releases)
2. Download the latest `.zip` file for JetBrains IDE
3. Open JetBrains IDE and go to Settings > Plugins
4. Click the gear icon and select "Install Plugin from Disk..."
5. Choose the downloaded `.zip` file
6. Restart JetBrains IDE

## Configuration

Zero-configuration setup with automatic network discovery. Optional customization:

-   **Use Custom Port**: Enable to use a specific TCP port (default: disabled - automatic selection)
-   **Custom Port**: Set specific TCP port when custom port is enabled (range: 1024-65535, default: 3000)
-   **Auto-sync Activation**: Synchronization starts automatically when IDE launches, configurable via checkbox
-   **Partner IDE Path**: Auto-detect or manually specify the path to partner IDE executable

### Port Configuration Options:

1. **Automatic (Recommended)**: Leave "Use custom port" disabled - the system will automatically select an available port to avoid conflicts
2. **Custom Port**: Enable "Use custom port" and specify a port for advanced use cases or when multiple sync groups are needed

Access settings:
-   **VSCode**: Settings > Extensions > IDE Sync - Connect to JetBrains IDE
-   **JetBrains IDE**: Settings > Tools > IDE Sync - Connect to VSCode

## Usage

1. **Install**: Add plugin to your VSCode and/or JetBrains IDEs
2. **Start**: Launch IDEs
3. **Control Sync**: Use the sync toggle button to enable/disable synchronization:
   - **"Turn IDE Sync On"** - Click when sync is disabled to enable synchronization
   - **"IDE Sync On"** - Displayed when sync is enabled and active
4. **Verify**: Check status bar indicator for connection status  
5. **Code**: File operations, cursor movements, and text selections sync automatically
6. **Focus**: Window blur events trigger full workspace sync to other IDEs

## Troubleshooting

### Error Log Locations

When experiencing synchronization issues, check the error logs in the following locations:

#### VSCode Extension Logs

-   **Output Panel**: View > Output, then select "IDE 同步" from the dropdown

#### JetBrains IDE Plugin Logs

-   **IDE Built-in Log Viewer**:
    -   Go to Help > Show Log in Explorer/Finder/Files
    -   Open `idea.log` file

## Building

### Components

#### VSCode Extension

-   Located in `/vscode-extension`
-   Supported versions: VSCode 1.84.0 and newer
-   Compatible with VSCode forks: Cursor, Windsurf, etc.

#### JetBrains IDE Plugin

-   Located in `/jetbrains-plugin`
-   Supported versions: 2023.3 and newer
-   Compatible IDEs: IntelliJ IDEA, WebStorm, Rider, PyCharm, etc.

### Prerequisites

-   Node.js and npm for VSCode extension
-   JDK 17+ and Gradle for JetBrains IDE plugin

### Build Steps

1. Clone the repository

```bash
git clone https://github.com/ileeoyo/IDESync-VSCode-JetBrains.git
cd IDESync-VSCode-JetBrains
```

2. Build VSCode extension

```bash
cd vscode-extension
npm install
npm run build
npm run package
cd ..
```

3. Build JetBrains plugin

```bash
cd jetbrains-plugin
./gradlew buildPlugin
cd ..
```

## Feedback & Issues

Please report issues or suggestions on [GitHub](https://github.com/ileeoyo/IDESync-VSCode-JetBrains/issues).
