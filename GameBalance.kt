package com.example.hackerclicker

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong

object GameBalance {
    fun currentCodeLength(debugMode: Boolean): Int {
        if (debugMode) return 1
        return 4
    }

    fun baseMemorizeMillis(totalHacks: Int): Long {
        return when (totalHacks) {
            in 0..4 -> 5000L
            in 5..14 -> 4000L
            in 15..29 -> 3500L
            in 30..49 -> 3000L
            else -> 2500L
        }
    }

    fun baseReward(totalHacks: Int, currentCodeLength: Int): Long {
        val tierBonus = when (totalHacks) {
            in 0..4 -> 0L
            in 5..14 -> 35L
            in 15..29 -> 70L
            in 30..49 -> 110L
            else -> 160L
        }
        return 50L + (currentCodeLength * 18L) + tierBonus + (totalHacks * 6L)
    }

    fun cashMultiplier(level: Int): Double = 1.0 + (level * 0.25)

    fun levelForDisplay(totalHacks: Int): Int = totalHacks + 1

    fun upgradeLevel(state: GameState, upgrade: UpgradeType): Int {
        return when (upgrade) {
            UpgradeType.EXTRA_TIME -> state.extraTimeLevel
            UpgradeType.HINTS -> state.hintsOwned
            UpgradeType.AUTOHACKER -> state.autohackerLevel
            UpgradeType.CASH_MULTIPLIER -> state.cashMultiplierLevel
            UpgradeType.DOUBLE_PAY -> state.doublePayLevel
        }
    }

    fun upgradeCost(upgrade: UpgradeType, level: Int): Long {
        return (upgrade.baseCost * 1.5.pow(level.toDouble())).roundToLong()
    }

    fun upgradeMaxLevel(upgrade: UpgradeType): Int? {
        return when (upgrade) {
            UpgradeType.DOUBLE_PAY -> 10
            else -> null
        }
    }

    fun extraTimeSeconds(level: Int): Float = level / 10f

    fun doublePayRecharge(level: Int): Int = max(1, 6 - level)

    fun updateDoublePayAfterRound(current: GameState, usedDoublePay: Boolean): GameState {
        if (current.doublePayLevel == 0) return current
        return when {
            usedDoublePay -> current.copy(
                doublePayReady = false,
                doublePayCooldownRemaining = doublePayRecharge(current.doublePayLevel),
            )

            !current.doublePayReady -> {
                val remaining = (current.doublePayCooldownRemaining - 1).coerceAtLeast(0)
                current.copy(doublePayReady = remaining == 0, doublePayCooldownRemaining = remaining)
            }

            else -> current
        }
    }
}
