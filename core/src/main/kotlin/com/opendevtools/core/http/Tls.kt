package com.opendevtools.core.http

import java.net.HttpURLConnection
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Certificate validation can be turned off per connection, because both a fresh Proxmox install
 * and a self-hosted Vaultwarden commonly run behind a self-signed certificate. This never touches
 * the JVM-wide defaults, so the rest of the IDE keeps validating normally.
 */
object Tls {

    fun relax(connection: HttpURLConnection) {
        if (connection !is HttpsURLConnection) return
        connection.sslSocketFactory = trustAllSocketFactory
        connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
    }

    private val trustAllSocketFactory: SSLSocketFactory by lazy {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        context.socketFactory
    }
}
