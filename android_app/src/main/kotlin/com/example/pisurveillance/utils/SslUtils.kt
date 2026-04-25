package com.example.pisurveillance.utils

import android.content.Context
import android.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.KeyStore
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.io.InputStream

/**
 * Utility class for handling SSL/TLS certificate pinning and self-signed certificates
 */
object SslUtils {

    /**
     * Get SSLSocketFactory for certificate pinning with custom CA certificates
     */
    fun getSSLSocketFactory(context: Context): SSLSocketFactory? {
        return try {
            val certificates = loadCertificatesFromPreferences(context)
            if (certificates.isNotEmpty()) {
                createSSLSocketFactory(certificates)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Get TrustManager for certificate validation
     */
    fun getTrustManager(context: Context): X509TrustManager? {
        return try {
            val certificates = loadCertificatesFromPreferences(context)
            if (certificates.isNotEmpty()) {
                createX509TrustManager(certificates)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Create SSLSocketFactory from certificates
     */
    private fun createSSLSocketFactory(certificates: List<Certificate>): SSLSocketFactory {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)

        certificates.forEachIndexed { index, cert ->
            keyStore.setCertificateEntry("cert_$index", cert)
        }

        val tmf = javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
        )
        tmf.init(keyStore)

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, tmf.trustManagers, java.security.SecureRandom())

        return sslContext.socketFactory
    }

    /**
     * Create X509TrustManager from certificates
     */
    private fun createX509TrustManager(certificates: List<Certificate>): X509TrustManager {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)

        certificates.forEachIndexed { index, cert ->
            keyStore.setCertificateEntry("cert_$index", cert)
        }

        val tmf = javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
        )
        tmf.init(keyStore)

        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /**
     * Load certificates from app preferences (PEM format stored in base64)
     */
    private fun loadCertificatesFromPreferences(context: Context): List<Certificate> {
        val prefs = PreferencesManager(context)
        val certificatePem = prefs.getCertificatePem()

        if (certificatePem.isEmpty()) {
            return emptyList()
        }

        return try {
            val certificates = mutableListOf<Certificate>()
            val cf = CertificateFactory.getInstance("X.509")
            val bytes = certificatePem.toByteArray()
            val inputStream: InputStream = bytes.inputStream()

            // Load all certificates from the PEM data
            val certCollection = cf.generateCertificates(inputStream)
            certificates.addAll(certCollection)

            certificates
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Store certificate from PEM string in preferences
     */
    fun storeCertificate(context: Context, certificatePem: String) {
        val prefs = PreferencesManager(context)
        prefs.setCertificatePem(certificatePem)
    }

    /**
     * Validate certificate fingerprint (SHA-256)
     */
    fun validateCertificateFingerprint(context: Context, expectedFingerprint: String): Boolean {
        val prefs = PreferencesManager(context)
        val storedFingerprint = prefs.getCertificateFingerprint()
        return storedFingerprint == expectedFingerprint
    }

    /**
     * Store certificate fingerprint
     */
    fun storeCertificateFingerprint(context: Context, fingerprint: String) {
        val prefs = PreferencesManager(context)
        prefs.setCertificateFingerprint(fingerprint)
    }
}
