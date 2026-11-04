package com.gp5.launcher.obd

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException

/**
 * ELM327 command channel (works over any byte stream: Wi-Fi TCP now, Bluetooth SPP later).
 * Commands end with CR; the adapter answers with text and a '>' prompt. Clone-safe: echo, spaces,
 * linefeeds and headers are turned off, "SEARCHING..." and blank lines are dropped.
 */
internal class Elm327(private val input: InputStream, private val output: OutputStream) {

    class NoCarException(msg: String) : IOException(msg)

    /** Sends [command] and returns the answer lines (prompt, echo and noise removed). */
    fun send(command: String, timeoutMs: Long = 2_500): List<String> {
        while (input.available() > 0) input.read()                // drop anything stale
        output.write("$command\r".toByteArray(Charsets.US_ASCII))
        output.flush()
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (System.currentTimeMillis() > deadline) throw SocketTimeoutException("no prompt after $command")
            val c = try { input.read() } catch (_: SocketTimeoutException) { continue }
            if (c < 0) throw IOException("adapter closed the connection")
            if (c == '>'.code) break
            sb.append(c.toChar())
        }
        return sb.split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != command && !it.startsWith("SEARCHING") && !it.startsWith("BUS INIT") }
    }

    /** Resets and configures the adapter. Returns its version line, e.g. "ELM327 v1.5". */
    fun init(): String {
        val version = runCatching { send("ATZ", 3_000) }.getOrDefault(emptyList()).lastOrNull { it.contains("ELM", true) }
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATST64", "ATSP0")) send(c)
        return version ?: runCatching { send("ATI").lastOrNull() }.getOrNull() ?: "ELM327"
    }

    /** First request after init: the adapter searches for the car's protocol (can take several seconds). */
    fun connectCar(): String {
        val r = send("0100", 12_000)
        if (r.none { it.replace(" ", "").startsWith("4100") }) throw NoCarException(r.joinToString(" ").ifBlank { "no answer" })
        return runCatching { send("ATDP").firstOrNull() }.getOrNull()?.removePrefix("AUTO, ")?.trim() ?: ""
    }

    /** Mode 01 request. Returns the data bytes after "41 pid", or null (NO DATA / unsupported). */
    fun pid(pid: Int): IntArray? {
        val hex = "%02X".format(pid)
        val lines = send("01$hex")
        // hybrids answer from more than one ECU: take the first line that answers this PID
        val line = lines.map { it.replace(" ", "") }.firstOrNull { it.startsWith("41$hex") } ?: return null
        val data = line.substring(4)
        if (data.length < 2 || data.length % 2 != 0) return null
        return IntArray(data.length / 2) { data.substring(it * 2, it * 2 + 2).toInt(16) }
    }

    /** Trouble codes from mode 03 (stored) or 07 (pending), e.g. ["P0456"]. */
    fun troubleCodes(mode: String = "03"): List<String> {
        val reply = "%02X".format(mode.toInt(16) + 0x40)
        val lines = send(mode, 5_000)
        if (lines.any { it.contains("NO DATA") }) return emptyList()
        val out = mutableListOf<String>()
        for (raw in lines) {
            // multi-frame CAN answers come as "0:43 02 ...", "1:..." plus a byte-count line ("00A")
            val line = raw.replace(" ", "").substringAfter(':')
            if (line.length <= 3) continue
            val at = line.indexOf(reply)
            if (at < 0 && !raw.contains(':')) continue
            var data = if (at >= 0) line.substring(at + 2) else line
            if (data.length % 4 == 2) data = data.substring(2)             // CAN: leading code count
            for (i in 0 until data.length / 4) {
                val v = data.substring(i * 4, i * 4 + 4).toIntOrNull(16) ?: continue
                if (v == 0) continue
                val letter = "PCBU"[v shr 14]
                out += "%c%d%03X".format(letter, (v shr 12) and 0x3, v and 0xFFF)
            }
        }
        return out.distinct()
    }

    /** Mode 04: clears stored codes and the check-engine lamp. */
    fun clearTroubleCodes(): Boolean = send("04", 5_000).any { it.replace(" ", "").startsWith("44") }

    /** Supported mode 01 PIDs, from the 0100 / 0120 / 0140 bitmaps. */
    fun supportedPids(): Set<Int> {
        val out = mutableSetOf<Int>()
        var base = 0x00
        while (base <= 0x40) {
            val bytes = pid(base) ?: break
            if (bytes.size < 4) break
            for (i in 0 until 32) {
                if (bytes[i / 8] and (0x80 shr (i % 8)) != 0) out += base + i + 1
            }
            if (base + 0x20 !in out) break                         // next bitmap not supported
            base += 0x20
        }
        return out
    }
}
