package com.example.hackerclicker

object TerminalUiRenderer {
    fun renderState(
        phase: GamePhase,
        currentCode: String,
        typedChars: List<Char?>,
        hintIndices: Set<Int>,
        correctness: List<Boolean?>,
        isRecognizing: Boolean,
        idleLines: List<String>,
        levelForDisplay: Int,
        blackoutEndsAt: Long,
        now: Long,
        lastRewardText: String,
        cursorVisible: Boolean,
        nextOpenIndex: Int,
    ): TerminalRenderState {
        return when (phase) {
            GamePhase.IDLE -> TerminalRenderState(
                mode = ScreenMode.IDLE,
                idleLines = idleLines,
                header = "AUTOHACKER\nONLINE",
                footer = "",
                cursorVisible = cursorVisible,
            )

            GamePhase.MEMORIZE -> TerminalRenderState(
                mode = ScreenMode.MEMORIZE,
                code = currentCode,
                header = "MEMORIZE THE CODE",
                footer = "Level $levelForDisplay trace incoming",
                cursorVisible = cursorVisible,
            )

            GamePhase.BLACKOUT -> TerminalRenderState(
                mode = ScreenMode.BLACKOUT,
                header = "SIGNAL CUT",
                footer = "Reconstruct from memory",
                cursorVisible = cursorVisible,
                glitchAmount = ((blackoutEndsAt - now).coerceAtLeast(0L) / 450f).coerceIn(0f, 1f),
            )

            GamePhase.DRAW -> TerminalRenderState(
                mode = ScreenMode.DRAW,
                code = currentCode,
                entered = typedChars,
                hintIndices = hintIndices,
                header = "",
                footer = if (isRecognizing) "Reading ink..." else "",
                cursorIndex = nextOpenIndex.coerceAtLeast(0),
                cursorVisible = cursorVisible,
            )

            GamePhase.RESULT -> TerminalRenderState(
                mode = ScreenMode.RESULT,
                code = currentCode,
                entered = typedChars,
                correctness = correctness,
                header = "TRACE REVIEW",
                footer = lastRewardText,
                cursorVisible = cursorVisible,
            )
        }
    }

    fun buildOutput(renderState: TerminalRenderState, terminalLines: List<String>, terminalLineCount: Int, terminalAnimatedLine: String?): String {
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
                lines += "TARGET CODE HIDDEN"
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
                lines += terminalAnimatedLine
            } else {
                lines += terminalLines
            }
        }

        return lines.joinToString("\n")
    }

    private fun buildEnteredLine(renderState: TerminalRenderState, resultMode: Boolean): String {
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
}
