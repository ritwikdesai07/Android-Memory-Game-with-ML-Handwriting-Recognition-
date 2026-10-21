package com.example.hackerclicker

import android.content.Context

object GameStorage {
    private const val PREFS_NAME = "ethical_hacker_state_v1"
    private const val KEY_CASH = "cash"
    private const val KEY_TOTAL_HACKS = "total_hacks"
    private const val KEY_SUCCESSFUL_HACKS = "successful_hacks"
    private const val KEY_EXTRA_TIME = "extra_time"
    private const val KEY_HINTS = "hints"
    private const val KEY_HINT_USED = "hint_used"
    private const val KEY_AUTOHACKER = "autohacker"
    private const val KEY_CASH_MULTIPLIER = "cash_multiplier"
    private const val KEY_CODE_FREEZER = "code_freezer"
    private const val KEY_DOUBLE_PAY = "double_pay"
    private const val KEY_DOUBLE_PAY_READY = "double_pay_ready"
    private const val KEY_DOUBLE_PAY_COOLDOWN = "double_pay_cooldown"

    fun load(context: Context): GameState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return GameState(
            cash = prefs.getLong(KEY_CASH, 0L),
            totalHacks = prefs.getInt(KEY_TOTAL_HACKS, 0),
            successfulHacks = prefs.getInt(KEY_SUCCESSFUL_HACKS, 0),
            extraTimeLevel = prefs.getInt(KEY_EXTRA_TIME, 0),
            hintsOwned = prefs.getInt(KEY_HINTS, 0), // FEATURE hint storage
            hintUsedThisRound = prefs.getBoolean(KEY_HINT_USED, false), // FEATURE hint flag
            autohackerLevel = prefs.getInt(KEY_AUTOHACKER, 0),
            cashMultiplierLevel = prefs.getInt(KEY_CASH_MULTIPLIER, 0),
            codeFreezerLevel = prefs.getInt(KEY_CODE_FREEZER, 0),
            doublePayLevel = prefs.getInt(KEY_DOUBLE_PAY, 0),
            doublePayReady = prefs.getBoolean(KEY_DOUBLE_PAY_READY, false),
            doublePayCooldownRemaining = prefs.getInt(KEY_DOUBLE_PAY_COOLDOWN, 0),
        )
    }

    fun save(context: Context, state: GameState) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_CASH, state.cash)
            .putInt(KEY_TOTAL_HACKS, state.totalHacks)
            .putInt(KEY_SUCCESSFUL_HACKS, state.successfulHacks)
            .putInt(KEY_EXTRA_TIME, state.extraTimeLevel)
            .putInt(KEY_HINTS, state.hintsOwned) // FEATURE hint storage
            .putBoolean(KEY_HINT_USED, state.hintUsedThisRound) // FEATURE hint flag
            .putInt(KEY_AUTOHACKER, state.autohackerLevel)
            .putInt(KEY_CASH_MULTIPLIER, state.cashMultiplierLevel)
            .putInt(KEY_CODE_FREEZER, state.codeFreezerLevel)
            .putInt(KEY_DOUBLE_PAY, state.doublePayLevel)
            .putBoolean(KEY_DOUBLE_PAY_READY, state.doublePayReady)
            .putInt(KEY_DOUBLE_PAY_COOLDOWN, state.doublePayCooldownRemaining)
            .apply()
    }
}
