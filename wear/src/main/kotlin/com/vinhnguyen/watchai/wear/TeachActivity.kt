package com.vinhnguyen.watchai.wear

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * "Learn my voice": the user reads out five short sentences starting with "Hey Buddy" and the watch
 * learns how the wake word hears their voice ([VoiceTeacher]). Opened from Buddy's settings while
 * "Hey Buddy" is on. The takes never leave the watch and are dropped once learned.
 */
class TeachActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { TeachScreen(onClose = ::finish) } }
    }

    // A short task on its own: leaving it ends it, so the mic doesn't keep going unseen.
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) finish()
    }
}

internal class TeachViewModel(
    application: Application,
) : AndroidViewModel(application) {
    sealed interface Step {
        data object Loading : Step

        data class Ready(
            val taught: Boolean,
        ) : Step

        data class Listening(
            val take: Int,
        ) : Step

        data class Missed(
            val take: Int,
            val why: String,
        ) : Step

        data object Learning : Step

        data class Done(
            val lesson: VoiceTeacher.Lesson,
        ) : Step

        data class Failed(
            val why: String,
        ) : Step
    }

    private val _step = MutableStateFlow<Step>(Step.Loading)
    val step: StateFlow<Step> = _step.asStateFlow()

    private val teacher = viewModelScope.async(Dispatchers.Default) { VoiceTeacher(application) }

    // The recognizer isn't thread-safe either: one decode at a time, and none while it's released.
    private val decoding = Mutex()
    private val takes = mutableListOf<ShortArray>()
    private val spellings = mutableListOf<Deferred<List<String>>>()
    private var run: Job? = null

    init {
        viewModelScope.launch {
            _step.value =
                when {
                    application.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> Step.Failed("Allow the microphone for Buddy first")
                    runCatching { teacher.await() }.isFailure -> Step.Failed("Couldn't get ready. Try again later")
                    else -> Step.Ready(WakeSetting.keywords(application) != null)
                }
        }
    }

    fun start() {
        takes.clear()
        spellings.clear()
        CallService.teaching(true)
        record()
    }

    fun retry() = record()

    fun forget() {
        CallService.learned(getApplication(), null)
        _step.value = Step.Ready(taught = false)
    }

    private fun record() {
        run?.cancel()
        run =
            viewModelScope.launch {
                val t = teacher.await()
                while (takes.size < TAKES) {
                    _step.value = Step.Listening(takes.size)
                    val pcm =
                        when (val take = t.take()) {
                            is VoiceTeacher.Take.Heard -> take.pcm

                            else -> {
                                _step.value = Step.Missed(takes.size, why(take))
                                return@launch
                            }
                        }
                    tick()
                    takes += pcm
                    // Decoded while the next sentence is read.
                    spellings += async(Dispatchers.Default) { decoding.withLock { t.spelling(t.phrase(pcm)) } }
                    // Not the tail of this take as the start of the next.
                    delay(BETWEEN_MS)
                }
                _step.value = Step.Learning
                val word = CallService.wakeModel()
                if (word == null) {
                    _step.value = Step.Failed("Turn on \"Hey Buddy\" first")
                    return@launch
                }
                val heard = spellings.awaitAll()
                val lesson = withContext(Dispatchers.Default) { t.learn(takes.toList(), heard, word) }
                takes.clear()
                spellings.clear()
                CallService.learned(getApplication(), lesson.keywords)
                CallService.teaching(false)
                _step.value = Step.Done(lesson)
            }
    }

    private fun why(take: VoiceTeacher.Take) = when (take) {
        VoiceTeacher.Take.Quiet -> "A little louder, please"
        VoiceTeacher.Take.Loud -> "A little quieter, please"
        VoiceTeacher.Take.Noisy -> "It's loud around you. Somewhere quieter?"
        else -> "I didn't hear anything"
    }

    private fun tick() {
        runCatching { getApplication<Application>().getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)) }
    }

    override fun onCleared() {
        CallService.teaching(false)
        takes.clear()
        if (teacher.isCompleted && !teacher.isCancelled) {
            val t = runCatching { teacher.getCompleted() }.getOrNull() ?: return
            // After a decode that's still running, if any.
            CoroutineScope(Dispatchers.Default).launch { decoding.withLock { t.release() } }
        }
    }

    private companion object {
        const val BETWEEN_MS = 400L
    }
}

/** What the user reads out, one per take: the phrase alone first, then as they'd use it. */
private val SENTENCES =
    listOf(
        "Hey Buddy",
        "Hey Buddy, what time is it?",
        "Hey Buddy, set a timer for five minutes",
        "Hey Buddy, how's the weather today?",
        "Hey Buddy, tell me a joke",
    )
private val TAKES = SENTENCES.size

@Composable
private fun TeachScreen(
    onClose: () -> Unit,
    vm: TeachViewModel = viewModel(),
) {
    val step by vm.step.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (val s = step) {
            TeachViewModel.Step.Loading -> Line("Getting ready…")

            is TeachViewModel.Step.Ready -> {
                Title("Learn my voice")
                Line("Read $TAKES short sentences out loud, so Buddy learns your voice.")
                Hint("It stays on your watch")
                Spacer(Modifier.height(10.dp))
                Button(onClick = vm::start, label = { Text("Start") })
                if (s.taught) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = vm::forget, label = { Text("Forget my voice") })
                }
            }

            is TeachViewModel.Step.Listening -> {
                Takes(s.take)
                Hint("Read out loud")
                Title("\"${SENTENCES[s.take]}\"")
            }

            is TeachViewModel.Step.Missed -> {
                Takes(s.take)
                Line(s.why)
                Spacer(Modifier.height(10.dp))
                Button(onClick = vm::retry, label = { Text("Try again") })
            }

            TeachViewModel.Step.Learning -> {
                Takes(TAKES)
                Line("Learning your voice…")
            }

            is TeachViewModel.Step.Done -> {
                Title("Got it")
                Line("Buddy heard ${s.lesson.before} of ${s.lesson.takes} before, ${s.lesson.after} now.")
                Spacer(Modifier.height(10.dp))
                Button(onClick = onClose, label = { Text("Done") })
            }

            is TeachViewModel.Step.Failed -> {
                Line(s.why)
                Spacer(Modifier.height(10.dp))
                Button(onClick = onClose, label = { Text("Close") })
            }
        }
    }
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))

@Composable
private fun Line(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))

/** A dot per take, filled once it's in. */
@Composable
private fun Takes(done: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 10.dp)) {
        repeat(TAKES) { i ->
            Box(Modifier.size(8.dp).background(if (i < done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f), CircleShape))
        }
    }
}
