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

    /**
     * Drops, and returns, whatever the adapter has sent that no command is waiting for. Some clones
     * answer a failed search twice, and the spare answer mustn't pass for the next command's.
     */
    fun discard(): String = ""
}
