package com.example.hackerclicker

enum class UpgradeType(val title: String, val description: String, val baseCost: Long) {
    EXTRA_TIME("EXTRA TIME", "+0.1 sec memorize buffer", 50L),
    HINTS("HINTS", "Store a manual code reveal", 50L),
    AUTOHACKER("AUTOHACKER", "Earn passive cash per second", 200L),
    CASH_MULTIPLIER("CASH MULT", "Boost hack payout multiplier", 300L),
    DOUBLE_PAY("DOUBLE PAY", "Double the next success reward", 500L),
}
