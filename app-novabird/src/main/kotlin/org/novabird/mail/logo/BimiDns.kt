package org.novabird.mail.logo

import android.annotation.SuppressLint
import android.net.DnsResolver
import android.os.CancellationSignal
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.minidns.dnsmessage.DnsMessage
import org.minidns.record.TXT

/** TXT lookups for BIMI with the system resolver; minidns only reads the answer. Blocking, for worker threads. */
internal object BimiDns {
    private const val TYPE_TXT = 16
    private const val DNS_TIMEOUT_MS = 2_000L
    private const val RCODE_NO_ERROR = 0
    private const val RCODE_NX_DOMAIN = 3

    /** The TXT records of [name]; empty if there are none; throws [IOException] if the lookup failed. */
    // the system resolver uses Private DNS if set
    // the raw query takes any record type; the annotation only lists the types of the parsed query
    @SuppressLint("WrongConstant")
    fun txtRecords(name: String): List<String> {
        val latch = CountDownLatch(1)
        var answer: ByteArray? = null
        val signal = CancellationSignal()
        DnsResolver.getInstance().rawQuery(
            null,
            name,
            DnsResolver.CLASS_IN,
            TYPE_TXT,
            DnsResolver.FLAG_EMPTY,
            Runnable::run,
            signal,
            object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(result: ByteArray, rcode: Int) {
                    // only "found" and "no such name" are answers; resolver trouble (SERVFAIL, REFUSED) is an error
                    if (rcode == RCODE_NO_ERROR || rcode == RCODE_NX_DOMAIN) answer = result
                    latch.countDown()
                }

                override fun onError(error: DnsResolver.DnsException) {
                    latch.countDown()
                }
            },
        )
        if (!latch.await(DNS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            signal.cancel()
            throw IOException("DNS timeout")
        }
        val bytes = answer ?: throw IOException("DNS error")
        return DnsMessage(bytes).answerSection.mapNotNull { (it.payload as? TXT)?.text }
    }
}
