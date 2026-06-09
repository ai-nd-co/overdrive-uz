package com.overdrive.app.automation

import com.overdrive.app.monitor.AccMonitor
import java.io.File

/** Live vehicle state exposed to JS as `state`. Minimal for P1; extend (soc/speed) later. */
class DaemonStateProvider : StateProvider {
    override fun snapshot(): Map<String, Any?> = mapOf(
        "accOn" to runCatching { AccMonitor.isAccOn() }.getOrDefault(false),
        "sentry" to runCatching { AccMonitor.isInSentryMode() }.getOrDefault(false),
    )
}

/** Append-only audit log to a file under the automation dir. Fail-safe (never throws). */
class FileAuditLog(private val file: File) : AuditLog {
    override fun record(entry: AuditEntry) {
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText("${entry.ts}\t${entry.kind}\t${entry.detail}\n")
        }
    }
}

/** Routes notify(...) to a caller-supplied sink (daemon log / telegram). */
class LogNotifier(private val sink: (String) -> Unit) : Notifier {
    override fun notify(message: String) = sink("[automation] $message")
}
