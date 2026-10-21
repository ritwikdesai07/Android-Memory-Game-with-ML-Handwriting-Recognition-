package com.example.hackerclicker

data class GameState(
    val cash: Long = 0L,
    val totalHacks: Int = 0,
    val successfulHacks: Int = 0,
    val extraTimeLevel: Int = 0,
    val hintsOwned: Int = 0, // FEATURE hint storage
    val hintUsedThisRound: Boolean = false, // FEATURE hint flag
    val autohackerLevel: Int = 0,
    val cashMultiplierLevel: Int = 0,
    val codeFreezerLevel: Int = 0,
    val doublePayLevel: Int = 0,
    val doublePayReady: Boolean = false,
    val doublePayCooldownRemaining: Int = 0,
)
