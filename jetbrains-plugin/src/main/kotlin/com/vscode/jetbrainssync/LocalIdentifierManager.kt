package com.vscode.jetbrainssync

import com.intellij.openapi.project.Project
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * Local identifier manager
 * Responsible for generating and managing local unique identifiers for use by various components
 * Supports multi-project instances, distinguishing different IDEA project windows through project paths
 * Supports VSCode multi-version, distinguishing different processes of the same project through PID
 */
class LocalIdentifierManager(private val project: Project) {

    /**
     * Local unique identifier
     * Generated on first access, format: hostname-projectHash-pid
     */
    val identifier: String by lazy { generateLocalIdentifier() }

    /**
     * Project-specific message sequence number generator
     * Solves the problem of multi-project instances sharing global sequence numbers
     */
    private val messageSequence = AtomicLong(0)

    /**
     * Generate local unique identifier
     * Format: hostname-projectHash-pid
     * - projectHash: Solves the problem of IDEA multi-project windows having the same PID
     * - pid: Solves the problem of VSCode multi-version having different PIDs for the same project
     */
    private fun generateLocalIdentifier(): String {
        return try {
            val hostname = InetAddress.getLocalHost().hostName
            val projectHash = generateProjectHash()
            val pid = ProcessHandle.current().pid()
            "$hostname-$projectHash-$pid"
        } catch (e: Exception) {
            "unknown-${System.currentTimeMillis()}-${(Math.random() * 10000).toInt()}"
        }
    }

    /**
     * Generate project hash value
     * Generate short hash based on project path, used to distinguish different project instances
     */
    private fun generateProjectHash(): String {
        return try {
            val path = project.basePath ?: "unknown-project"
            val md = MessageDigest.getInstance("MD5")
            val hashBytes = md.digest(path.toByteArray())
            // Take first 3 bytes (6 hexadecimal characters) as project hash
            hashBytes.take(3).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            // If hash generation fails, use last 6 digits of timestamp as fallback
            System.currentTimeMillis().toString().takeLast(6)
        }
    }

    /**
     * Generate project-specific message ID
     * Format: {localIdentifier}-{sequence}-{timestamp}
     * Each project instance has independent sequence number, avoiding multi-instance conflicts
     */
    fun generateMessageId(): String {
        val sequence = messageSequence.incrementAndGet()
        val timestamp = System.currentTimeMillis()
        return "$identifier-$sequence-$timestamp"
    }

}