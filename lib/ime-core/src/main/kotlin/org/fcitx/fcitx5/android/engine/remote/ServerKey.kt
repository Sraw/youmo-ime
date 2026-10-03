/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.remote

import java.io.IOException
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * The user's server known by its key: the SHA-256 of its certificate's public key (SPKI), in hex,
 * as `cloud/server.py` prints it. A self-signed certificate on the user's own computer has no
 * authority to vouch for it; the user compares this fingerprint once, and from then on exactly
 * that key is trusted, under any name and on any network, and nothing else.
 */
object ServerKey {

    fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded).joinToString("") { "%02X".format(it) }

    /** [fingerprint] in groups of four, as the server prints it, for a person to compare. */
    fun grouped(fingerprint: String): String = fingerprint.chunked(GROUP).joinToString(" ")

    /** Whether [text] is a [fingerprint]: 64 hex digits, spaces and case aside. */
    fun normalized(text: String): String? = text.filterNot { it.isWhitespace() }.uppercase().takeIf { s ->
        s.length == HEX_LENGTH && s.all { it in '0'..'9' || it in 'A'..'F' }
    }

    /** Trusts exactly the key [pin], whatever the certificate's name or issuer. */
    fun pinned(pin: String): Pair<SSLSocketFactory, HostnameVerifier> =
        socketFactory(PinnedTrust(pin)) to HostnameVerifier { _, _ -> true }

    /**
     * The fingerprint of the key [url]'s server presents, for the user to compare with what the
     * server printed before trusting it. Only the TLS handshake happens: nothing is sent. Blocks.
     */
    fun fetch(url: URL, timeoutMillis: Int): String {
        val seen = Seen()
        val connection = url.openConnection(Proxy.NO_PROXY) as? HttpsURLConnection ?: throw IOException("not https")
        connection.sslSocketFactory = socketFactory(seen)
        connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis
        try {
            connection.connect()
        } finally {
            connection.disconnect()
        }
        return seen.fingerprint ?: throw IOException("no certificate")
    }

    private fun socketFactory(trust: X509TrustManager): SSLSocketFactory =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }.socketFactory

    private abstract class ServerTrust : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("no client certificates")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private class PinnedTrust(private val pin: String) : ServerTrust() {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
            if (fingerprint(leaf) != pin) throw CertificateException("not the key the user trusted")
        }
    }

    /** Takes note of the key and lets the handshake finish: [fetch] sends nothing over it. */
    private class Seen : ServerTrust() {
        var fingerprint: String? = null

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            fingerprint = fingerprint(chain?.firstOrNull() ?: throw CertificateException("no certificate"))
        }
    }

    private const val GROUP = 4
    private const val HEX_LENGTH = 64
}
