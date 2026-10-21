package com.example.hackerclicker

import kotlin.random.Random

class TerminalFeed(
    private val maxLines: Int = 12,
    private val random: Random = Random(System.currentTimeMillis()),
) {
    private val lines = MutableList(maxLines) { "" }

    private val passiveCodeLines = listOf( // FEATURE real-looking code
        "val packet = DataPacket(src = node.ip, dst = target.ip)",
        "while (!access.granted) { auth.attempt(credentials) }",
        "override fun inject(payload: ByteArray): Boolean {",
        "    return buffer.write(payload, offset = 0x00FF)",
        "}",
        "fun decrypt(key: String, data: ByteArray): String {",
        "    return AES.decode(data, key.toByteArray())",
        "}",
        "nodeMap.filter { it.vulnerable }.forEach { crack(it) }",
        "val hash = SHA256.digest(token + salt)",
        "socket.connect(InetAddress.getByName(targetIP), 4444)",
        "Log.d(\"HACK\", \"Layer bypassed\")",
        "if (traceLevel > threshold) { reroute(signal) }",
        "exploit.inject(shellcode = payload_x86)",
        "val root = sudo.escalate(user = \"admin\")",
        "Thread.sleep(jitter.randomDelay())",
        "proxy.chain(nodes = listOf(vpn1, vpn2, tor))",
        "assert(memoryDump.contains(secretKey))",
        "scheduler.queue(task = BackdoorInstall(), delay = 500L)"
    ) // END FEATURE

    fun tick() {
        lines.removeAt(0)
        lines.add(passiveCodeLines.random(random)) // FEATURE real-looking code
    }

    fun snapshot(cursorVisible: Boolean): List<String> {
        return lines.mapIndexed { index, line ->
            if (index == lines.lastIndex && cursorVisible) "$line|" else line
        }
    }
}
