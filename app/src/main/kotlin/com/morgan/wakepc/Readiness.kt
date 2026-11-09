package com.morgan.wakepc

/**
 * What a status reply means for actually using the machine. A PC answers
 * ping early in its boot, well before it can be used, so a relay can name a
 * port that only opens later (`ready = 445`) and report whether it answers.
 *
 * READY: the ready port answers. UP: it pings, but the port does not answer
 * yet, so it is still starting. AWAKE: it pings and the relay has no port to
 * check, which is all a relay before 0.15.0 can say. ASLEEP: no ping.
 */
enum class Readiness {
    ASLEEP,
    UP,
    READY,
    AWAKE,
    ;

    /** Finished waking: nothing more is coming, so stop waiting. */
    val usable: Boolean get() = this == READY || this == AWAKE

    /** Back to the relay's `ready` field: null when it had no port to check. */
    val readyFlag: Boolean?
        get() =
            when (this) {
                READY -> true
                UP -> false
                AWAKE -> null
                ASLEEP -> false
            }
}

fun readinessOf(
    awake: Boolean?,
    ready: Boolean?,
): Readiness? =
    when (awake) {
        null -> {
            null
        }

        false -> {
            Readiness.ASLEEP
        }

        true -> {
            when (ready) {
                null -> Readiness.AWAKE
                true -> Readiness.READY
                false -> Readiness.UP
            }
        }
    }

val PingStats.readiness: Readiness
    get() = readinessOf(awake, ready) ?: Readiness.ASLEEP

/** One quick look at a command's machine, as a [Readiness]. */
suspend fun WakeRepository.status(
    connection: Connection,
    command: String,
): Result<Readiness> = stats(connection, command, count = 1).map { it.readiness }
