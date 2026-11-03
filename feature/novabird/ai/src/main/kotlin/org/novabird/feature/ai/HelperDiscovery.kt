package org.novabird.feature.ai

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A Helper in pairing mode that answered the search in the local network. */
data class FoundHelper(val name: String, val address: String, val port: Int) {
    val url: String get() = "http://$address:$port"
}

/**
 * Finds Helpers in pairing mode (docs/helper-kopplung.md): "NBHELPER?1" to 239.255.77.31:47531 twice, then 1.5 s of
 * answers. Only Helpers whose admin started "add a device by pairing" answer, and only to private addresses.
 */
class HelperDiscovery(private val context: Context) {

    suspend fun find(): List<FoundHelper> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("novabird-helper")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        try {
            DatagramSocket().use { socket -> search(socket) }
        } finally {
            runCatching { lock?.release() }
        }
    }

    private fun search(socket: DatagramSocket): List<FoundHelper> {
        val request = QUERY.encodeToByteArray()
        val group = InetAddress.getByName(GROUP)
        val packet = DatagramPacket(request, request.size, group, PORT)
        val found = LinkedHashMap<String, FoundHelper>()
        val buffer = ByteArray(MAX_ANSWER)
        val start = System.currentTimeMillis()
        val deadline = start + RESEND_MS + WAIT_MS
        var sent = 0
        socket.soTimeout = RECEIVE_SLICE_MS
        while (System.currentTimeMillis() < deadline) {
            if (sent < SENDS && System.currentTimeMillis() >= start + sent * RESEND_MS) {
                runCatching { socket.send(packet) }
                sent++
            }
            val answer = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(answer)
            } catch (_: SocketTimeoutException) {
                continue
            }
            parse(answer)?.let { found["${it.address}:${it.port}"] = it }
        }
        return found.values.toList()
    }

    private fun parse(packet: DatagramPacket): FoundHelper? = runCatching {
        val json = JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
        if (json.optString("service") != SERVICE) return null
        val port = json.optInt("port", 0).takeIf { it in 1..MAX_PORT } ?: return null
        val address = packet.address?.hostAddress ?: return null
        val name = json.optString("name").take(MAX_NAME).ifBlank { address }
        FoundHelper(name = name, address = address, port = port)
    }.getOrNull()

    private companion object {
        const val QUERY = "NBHELPER?1"
        const val SERVICE = "novabird-helper"
        const val GROUP = "239.255.77.31"
        const val PORT = 47531
        const val SENDS = 2
        const val RESEND_MS = 300L
        const val WAIT_MS = 1_500L
        const val RECEIVE_SLICE_MS = 100
        const val MAX_ANSWER = 2048
        const val MAX_PORT = 65535
        const val MAX_NAME = 60
    }
}
