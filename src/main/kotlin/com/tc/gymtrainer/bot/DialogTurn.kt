package com.tc.gymtrainer.bot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import java.time.LocalDate
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal data class InputStep(val value: String, val message: Long? = null, val day: String = LocalDate.now().toString())
internal data class RecordedCall(val name: String, val result: String)
internal data class DialogDraft(
    val entry: String, val argument: String?, val owner: Long?, val started: String = LocalDate.now().toString(),
    val inputs: MutableList<InputStep> = mutableListOf(), val calls: MutableList<RecordedCall> = mutableListOf(),
    var expectsText: Boolean = false, var prompt: Long? = null,
)
internal class WaitingForInput : CancellationException()
internal class ChangedDialog : CancellationException()

// Re-enter the same dialog on each request. Already completed reads, writes and prompts
// return their original results. Only the new input can advance past the last wait.
internal class DialogTurn(
    val draft: DialogDraft, private val sender: Long, private val update: Long, private val checkpoint: suspend () -> Unit,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DialogTurn>
    private var callIndex = 0
    private var inputIndex = 0
    var day: LocalDate = LocalDate.parse(draft.started)
        private set

    suspend fun effect(name: String, execute: suspend (UUID) -> String): String {
        val index = callIndex++
        draft.calls.getOrNull(index)?.let {
            if (it.name != name) throw ChangedDialog()
            return it.result
        }
        val key = UUID.nameUUIDFromBytes("$sender:$update:$index".toByteArray())
        val result = execute(key)
        draft.calls.add(RecordedCall(name, result))
        checkpoint()
        return result
    }

    fun input(message: Long?): String {
        val input = draft.inputs.getOrNull(inputIndex++) ?: run {
            draft.expectsText = message == null
            draft.prompt = message
            throw WaitingForInput()
        }
        if (input.message != message) throw ChangedDialog()
        day = LocalDate.parse(input.day)
        if ((message != null && input.value == "cancel") || (message == null && input.value.equals("отмена", true))) throw FlowCancelled()
        return input.value
    }
}

internal suspend fun dialogToday(): LocalDate = currentCoroutineContext()[DialogTurn]?.day ?: LocalDate.now()
