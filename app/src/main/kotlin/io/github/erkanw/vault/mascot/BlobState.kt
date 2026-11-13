package io.github.erkanw.vault.mascot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * What the agent is doing, as the chat, the widget and the notifications know it. [Approval] is a tool waiting for your
 * OK; [Question] is kept for a question the agent asks. How the blob shows each one is the mascot's business (`blobMoodOf` maps a
 * state to one of his moods); this is only the fact.
 */
enum class BlobState { Idle, Working, Thinking, Searching, Approval, Question, Error, Finished, RateLimit, Sleeping, Dizzy }

/**
 * What to show for [state]: at night, with [sleepEnabled], an idle Blob sleeps (see [SleepSchedule]). Checked every
 * half minute, so he nods off and wakes up on time.
 */
@Composable
fun rememberShownState(state: BlobState, sleepEnabled: Boolean): BlobState {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    return SleepSchedule.display(state, now, sleepEnabled)
}
