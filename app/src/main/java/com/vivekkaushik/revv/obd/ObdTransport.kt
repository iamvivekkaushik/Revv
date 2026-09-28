package com.vivekkaushik.revv.obd

import java.io.Closeable

/** A serial link to an ELM327. Calls block, so run them off the main thread. */
interface ObdTransport : Closeable {

    /** Sends [command], terminated by the carriage return the ELM327 expects. */
    fun send(command: String)

    /**
     * Everything the adapter replied, up to its `>` prompt, or null if the prompt didn't arrive
     * within [timeoutMillis].
     */
    fun receive(timeoutMillis: Long): String?
}
