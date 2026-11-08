package io.github.jichuo1.meguviz.android.extensions

import io.github.jichuo1.meguviz.android.runtime.core.BoundedMailbox
import io.github.jichuo1.meguviz.android.runtime.core.FrameDriver
import io.github.jichuo1.meguviz.android.runtime.core.FrameScheduler
import io.github.jichuo1.meguviz.android.runtime.core.LifecycleScope
import io.github.jichuo1.meguviz.android.runtime.core.OwnerDispatcher
import io.github.jichuo1.meguviz.android.runtime.core.WorkToken
import java.io.DataInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JVM test: the DSL only stages and submits, so no Android framework class is involved. */
class MailboxExtensionsTest {
    private class Owner : OwnerDispatcher, FrameDriver {
        private val thread = Thread.currentThread()
        val posted = ArrayDeque<Runnable>()
        var frame: FrameDriver.Callback? = null
        override fun isOwnerThread() = Thread.currentThread() === thread
        override fun post(callback: Runnable): Boolean { posted.addLast(callback); return true }
        override fun remove(callback: Runnable) { posted.remove(callback) }
        override fun request(callback: FrameDriver.Callback): Boolean { frame = callback; return true }
        override fun remove(callback: FrameDriver.Callback) { if (frame === callback) frame = null }
        fun drain() {
            repeat(100) {
                while (posted.isNotEmpty()) posted.removeFirst().run()
                val next = frame ?: return
                frame = null
                next.onFrame(16_666_667L)
            }
        }
    }

    private val owner = Owner()
    private val scope = LifecycleScope(owner) { throw AssertionError(it) }.apply { setAttached(true); setStarted(true) }
    private val scheduler = FrameScheduler(owner, owner, { throw AssertionError(it) }, 64)
    private val token: WorkToken = scope.lease(LifecycleScope.ALL).token()
    private val box = BoundedMailbox(scope, scheduler, { throw AssertionError(it) }, 4, 8, 16)

    @Test fun submitBatchStagesInOrderAndClearsWhenTheBlockThrows() {
        val slot = box.newSlot()
        val ran = mutableListOf<String>()
        val offers = box.offers()
        val accepted = offers.submitBatch {
            state(slot, token) { ran += "stale" }
            command(token) { ran += "c1" }
            state(slot, token) { ran += "latest" }
            command(token) { ran += "c2" }
        }
        assertEquals(4, accepted)
        assertEquals(BoundedMailbox.Admission.REPLACED, offers.admission(2))
        assertEquals(BoundedMailbox.Outcome.SUPERSEDED, offers.handle(0).outcome())
        owner.drain()
        assertEquals(listOf("c1", "latest", "c2"), ran)

        assertThrows(IllegalStateException::class.java) {
            offers.submitBatch {
                command(token) { ran += "never" }
                error("staging failed")
            }
        }
        assertEquals(0, offers.size())
        assertEquals(0, box.pendingCommandCount())
        owner.drain()
        assertFalse("never" in ran)
    }

    /**
     * `block` is crossinline, so a non-local `return` (which would skip the submit and strand the
     * staged offers for the next batch) does not compile. The supported way out is a labeled
     * return, which leaves the block and submits what was staged so far.
     */
    @Test fun submitBatchLabeledReturnSubmitsWhatWasStagedAndLeavesNothingBehind() {
        val ran = mutableListOf<String>()
        val offers = box.offers()
        val early = true
        val accepted = offers.submitBatch {
            command(token) { ran += "first" }
            if (early) return@submitBatch
            command(token) { ran += "skipped" }
        }
        assertEquals(1, accepted)
        assertEquals(1, offers.size())

        val next = offers.submitBatch { command(token) { ran += "second" } }
        assertEquals("the previous batch must not leak into the next one", 1, next)
        assertEquals(1, offers.size())
        owner.drain()
        assertEquals(listOf("first", "second"), ran)
    }

    @Test fun submitBatchIsInlinedWithoutAFunctionObject() {
        // Bytecode proof, read from the compiled probe below: the call site references neither the
        // extension function nor a Kotlin function type, so no block object is created or invoked.
        val refs = methodReferences(Probe::class.java)
        assertTrue(refs.any { it == "io/github/jichuo1/meguviz/android/runtime/core/BoundedMailbox\$Offers.submit" })
        assertFalse(refs.toString(), refs.any { it.endsWith(".submitBatch") })
        assertFalse(refs.toString(), refs.any { it.startsWith("kotlin/jvm/functions/") })
        assertEquals(1, Probe.offerTwo(box.offers(), token))
    }

    /** Probe compiled exactly like a consumer call site. */
    object Probe {
        private val noop = Runnable { }
        fun offerTwo(offers: BoundedMailbox.Offers, token: WorkToken): Int =
            offers.submitBatch { command(token, noop) }
    }

    /** Method references (class.name) in a class file's constant pool. */
    private fun methodReferences(type: Class<*>): List<String> {
        val stream = type.getResourceAsStream("/" + type.name.replace('.', '/') + ".class")!!
        DataInputStream(stream).use { input ->
            check(input.readInt() == 0xCAFEBABE.toInt())
            input.readUnsignedShort(); input.readUnsignedShort()
            val count = input.readUnsignedShort()
            val utf8 = arrayOfNulls<String>(count)
            val classes = IntArray(count)
            val nameAndTypes = IntArray(count)
            val refs = mutableListOf<IntArray>()
            var index = 1
            while (index < count) {
                when (input.readUnsignedByte()) {
                    1 -> utf8[index] = input.readUTF()
                    3, 4 -> input.readInt()
                    5, 6 -> { input.readLong(); index++ }
                    7 -> classes[index] = input.readUnsignedShort()
                    8, 16, 19, 20 -> input.readUnsignedShort()
                    9, 17, 18 -> input.readInt()
                    10, 11 -> refs += intArrayOf(input.readUnsignedShort(), input.readUnsignedShort())
                    12 -> { nameAndTypes[index] = input.readUnsignedShort(); input.readUnsignedShort() }
                    15 -> { input.readUnsignedByte(); input.readUnsignedShort() }
                    else -> error("Unknown constant pool tag")
                }
                index++
            }
            return refs.map { (owner, nameAndType) -> utf8[classes[owner]] + "." + utf8[nameAndTypes[nameAndType]] }
        }
    }
}
