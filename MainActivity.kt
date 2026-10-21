package com.example.hackerclicker

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import com.example.hackerclicker.databinding.ActivityMainBinding
import com.example.hackerclicker.databinding.DialogBackWarningBinding
import com.example.hackerclicker.databinding.ViewUpgradeItemBinding
import com.example.hackerclicker.viewmodel.DrawingViewModel
import com.google.android.material.card.MaterialCardView
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    // DEBUG MODE: Set to true for 1-character codes (easier testing)
    private val DEBUG_MODE = false
    private val backOutPenalty = 500L

    private lateinit var binding: ActivityMainBinding
    private val inkCaptureView get() = binding.computerScene.inkCaptureView
    private val drawingBoxContainer get() = binding.computerScene.drawingBoxContainer
    // DEBUG: observe isolated recognition results for on-screen inspection.
    private val viewModel: DrawingViewModel by viewModels()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val random = Random(System.currentTimeMillis())
    private val terminalFeed = TerminalFeed()
    private val upgradeViews = linkedMapOf<UpgradeType, ViewUpgradeItemBinding>()
    // DEBUG terminal animation - remove this block when no longer needed as a visual
    private val terminalLines = mutableListOf<String>()
    private val fakePrefixes = listOf("> ", "$ ", "# ", ">> ", ":: ")
    private val fakeCommands = listOf(
        "ACCESS GRANTED", "DECRYPTING...", "PACKET INJECTED",
        "BYPASS LAYER %d COMPLETE", "AUTH TOKEN ACCEPTED",
        "NODE UNLOCKED", "TRACE SUPPRESSED",
        "KERNEL MODULE LOADED", "PORT %d OPEN", "SIGNATURE VERIFIED",
        "BUFFER OVERFLOW CONTAINED", "ROOT ACCESS ENABLED",
        "SIGNAL REROUTED VIA %s", "MEMORY DUMP COMPLETE"
    )
    private val fakeAddresses = listOf("192.168.1.%d", "10.0.%d.1", "172.16.%d.0")
    private var terminalLineCount = 0
    private var terminalAnimatedLine: String? = null
    private var terminalAnimationHandler: Handler? = null
    private var lastTerminalText = ""
    @Volatile
    private var nextIdleTerminalTickAt = 0L
    private val saveStateRunnable = Runnable { saveStateNow() }
    // END DEBUG terminal animation

    @Volatile
    private var state = GameState()
    private var displayedCash = 0L
    @Volatile
    private var phase = GamePhase.IDLE
    private var modelReady = false
    private var isRecognizing = false
    private var currentCode = ""
    private var typedChars = mutableListOf<Char?>()
    private var pendingVerificationIndices = emptyList<Int>()
    private var hintIndices = emptySet<Int>()
    private var correctness = emptyList<Boolean?>()
    private var lastRewardText = ""
    @Volatile
    private var memorizeEndsAt = 0L
    @Volatile
    private var memorizeDurationMillis = 0L
    @Volatile
    private var blackoutEndsAt = 0L
    @Volatile
    private var resultEndsAt = 0L
    private var lockCharPending = false
    private var pendingLockIndex: Int? = null
    private var pendingExpectedChar: Char? = null
    private var memorizeFlashStarted = false // FEATURE big flash before hide

    private var recognizer: DigitalInkRecognizer? = null
    private var recognitionModel: DigitalInkRecognitionModel? = null
    private var hackPulseAnimator: ObjectAnimator? = null
    private var cashAnimator: ValueAnimator? = null
    private var hackActivationAnimator: AnimatorSet? = null
    private var resultSequenceAnimator: AnimatorSet? = null

    private var tfliteVerifier: HandwritingCodeVerifier? = null
    private val handwritingFallbackAnalyzer by lazy { HandwritingFallbackAnalyzer(::dp) }
    private val gameExecutor = Executors.newSingleThreadExecutor()
    private val recognitionExecutor = Executors.newSingleThreadExecutor()
    private val tickScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var frameTickFuture: ScheduledFuture<*>? = null
    private var passiveIncomeFuture: ScheduledFuture<*>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // DEBUG: show the top ML Kit candidates near the Clear button.
        viewModel.recognitionResult.observe(this) { result ->
            val tv = binding.tvDebugRecognition
            tv.isVisible = DEBUG_MODE
            if (DEBUG_MODE) {
                val target = if (phase == GamePhase.IDLE) "--" else currentCode // FEATURE
                if (result.isSuccess) {
                    val top = result.candidates.firstOrNull() ?: "?"
                    val all = result.candidates.take(3).joinToString(", ")
                    tv.text = "Target: $target | Debug: $top (all: $all)" // FEATURE
                } else {
                    tv.text = "Target: $target | Debug: FAILED - ${result.errorMessage}" // FEATURE
                }
            }
            if (lockCharPending) {
                handlePendingLockRecognition(result)
            }
        }
        updateDrawAreaMode()
        inkCaptureView.onTouchEventForwarded = { event ->
            viewModel.inkManager.onTouchEvent(event) // DEBUG: feed the isolated ML Kit pipeline.
        }
        inkCaptureView.onInkCleared = {
            viewModel.inkManager.clear() // DEBUG: avoid stale strokes between drawings.
        }

        state = GameStorage.load(this)
        displayedCash = state.cash

        setupUpgradeBar()
        setupButtons()
        setupAnimations()
        prepareRecognition()
        renderStaticUi()
        refreshUpgradeViews()
        updateTopBar()
        renderTerminal()
        setupBackNavigation()
    }

    override fun onStart() {
        super.onStart()
        startBackgroundLoops()
    }

    override fun onStop() {
        super.onStop()
        stopBackgroundLoops()
        mainHandler.removeCallbacks(saveStateRunnable)
        saveStateNow()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBackgroundLoops()
        tickScheduler.shutdownNow()
        gameExecutor.shutdownNow()
        recognitionExecutor.shutdownNow()
        recognizer?.close()
        tfliteVerifier?.close()
        hackPulseAnimator?.cancel()
        cashAnimator?.cancel()
        hackActivationAnimator?.cancel()
        resultSequenceAnimator?.cancel()
    }

    private fun setupButtons() {
        styleTerminalTextAction(binding.backButton)
        styleTerminalTextAction(binding.clearButton)

        binding.hackButton.setOnClickListener {
            if (phase == GamePhase.IDLE && modelReady) {
                playHackActivationSequence()
                gameExecutor.execute { startHackRound() }
            }
        }
        binding.backButton.setOnClickListener {
            showBackOutDialog()
        }
        binding.clearButton.setOnClickListener {
            viewModel.recognizeCurrentDrawing() // DEBUG: capture recognition output before clearing.
            inkCaptureView.clearInk()
        }
        binding.btnUseHint.setOnClickListener { // FEATURE hint usage
            gameExecutor.execute {
                if (state.hintUsedThisRound) {
                    mainHandler.post { Toast.makeText(this, "Only 1 hint per round!", Toast.LENGTH_SHORT).show() }
                    return@execute
                }
                if (state.hintsOwned <= 0) {
                    mainHandler.post { Toast.makeText(this, "No hints stored!", Toast.LENGTH_SHORT).show() }
                    return@execute
                }
                state = state.copy(
                    hintsOwned = state.hintsOwned - 1,
                    hintUsedThisRound = true,
                )
                mainHandler.post {
                    binding.computerScene.tvTargetCode.text = currentCode
                    binding.computerScene.tvTargetCode.visibility = View.VISIBLE
                    refreshUpgradeViews()
                    updateTargetDebugText()
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (phase == GamePhase.DRAW) {
                            binding.computerScene.tvTargetCode.visibility = View.INVISIBLE
                        }
                    }, 2000L)
                }
            }
        } // END FEATURE hint usage
        binding.lockCharButton.setOnClickListener { lockCurrentCharacter() }
        inkCaptureView.onInkStateChanged = { renderActionButtons() }

        inkCaptureView.onCharacterSegmented = onCharacterSegmented@ { seg ->
            val verifier = tfliteVerifier ?: return@onCharacterSegmented
            if (phase != GamePhase.DRAW) return@onCharacterSegmented

            recognitionExecutor.execute {
                try {
                    val pred = verifier.submitSegment(seg)
                    val targetIndex = pendingVerificationIndices.getOrNull(pred.index)
                        ?: error("Missing target slot for segment index ${pred.index}")
                    gameExecutor.execute {
                        typedChars[targetIndex] = pred.char
                        val shouldFinish = nextOpenIndex() == -1
                        mainHandler.post {
                            vibrate(16L)
                            binding.statusTextView.text = getString(R.string.status_locked_char, pred.char.toString())
                            renderActionButtons()
                            renderTerminal()
                            if (shouldFinish) {
                                gameExecutor.execute { finishRound() }
                            }
                        }
                    }
                } catch (_: Exception) {
                    mainHandler.post {
                        binding.statusTextView.text = getString(R.string.status_recognition_failed)
                        renderActionButtons()
                        renderTerminal()
                    }
                } finally {
                    if (!seg.bitmap.isRecycled) {
                        seg.bitmap.recycle()
                    }
                }
            }
        }
    }

    private fun styleTerminalTextAction(view: TextView) {
        view.background = null
        view.backgroundTintList = null
        view.foreground = ContextCompat.getDrawable(this, android.R.drawable.list_selector_background)
        view.setTextColor(ContextCompat.getColor(this, R.color.neon_green))
        view.minWidth = 0
        view.minHeight = 0
        view.minimumWidth = 0
        view.minimumHeight = 0
        view.stateListAnimator = null
        view.elevation = 0f
        view.includeFontPadding = false
        view.setPadding(dp(6f).roundToInt())
    }

    private fun handlePendingLockRecognition(result: com.example.hackerclicker.recognition.RecognitionResult) {
        val strokes = inkCaptureView.getAllStrokes()
        recognitionExecutor.execute {
            val lockIndex = pendingLockIndex
            val expectedChar = pendingExpectedChar
            lockCharPending = false
            pendingLockIndex = null
            pendingExpectedChar = null
            isRecognizing = false

            if (lockIndex == null || expectedChar == null) {
                mainHandler.post { renderActionButtons() }
                return@execute
            }

            val fallbackChar = handwritingFallbackAnalyzer.resolveFallbackCharacter(
                result = result,
                strokes = strokes,
            )

            if (result.isSuccess && result.candidates.any { it.equals(expectedChar.toString(), ignoreCase = true) }) {
                gameExecutor.execute {
                    typedChars[lockIndex] = expectedChar
                    val shouldFinish = nextOpenIndex() == -1
                    mainHandler.post {
                        inkCaptureView.clearInk()
                        vibrate(16L)
                        binding.statusTextView.text = getString(R.string.status_locked_char, expectedChar.toString())
                        appendTerminalLine()
                        renderActionButtons()
                        renderTerminal()
                        if (shouldFinish) {
                            gameExecutor.execute { finishRound() }
                        }
                    }
                }
                return@execute
            }

            if (fallbackChar != null && fallbackChar.equals(expectedChar, ignoreCase = true)) {
                gameExecutor.execute {
                    typedChars[lockIndex] = expectedChar
                    val shouldFinish = nextOpenIndex() == -1
                    mainHandler.post {
                        inkCaptureView.clearInk()
                        vibrate(16L)
                        binding.statusTextView.text = getString(R.string.status_locked_char, expectedChar.toString())
                        appendTerminalLine()
                        renderActionButtons()
                        renderTerminal()
                        if (shouldFinish) {
                            gameExecutor.execute { finishRound() }
                        }
                    }
                }
                return@execute
            }

            mainHandler.post {
                if (result.isSuccess) {
                    binding.statusTextView.text = getString(R.string.status_unreadable)
                    Toast.makeText(this, "Try again!", Toast.LENGTH_SHORT).show()
                } else {
                    binding.statusTextView.text = getString(R.string.status_recognition_failed)
                }
                renderActionButtons()
                renderTerminal()
            }
        }
    }

    private fun setupUpgradeBar() {
        UpgradeType.entries.forEach { upgrade ->
            val itemBinding = ViewUpgradeItemBinding.inflate(layoutInflater, binding.upgradeContainer, false)
            itemBinding.root.setOnClickListener { gameExecutor.execute { attemptPurchase(upgrade) } }
            binding.upgradeContainer.addView(itemBinding.root)
            upgradeViews[upgrade] = itemBinding
        }
    }

    private fun setupAnimations() {
        hackPulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            binding.hackButton,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.04f, 1f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.04f, 1f),
        ).apply {
            duration = 1500L
            repeatCount = ValueAnimator.INFINITE
            start()
        }
        binding.computerScene.terminalScreenView.scanSweepProgress = -1f
        binding.computerScene.terminalScreenView.codeGlowIntensity = 0f
        binding.computerScene.terminalScreenView.verificationGlowProgress = 0f
        binding.computerScene.terminalScreenView.stabilizationProgress = 0f
    }

    private fun prepareRecognition() {
        recognitionExecutor.execute {
            if (tfliteVerifier == null) {
                tfliteVerifier = runCatching { HandwritingCodeVerifier(this) }.getOrNull()
            }
            if (tfliteVerifier != null) {
                mainHandler.post { onRecognitionReady() }
                return@execute
            }

            val identifier = try {
                DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US")
            } catch (_: MlKitException) {
                null
            }

            if (identifier == null) {
                modelReady = true
                mainHandler.post {
                    binding.computerScene.modelStatusChip.text = "OCR FALLBACK"
                    binding.statusTextView.text = getString(R.string.status_ready_first_round)
                    renderActionButtons()
                }
                return@execute
            }

            val localRecognitionModel = DigitalInkRecognitionModel.builder(identifier).build()
            val localRecognizer = DigitalInkRecognition.getClient(
                DigitalInkRecognizerOptions.builder(localRecognitionModel).build(),
            )
            recognitionModel = localRecognitionModel
            recognizer = localRecognizer

            val modelManager = RemoteModelManager.getInstance()
            modelReady = true
            mainHandler.post {
                binding.computerScene.modelStatusChip.text = "OCR FALLBACK"
                binding.statusTextView.text = getString(R.string.status_ready_first_round)
                renderActionButtons()
            }
            modelManager.isModelDownloaded(localRecognitionModel)
                .addOnSuccessListener { alreadyDownloaded ->
                    if (alreadyDownloaded) {
                        mainHandler.post { onRecognitionReady() }
                    } else {
                        modelManager.download(localRecognitionModel, DownloadConditions.Builder().build())
                            .addOnSuccessListener { mainHandler.post { onRecognitionReady() } }
                            .addOnFailureListener {
                                mainHandler.post {
                                    binding.computerScene.modelStatusChip.text = getString(R.string.model_status_failed)
                                    if (phase == GamePhase.IDLE) {
                                        binding.statusTextView.text = "Fallback OCR active. You can still play offline."
                                    }
                                    renderActionButtons()
                                }
                            }
                    }
                }
                .addOnFailureListener {
                    mainHandler.post {
                        binding.computerScene.modelStatusChip.text = getString(R.string.model_status_failed)
                        if (phase == GamePhase.IDLE) {
                            binding.statusTextView.text = "Fallback OCR active. You can still play offline."
                        }
                        renderActionButtons()
                    }
                }
        }
    }

    private fun onRecognitionReady() {
        modelReady = true
        binding.computerScene.modelStatusChip.text = getString(R.string.model_status_ready)
        binding.statusTextView.text = if (state.totalHacks == 0) {
            if (DEBUG_MODE) "DEBUG: 1-char codes | Tap HACK to start" else getString(R.string.status_ready_first_round)
        } else {
            if (DEBUG_MODE) "DEBUG MODE ACTIVE | 1-char codes" else getString(R.string.status_idle)
        }
        renderActionButtons()
    }

    private fun startBackgroundLoops() {
        if (frameTickFuture == null || frameTickFuture?.isCancelled == true) {
            frameTickFuture = tickScheduler.scheduleAtFixedRate(
                { processFrameTick() },
                0L,
                250L,
                TimeUnit.MILLISECONDS,
            )
        }
        if (passiveIncomeFuture == null || passiveIncomeFuture?.isCancelled == true) {
            passiveIncomeFuture = tickScheduler.scheduleAtFixedRate(
                { processPassiveIncomeTick() },
                0L,
                1000L,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun stopBackgroundLoops() {
        frameTickFuture?.cancel(true)
        passiveIncomeFuture?.cancel(true)
        frameTickFuture = null
        passiveIncomeFuture = null
    }

    private fun processFrameTick() {
        val now = SystemClock.elapsedRealtime()
        when (phase) {
            GamePhase.IDLE -> {
                if (now >= nextIdleTerminalTickAt) {
                    mainHandler.post {
                        if (phase == GamePhase.IDLE && now >= nextIdleTerminalTickAt) {
                            terminalFeed.tick()
                            nextIdleTerminalTickAt = now + 700L
                            renderTerminal()
                        }
                    }
                }
            }

            GamePhase.MEMORIZE -> {
                val memorizeEnd = memorizeEndsAt
                val memorizeDuration = memorizeDurationMillis
                mainHandler.post {
                    if (phase == GamePhase.MEMORIZE &&
                        memorizeEnd == memorizeEndsAt &&
                        memorizeDuration == memorizeDurationMillis
                    ) {
                        updateMemorizePhase(now)
                    }
                }
            }

            GamePhase.BLACKOUT -> {
                val blackoutEnd = blackoutEndsAt
                if (now >= blackoutEnd) {
                    mainHandler.post {
                        if (phase == GamePhase.BLACKOUT && blackoutEndsAt == blackoutEnd) {
                            startDrawPhase()
                        }
                    }
                }
            }

            GamePhase.DRAW -> Unit

            GamePhase.RESULT -> {
                val resultEnd = resultEndsAt
                if (now >= resultEnd) {
                    mainHandler.post {
                        if (phase == GamePhase.RESULT && resultEndsAt == resultEnd) {
                            returnToIdle()
                        }
                    }
                }
            }
        }
    }

    private fun processPassiveIncomeTick() {
        val stateSnapshot = state
        if (phase != GamePhase.IDLE || stateSnapshot.autohackerLevel <= 0) return

        gameExecutor.execute {
            if (phase == GamePhase.IDLE && state.autohackerLevel > 0) {
                state = state.copy(cash = state.cash + state.autohackerLevel.toLong())
                mainHandler.post {
                    animateCashTo(state.cash)
                    scheduleSave()
                    refreshUpgradeViews()
                }
            }
        }
    }

    private fun updateMemorizePhase(now: Long) {
        val remaining = memorizeEndsAt - now

        if (remaining <= 0L) {
            startBlackout()
            return
        }

        if (!memorizeFlashStarted && remaining <= 1000L) {
            memorizeFlashStarted = true
        }

        val ratio = (remaining.toFloat() / memorizeDurationMillis.toFloat()).coerceIn(0f, 1f)
        binding.memorizeProgressBar.progress = (ratio * 1000).roundToInt()
        binding.phaseTextView.text = getString(R.string.phase_memorize_countdown, formatSeconds(remaining))
    }

    private fun updateBlackoutPhase(now: Long) {
        if (now >= blackoutEndsAt) startDrawPhase()
    }

    private fun startHackRound() {
        currentCode = generateCode(GameBalance.currentCodeLength(DEBUG_MODE))
        typedChars = MutableList(currentCode.length) { null }
        hintIndices = emptySet()
        correctness = List(currentCode.length) { null }
        lastRewardText = ""
        phase = GamePhase.MEMORIZE
        memorizeFlashStarted = false
        state = state.copy(hintUsedThisRound = false)

        memorizeDurationMillis = GameBalance.baseMemorizeMillis(state.totalHacks) + (state.extraTimeLevel * 100L)
        memorizeEndsAt = SystemClock.elapsedRealtime() + memorizeDurationMillis
        nextIdleTerminalTickAt = 0L

        mainHandler.post {
            inkCaptureView.clearInk()
            inkCaptureView.drawingEnabled = false
            inkCaptureView.isVisible = false
            binding.computerScene.tvTargetCode.text = currentCode
            binding.computerScene.tvTargetCode.visibility = View.INVISIBLE
            binding.memorizeProgressBar.isVisible = true
            binding.memorizeProgressBar.progress = 1000
            binding.phaseTextView.text = getString(R.string.phase_memorize)
            binding.statusTextView.text = getString(R.string.status_memorize)
            binding.computerScene.rewardTextView.isVisible = false
            playTerminalSweepAndCodeShimmer()
            renderActionButtons()
            renderTerminal()
        }
    }

    private fun startBlackout() {
        phase = GamePhase.BLACKOUT
        blackoutEndsAt = SystemClock.elapsedRealtime() + 450L
        binding.computerScene.tvTargetCode.visibility = View.INVISIBLE
        binding.memorizeProgressBar.isVisible = false
        binding.phaseTextView.text = getString(R.string.phase_blackout)
        binding.statusTextView.text = getString(R.string.status_blackout)
        renderActionButtons()
        renderTerminal()
    }

    private fun startDrawPhase() {
        phase = GamePhase.DRAW
        inkCaptureView.clearInk()
        inkCaptureView.drawingEnabled = true
        inkCaptureView.isVisible = true
        inkCaptureView.debugShowSegmentBoxes = HandwritingCodeVerifier.DEBUG_MODE
        binding.computerScene.tvTargetCode.visibility = View.INVISIBLE
        startTfliteVerificationSession()
        if (phase != GamePhase.DRAW) return
        binding.phaseTextView.text = getString(R.string.phase_draw)
        binding.statusTextView.text = getString(R.string.status_draw)
        renderActionButtons()
        renderTerminal()
    }

    private fun lockCurrentCharacter() {
        if (phase != GamePhase.DRAW || isRecognizing || !inkCaptureView.hasInk()) return

        val nextIndex = nextOpenIndex()
        if (nextIndex == -1) {
            finishRound()
            return
        }

        lockCharPending = true
        pendingLockIndex = nextIndex
        pendingExpectedChar = currentCode[nextIndex]
        isRecognizing = true
        binding.statusTextView.text = getString(R.string.status_reading)
        renderActionButtons()
        viewModel.recognizeCurrentDrawing() // DEBUG: run the same recognition path as Clear before clearing the canvas.
    }

    private fun applyLockedCharacter(index: Int, recognized: Char) {
        typedChars[index] = recognized
        inkCaptureView.clearInk()
        vibrate(16L)
        binding.statusTextView.text = getString(R.string.status_locked_char, recognized.toString())
        if (nextOpenIndex() == -1) finishRound()
        renderActionButtons()
        renderTerminal()
    }

    private fun finishRound() {
        tfliteVerifier?.resetSession()
        phase = GamePhase.RESULT

        val submitted = typedChars.mapIndexed { index, value ->
            value ?: '_'
        }
        val nextCorrectness = submitted.mapIndexed { index, char ->
            if (char == '_') false else char == currentCode[index]
        }
        val correctCount = nextCorrectness.count { it == true }

        val baseReward = GameBalance.baseReward(state.totalHacks, currentCode.length)
        var payout = 0L
        var usedDoublePay = false

        if (correctCount > 0) {
            payout = (baseReward * GameBalance.cashMultiplier(state.cashMultiplierLevel) * (correctCount.toDouble() / currentCode.length.toDouble())).roundToLong()
            if (state.doublePayLevel > 0 && state.doublePayReady) {
                payout *= 2L
                usedDoublePay = true
            }
        }

        var newState = state.copy(
            cash = state.cash + payout,
            totalHacks = state.totalHacks + 1,
            successfulHacks = state.successfulHacks + if (correctCount > 0) 1 else 0,
        )
        newState = GameBalance.updateDoublePayAfterRound(newState, usedDoublePay)

        val rewardText = when {
            usedDoublePay -> getString(R.string.reward_double_pay, formatCash(payout), correctCount, currentCode.length)
            payout > 0L -> getString(R.string.reward_success, formatCash(payout), correctCount, currentCode.length)
            else -> getString(R.string.reward_fail)
        }

        val statusText = when {
            correctCount == currentCode.length -> getString(R.string.status_perfect_hack)
            correctCount > 0 -> getString(R.string.status_partial_hack, correctCount, currentCode.length)
            else -> getString(R.string.status_failed_hack)
        }

        state = newState
        correctness = nextCorrectness
        lastRewardText = rewardText
        resultEndsAt = SystemClock.elapsedRealtime() + 2600L

        mainHandler.post {
            inkCaptureView.drawingEnabled = false
            inkCaptureView.isVisible = false
            binding.memorizeProgressBar.isVisible = false
            binding.phaseTextView.text = getString(R.string.phase_result)
            binding.statusTextView.text = statusText
            binding.computerScene.tvTargetCode.visibility = View.INVISIBLE
            animateCashTo(state.cash)
            playResultSequence(lastRewardText)
            scheduleSave()
            refreshUpgradeViews()
            updateTopBar()
            renderActionButtons()
            renderTerminal()
        }
    }

    private fun returnToIdle() {
        tfliteVerifier?.resetSession()
        phase = GamePhase.IDLE
        currentCode = ""
        typedChars.clear()
        pendingVerificationIndices = emptyList()
        hintIndices = emptySet()
        correctness = emptyList()
        lastRewardText = ""
        lockCharPending = false
        pendingLockIndex = null
        pendingExpectedChar = null
        state = state.copy(hintUsedThisRound = false) // FEATURE reset hint flag per round
        binding.computerScene.tvTargetCode.visibility = View.INVISIBLE // FEATURE hide target in gameplay
        binding.computerScene.rewardTextView.isVisible = false
        binding.phaseTextView.text = getString(R.string.phase_idle, formatCash(state.autohackerLevel.toLong()))
        binding.statusTextView.text = getString(R.string.status_idle)
        nextIdleTerminalTickAt = 0L
        renderActionButtons()
        renderTerminal()
    }

    private fun updateTopBar() {
        binding.cashTextView.text = getString(R.string.cash_format, formatCash(displayedCash))
        binding.statsTextView.text = getString(
            R.string.stats_format,
            GameBalance.levelForDisplay(state.totalHacks),
            state.totalHacks,
            state.autohackerLevel,
        )
    }

    private fun renderStaticUi() {
        binding.phaseTextView.text = getString(R.string.phase_idle, formatCash(state.autohackerLevel.toLong()))
        binding.statusTextView.text = if (modelReady) {
            if (state.totalHacks == 0) getString(R.string.status_ready_first_round) else getString(R.string.status_idle)
        } else {
            getString(R.string.status_booting)
        }
        binding.memorizeProgressBar.max = 1000
        binding.memorizeProgressBar.isVisible = false
        inkCaptureView.isVisible = false
        binding.computerScene.tvTargetCode.visibility = View.INVISIBLE // FEATURE hide target in gameplay
        binding.computerScene.rewardTextView.isVisible = false
        renderActionButtons()
    }

    private fun renderActionButtons() {
        updateDrawAreaMode()
        binding.hackButton.isVisible = phase == GamePhase.IDLE
        binding.hackButton.isEnabled = phase == GamePhase.IDLE && modelReady
        binding.hackButton.text = if (!modelReady) {
            getString(R.string.button_booting)
        } else {
            getString(R.string.button_hack)
        }
        binding.backButton.isVisible = phase == GamePhase.MEMORIZE || phase == GamePhase.BLACKOUT || phase == GamePhase.DRAW
        binding.clearButton.isVisible = phase == GamePhase.DRAW
        binding.btnUseHint.isVisible = phase == GamePhase.DRAW // FEATURE
        // If the TFLite verifier is available, segmentation+classification is automatic.
        binding.lockCharButton.isVisible = phase == GamePhase.DRAW && tfliteVerifier == null
        binding.lockCharButton.text = if (isRecognizing) getString(R.string.button_reading) else getString(R.string.button_lock_char)
        binding.clearButton.isEnabled = phase == GamePhase.DRAW && inkCaptureView.hasInk()
        binding.lockCharButton.isEnabled = phase == GamePhase.DRAW && !isRecognizing && tfliteVerifier == null
        binding.btnUseHint.isEnabled = phase == GamePhase.DRAW && state.hintsOwned > 0 && !state.hintUsedThisRound // FEATURE
    }

    private fun updateDrawAreaMode() {
        binding.tvDebugRecognition.isVisible = DEBUG_MODE
        drawingBoxContainer.isVisible = phase == GamePhase.DRAW // DEBUG
        binding.computerScene.tvDrawPrompt.isVisible = phase == GamePhase.DRAW
        binding.upgradePanel.isVisible = DEBUG_MODE || phase != GamePhase.DRAW
    }

    private fun startTfliteVerificationSession() {
        val verifier = tfliteVerifier ?: return
        pendingVerificationIndices = typedChars.mapIndexedNotNull { index, value ->
            if (value == null) index else null
        }

        if (pendingVerificationIndices.isEmpty()) {
            finishRound()
            return
        }

        val expectedRemainingCode = pendingVerificationIndices.joinToString(separator = "") { index ->
            currentCode[index].toString()
        }

        verifier.startSession(
            expectedCode = expectedRemainingCode,
            callback = object : HandwritingCodeVerifier.VerificationCallback {
                override fun onVerified(
                    matched: Boolean,
                    predicted: String,
                    perChar: List<HandwritingCodeVerifier.CharPrediction>,
                ) {
                    if (phase != GamePhase.DRAW) return
                    if (perChar.size != pendingVerificationIndices.size) {
                        mainHandler.post {
                            binding.statusTextView.text = getString(R.string.status_recognition_failed)
                            renderActionButtons()
                            renderTerminal()
                        }
                        return
                    }
                    gameExecutor.execute { finishRound() }
                }

                override fun onError(error: Throwable) {
                    if (phase != GamePhase.DRAW) return
                    mainHandler.post {
                        binding.statusTextView.text = getString(R.string.status_recognition_failed)
                        renderActionButtons()
                        renderTerminal()
                    }
                }
            },
        )
    }

    private fun refreshUpgradeViews() {
        val unlocked = state.totalHacks > 0
        binding.upgradeLockText.isVisible = !unlocked
        binding.upgradeScroll.alpha = if (unlocked) 1f else 0.35f

        upgradeViews.forEach { (upgrade, itemBinding) ->
            val level = GameBalance.upgradeLevel(state, upgrade)
            val cost = GameBalance.upgradeCost(upgrade, level)
            val maxLevel = GameBalance.upgradeMaxLevel(upgrade)
            val isMaxed = maxLevel != null && level >= maxLevel
            val affordable = state.cash >= cost

            itemBinding.upgradeNameText.text = "[${upgrade.title}]"
            itemBinding.upgradeMetaText.text = UpgradeUiFormatter.buildMeta(upgrade, level, cost, isMaxed, state, ::formatCash)
            itemBinding.upgradeDescriptionText.text = UpgradeUiFormatter.buildStatus(upgrade, level, state, ::formatCash)
            (itemBinding.root as MaterialCardView).strokeColor = ContextCompat.getColor(
                this,
                if (unlocked && affordable && !isMaxed) R.color.glow_green else R.color.dim_green,
            )
            itemBinding.root.alpha = when {
                !unlocked -> 0.35f
                isMaxed -> 0.55f
                affordable -> 1f
                else -> 0.8f
            }
        }
    }

    private fun renderTerminal() {
        val now = SystemClock.elapsedRealtime()
        val cursorVisible = ((now / 350L) % 2L) == 0L
        val renderState = TerminalUiRenderer.renderState(
            phase = phase,
            currentCode = currentCode,
            typedChars = typedChars.toList(),
            hintIndices = hintIndices,
            correctness = correctness,
            isRecognizing = isRecognizing,
            idleLines = terminalFeed.snapshot(cursorVisible),
            levelForDisplay = GameBalance.levelForDisplay(state.totalHacks),
            blackoutEndsAt = blackoutEndsAt,
            now = now,
            lastRewardText = lastRewardText,
            cursorVisible = cursorVisible,
            nextOpenIndex = nextOpenIndex(),
        )

        val terminalText = TerminalUiRenderer.buildOutput(renderState, terminalLines, terminalLineCount, terminalAnimatedLine)
        if (terminalText != lastTerminalText) {
            binding.computerScene.terminalScreenView.contentDescription = terminalText
            lastTerminalText = terminalText
        }
        binding.computerScene.terminalScreenView.renderState = renderState
        updateTargetDebugText() // FEATURE
    }

    private fun buildTerminalOutput(renderState: TerminalRenderState): String { // DEBUG
        val lines = mutableListOf<String>()
        when (renderState.mode) {
            ScreenMode.IDLE -> {
                lines += renderState.header
                lines += ""
                lines += renderState.idleLines
                lines += ""
                lines += renderState.footer
            }

            ScreenMode.MEMORIZE -> {
                lines += renderState.header
                lines += ""
                lines += "TARGET CODE HIDDEN" // FEATURE
                lines += ""
                lines += renderState.footer
            }

            ScreenMode.BLACKOUT -> {
                lines += renderState.header
                lines += ""
                lines += "SIGNAL LOST"
                lines += "REBUILD PATTERN FROM MEMORY"
                lines += ""
                lines += renderState.footer
            }

            ScreenMode.DRAW -> {
                lines += renderState.header
                lines += ""
                lines += "TRACE : ${buildEnteredLine(renderState, false)}"
                lines += ""
                lines += renderState.footer
            }

            ScreenMode.RESULT -> {
                lines += renderState.header
                lines += ""
                lines += "TRACE : ${buildEnteredLine(renderState, true)}"
                lines += ""
                lines += renderState.footer
            }
        }

        if (terminalLines.isNotEmpty()) {
            lines += ""
            lines += "TRACE LOG ${terminalLineCount.toString().padStart(2, '0')}"
            if (terminalAnimatedLine != null && terminalLines.isNotEmpty()) {
                lines += terminalLines.dropLast(1)
                lines += terminalAnimatedLine!!
            } else {
                lines += terminalLines
            }
        }

        return lines.joinToString("\n")
    }

    private fun buildEnteredLine(renderState: TerminalRenderState, resultMode: Boolean): String { // DEBUG
        if (renderState.code.isEmpty()) return ""
        return renderState.code.indices.joinToString(" ") { index ->
            val enteredChar = renderState.entered.getOrNull(index)
            when {
                enteredChar != null -> {
                    if (resultMode && renderState.correctness.getOrNull(index) == false) {
                        "!${enteredChar}!"
                    } else {
                        enteredChar.toString()
                    }
                }

                !resultMode && index in renderState.hintIndices -> "(${renderState.code[index]})"
                else -> "_"
            }
        }
    }

    // DEBUG terminal animation - remove this block when no longer needed as a visual
    private fun appendTerminalLine() {
        val prefix = fakePrefixes.random()
        var line = fakeCommands.random()
        // Fill format placeholders with random numbers or addresses
        if (line.contains("%d")) line = line.replace("%d", (1..9999).random().toString())
        if (line.contains("%s")) line = line.replace(
            "%s", fakeAddresses.random().replace("%d", (1..254).random().toString())
        )
        val timestamp = "[${String.format("%02d", (0..23).random())}:" +
                        "${String.format("%02d", (0..59).random())}:" +
                        "${String.format("%02d", (0..59).random())}]"
        val fullLine = "$timestamp $prefix$line"
        terminalLines.add(fullLine)
        terminalLineCount++

        // Keep max 20 lines (scroll behaviour - oldest lines removed)
        if (terminalLines.size > 20) terminalLines.removeAt(0)
        terminalAnimationHandler?.removeCallbacksAndMessages(null) // DEBUG
        terminalAnimationHandler = null
        terminalAnimatedLine = null
        renderTerminal()
    }
    // END DEBUG terminal animation

    private fun attemptPurchase(upgrade: UpgradeType) {
        if (state.totalHacks == 0) {
            binding.statusTextView.text = getString(R.string.status_upgrade_locked)
            return
        }

        if (upgrade == UpgradeType.EXTRA_TIME && state.extraTimeLevel >= 20) { // FEATURE time cap
            Toast.makeText(this, "Max extra time reached!", Toast.LENGTH_SHORT).show()
            return
        } // FEATURE time cap end

        val level = GameBalance.upgradeLevel(state, upgrade)
        val maxLevel = GameBalance.upgradeMaxLevel(upgrade)
        if (maxLevel != null && level >= maxLevel) {
            binding.statusTextView.text = getString(R.string.status_upgrade_maxed, upgrade.title)
            return
        }

        val cost = GameBalance.upgradeCost(upgrade, level)
        if (state.cash < cost) {
            binding.statusTextView.text = getString(R.string.status_need_cash, upgrade.title)
            return
        }

        var nextState = state.copy(cash = state.cash - cost)
        nextState = when (upgrade) {
            UpgradeType.EXTRA_TIME -> nextState.copy(extraTimeLevel = nextState.extraTimeLevel + 1) // FEATURE 0.1 second step
            UpgradeType.HINTS -> nextState.copy(hintsOwned = nextState.hintsOwned + 1) // FEATURE hint purchase
            UpgradeType.AUTOHACKER -> nextState.copy(autohackerLevel = nextState.autohackerLevel + 1)
            UpgradeType.CASH_MULTIPLIER -> nextState.copy(cashMultiplierLevel = nextState.cashMultiplierLevel + 1)
            UpgradeType.DOUBLE_PAY -> {
                val nextLevel = nextState.doublePayLevel + 1
                val keepCoolingDown = nextState.doublePayLevel > 0 && !nextState.doublePayReady
                nextState.copy(
                    doublePayLevel = nextLevel,
                    doublePayReady = !keepCoolingDown,
                    doublePayCooldownRemaining = if (keepCoolingDown) {
                        min(nextState.doublePayCooldownRemaining, GameBalance.doublePayRecharge(nextLevel))
                    } else {
                        0
                    },
                )
            }
        }

        state = nextState
        mainHandler.post {
            animateCashTo(state.cash)
            refreshUpgradeViews()
            scheduleSave()
            binding.statusTextView.text = if (upgrade == UpgradeType.HINTS) {
                "Hint stored! You have ${state.hintsOwned} hint(s)."
            } else {
                getString(R.string.status_upgrade_bought, upgrade.title)
            }
            binding.phaseTextView.text = getString(R.string.phase_idle, formatCash(state.autohackerLevel.toLong()))
            renderTerminal()
        }
    }

    private fun dp(value: Float): Float {
        return android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics
        )
    }
    private fun nextOpenIndex(): Int = typedChars.indexOfFirst { it == null }

    private fun updateTargetDebugText() { // FEATURE
        if (!DEBUG_MODE) return
        val current = binding.tvDebugRecognition.text?.toString().orEmpty()
        val debugPart = current.substringAfter(" | ", "")
        binding.tvDebugRecognition.text = if (debugPart.isBlank()) {
            "Target: ${if (phase == GamePhase.IDLE) "--" else currentCode}"
        } else {
            "Target: ${if (phase == GamePhase.IDLE) "--" else currentCode} | $debugPart"
        }
    }

    private fun generateCode(length: Int): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return buildString(length) {
            repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }

    private fun animateCashTo(target: Long) {
        cashAnimator?.cancel()
        displayedCash = target
        updateTopBar()
    }

    private fun showRewardText(text: String) {
        binding.computerScene.rewardTextView.isVisible = false
        val rewardView = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_hud_chip_low_poly)
            setPadding(dp(18f).roundToInt(), dp(12f).roundToInt(), dp(18f).roundToInt(), dp(12f).roundToInt())
            alpha = 0f
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        )
        binding.floatingOverlay.addView(rewardView, params)
        rewardView.animate()
            .alpha(1f)
            .translationY(-dp(140f))
            .setDuration(1200L)
            .withEndAction {
                rewardView.animate()
                    .alpha(0f)
                    .setDuration(800L)
                    .withEndAction { binding.floatingOverlay.removeView(rewardView) }
                    .start()
            }
            .start()
    }

    private fun playHackActivationSequence() {
        hackActivationAnimator?.cancel()
        hackPulseAnimator?.pause()

        val buttonPress = ObjectAnimator.ofPropertyValuesHolder(
            binding.hackButton,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0.94f, 1.08f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.94f, 1.08f, 1f),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, dp(2f), -dp(2f), 0f),
        ).apply {
            duration = 320L
            interpolator = AccelerateDecelerateInterpolator()
        }

        val flashIn = ObjectAnimator.ofFloat(binding.computerScene.monitorFlashOverlay, View.ALPHA, 0f, 0.75f, 0f).apply {
            duration = 280L
            interpolator = DecelerateInterpolator()
        }

        hackActivationAnimator = AnimatorSet().apply {
            playTogether(buttonPress, flashIn)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.hackButton.translationY = 0f
                    binding.computerScene.monitorFlashOverlay.alpha = 0f
                    hackPulseAnimator?.resume()
                }

                override fun onAnimationCancel(animation: Animator) {
                    binding.hackButton.scaleX = 1f
                    binding.hackButton.scaleY = 1f
                    binding.hackButton.translationY = 0f
                    binding.computerScene.monitorFlashOverlay.alpha = 0f
                    hackPulseAnimator?.resume()
                }
            })
            start()
        }
    }

    private fun playTerminalSweepAndCodeShimmer() {
        val terminalScreen = binding.computerScene.terminalScreenView
        val sweep = ValueAnimator.ofFloat(-0.25f, 1.15f).apply {
            duration = 720L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { terminalScreen.scanSweepProgress = it.animatedValue as Float }
        }
        val shimmer = ValueAnimator.ofFloat(0f, 1f, 0.35f, 0.9f, 0f).apply {
            duration = 880L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { terminalScreen.codeGlowIntensity = it.animatedValue as Float }
        }
        AnimatorSet().apply {
            playTogether(sweep, shimmer)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    terminalScreen.scanSweepProgress = -1f
                    terminalScreen.codeGlowIntensity = 0f
                }

                override fun onAnimationCancel(animation: Animator) {
                    terminalScreen.scanSweepProgress = -1f
                    terminalScreen.codeGlowIntensity = 0f
                }
            })
            start()
        }
    }

    private fun playResultSequence(rewardText: String) {
        resultSequenceAnimator?.cancel()

        val terminalScreen = binding.computerScene.terminalScreenView
        val verifyGlow = ValueAnimator.ofFloat(0f, 1f, 0.45f).apply {
            duration = 640L
            interpolator = DecelerateInterpolator()
            addUpdateListener { terminalScreen.verificationGlowProgress = it.animatedValue as Float }
        }
        val stabilize = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 640L
            interpolator = DecelerateInterpolator()
            addUpdateListener { terminalScreen.stabilizationProgress = it.animatedValue as Float }
        }
        val crtFlash = ObjectAnimator.ofFloat(binding.computerScene.monitorFlashOverlay, View.ALPHA, 0f, 0.4f, 0f).apply {
            duration = 460L
            interpolator = DecelerateInterpolator()
        }

        resultSequenceAnimator = AnimatorSet().apply {
            playTogether(verifyGlow, stabilize, crtFlash)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    terminalScreen.verificationGlowProgress = 0f
                    terminalScreen.stabilizationProgress = 0f
                    binding.computerScene.monitorFlashOverlay.alpha = 0f
                    showRewardBurst(rewardText)
                }

                override fun onAnimationCancel(animation: Animator) {
                    terminalScreen.verificationGlowProgress = 0f
                    terminalScreen.stabilizationProgress = 0f
                    binding.computerScene.monitorFlashOverlay.alpha = 0f
                }
            })
            start()
        }
    }

    private fun showRewardBurst(text: String) {
        binding.computerScene.rewardTextView.isVisible = false
        val rewardView = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_hud_chip_low_poly)
            setPadding(dp(18f).roundToInt(), dp(12f).roundToInt(), dp(18f).roundToInt(), dp(12f).roundToInt())
            alpha = 0f
            scaleX = 0.7f
            scaleY = 0.7f
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        )
        binding.floatingOverlay.addView(rewardView, params)

        val pop = ObjectAnimator.ofPropertyValuesHolder(
            rewardView,
            PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.7f, 1.16f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.7f, 1.16f, 1f),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, -dp(56f), -dp(144f)),
        ).apply {
            duration = 1050L
            interpolator = DecelerateInterpolator()
        }
        val tintPulse = ValueAnimator.ofObject(
            ArgbEvaluator(),
            Color.WHITE,
            Color.parseColor("#C9FFC2"),
            Color.WHITE,
        ).apply {
            duration = 720L
            addUpdateListener { rewardView.setTextColor(it.animatedValue as Int) }
        }

        AnimatorSet().apply {
            playTogether(pop, tintPulse)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    rewardView.animate()
                        .alpha(0f)
                        .setDuration(450L)
                        .withEndAction { binding.floatingOverlay.removeView(rewardView) }
                        .start()
                }

                override fun onAnimationCancel(animation: Animator) {
                    binding.floatingOverlay.removeView(rewardView)
                }
            })
            start()
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (phase == GamePhase.MEMORIZE || phase == GamePhase.BLACKOUT || phase == GamePhase.DRAW) {
                    showBackOutDialog()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    private fun showBackOutDialog() {
        if (phase != GamePhase.MEMORIZE && phase != GamePhase.BLACKOUT && phase != GamePhase.DRAW) return
        val dialogBinding = DialogBackWarningBinding.inflate(LayoutInflater.from(this))
        styleTerminalTextAction(dialogBinding.confirmBackButton)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialogBinding.continueButton.setOnClickListener { dialog.dismiss() }
        dialogBinding.confirmBackButton.setOnClickListener {
            dialog.dismiss()
            backOutOfHack()
        }
        dialog.show()
    }

    private fun backOutOfHack() {
        state = state.copy(
            cash = (state.cash - backOutPenalty).coerceAtLeast(0L),
            hintUsedThisRound = false,
        )
        animateCashTo(state.cash)
        scheduleSave()
        returnToIdle()
        binding.statusTextView.text = "Backed out. Lost ${formatCash(backOutPenalty)}."
    }

    private fun scheduleSave() {
        mainHandler.removeCallbacks(saveStateRunnable)
        mainHandler.postDelayed(saveStateRunnable, 1500L)
    }

    private fun saveStateNow() {
        GameStorage.save(this, state)
    }

    private fun formatCash(amount: Long): String = "\$$amount"

    private fun formatSeconds(remainingMillis: Long): String {
        val seconds = remainingMillis.coerceAtLeast(0L) / 100L / 10f
        return "${"%.1f".format(seconds)}s"
    }

    private fun formatSecondsValue(durationMillis: Long): String {
        return "${"%.1f".format(durationMillis / 1000f)}s"
    }

    private fun vibrate(durationMillis: Long) {
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        vibrator.vibrate(VibrationEffect.createOneShot(durationMillis, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
