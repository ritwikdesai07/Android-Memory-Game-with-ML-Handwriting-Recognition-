package com.example.hackerclicker

object UpgradeUiFormatter {
    fun buildMeta(
        upgrade: UpgradeType,
        level: Int,
        cost: Long,
        isMaxed: Boolean,
        state: GameState,
        formatCash: (Long) -> String,
    ): String {
        val costLabel = if (isMaxed) "MAX" else formatCash(cost)
        return when (upgrade) {
            UpgradeType.EXTRA_TIME -> "+0.1s | ${"%.1f".format(GameBalance.extraTimeSeconds(level))}s total | $costLabel"
            UpgradeType.HINTS -> "+1 stored | ${state.hintsOwned} held | $costLabel"
            UpgradeType.AUTOHACKER -> "+${formatCash((level + 1).toLong())}/sec | Lvl $level | $costLabel"
            UpgradeType.CASH_MULTIPLIER -> "x${"%.2f".format(GameBalance.cashMultiplier(state.cashMultiplierLevel) + 0.25)} next | Lvl $level | $costLabel"
            UpgradeType.DOUBLE_PAY -> "Charge | Lvl $level | $costLabel"
        }
    }

    fun buildStatus(
        upgrade: UpgradeType,
        level: Int,
        state: GameState,
        formatCash: (Long) -> String,
    ): String {
        return when (upgrade) {
            UpgradeType.EXTRA_TIME -> "Memorize bonus: ${"%.1f".format(GameBalance.extraTimeSeconds(level))}s / 2.0s cap"
            UpgradeType.HINTS -> "Stored hints: ${state.hintsOwned}. Reveals the full target for 2s."
            UpgradeType.AUTOHACKER -> "Passive income ${formatCash(level.toLong())}/sec"
            UpgradeType.CASH_MULTIPLIER -> "Current multiplier x${"%.2f".format(GameBalance.cashMultiplier(state.cashMultiplierLevel))}"
            UpgradeType.DOUBLE_PAY -> if (state.doublePayReady) {
                "READY: next success pays 2x"
            } else {
                "Recharge: ${state.doublePayCooldownRemaining} hack(s)"
            }
        }
    }
}
