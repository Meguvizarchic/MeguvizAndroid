package io.github.jichuo1.meguviz.android.extensions

import io.github.jichuo1.meguviz.android.runtime.core.BoundedMailbox

/**
 * Stages the offers written in [block] and submits them as one batch; returns how many were
 * accepted or replaced. Per-offer results stay readable through [BoundedMailbox.Offers.admission]
 * and [BoundedMailbox.Offers.handle].
 *
 * ```
 * val accepted = offers.submitBatch {
 *     state(slot, token) { applyLatest(value) }
 *     command(token) { log("applied") }
 * }
 * ```
 *
 * The function is inline: [block] is compiled into the caller, so the call itself allocates
 * nothing beyond the Runnables the staged offers need anyway. If [block] throws, nothing is
 * submitted and the partly staged batch is cleared.
 *
 * Kotlin only. From Java the extension is visible as a static method taking a `Function1`, which
 * would allocate a function object per call; stage offers with `offers.state(...)` /
 * `offers.command(...)` and call `offers.submit()` directly instead. That path has identical
 * admission and release semantics and needs no wrapper object.
 */
inline fun BoundedMailbox.Offers.submitBatch(block: BoundedMailbox.Offers.() -> Unit): Int {
    try {
        block()
    } catch (failure: Throwable) {
        clear()
        throw failure
    }
    return submit()
}
