package com.overdrive.app.automation

import com.overdrive.app.monitor.AccMonitor
import java.io.File

/** Live vehicle state exposed to JS as `state`. soc is omitted until first observed. */
class DaemonStateProvider : StateProvider {
    override fun snapshot(): Map<String, Any?> = buildMap {
        put("accOn", runCatching { AccMonitor.isAccOn() }.getOrDefault(false))
        put("sentry", runCatching { AccMonitor.isInSentryMode() }.getOrDefault(false))
        put("charging", Automation.isCharging())
        runCatching { com.overdrive.app.power.SocCutoffMonitor.getLastSocPercent() }
            .getOrNull()?.let { put("soc", it) }
    }
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
