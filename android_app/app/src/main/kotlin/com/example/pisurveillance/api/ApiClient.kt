package com.example.pisurveillance.api

import android.content.Context
import com.example.pisurveillance.utils.SslUtils
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier

/**
 * API Client factory for creating configured Retrofit instances
 */
object ApiClient {
    private var retrofit: Retrofit? = null
    private var okHttpClient: OkHttpClient? = null

    /**
     * Get or create Retrofit instance with the given base URL
     */
    fun getRetrofit(baseUrl: String, context: Context, certificatePinning: Boolean = false): Retrofit {
        // If URL changed, recreate the client
        if (retrofit?.baseUrl()?.toString() != normalizeUrl(baseUrl)) {
            retrofit = null
            okHttpClient = null
        }

        if (retrofit == null) {
            retrofit = Retrofit.Builder()
                .baseUrl(normalizeUrl(baseUrl))
                .client(getOkHttpClient(context, certificatePinning))
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }
        return retrofit!!
    }

    /**
     * Get or create OkHttpClient with SSL/TLS configuration
     */
    fun getOkHttpClient(context: Context, certificatePinning: Boolean = false): OkHttpClient {
        if (okHttpClient == null) {
            val builder = OkHttpClient.Builder()

            // Set timeouts
            builder.connectTimeout(30, TimeUnit.SECONDS)
            builder.readTimeout(30, TimeUnit.SECONDS)
            builder.writeTimeout(30, TimeUnit.SECONDS)

            // Add logging interceptor
            val loggingInterceptor = HttpLoggingInterceptor()
            loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY)
            builder.addInterceptor(loggingInterceptor)

            // Configure SSL/TLS
            if (certificatePinning) {
                try {
                    val sslSocketFactory = SslUtils.getSSLSocketFactory(context)
                    if (sslSocketFactory != null) {
                        val trustManager = SslUtils.getTrustManager(context)
                        if (trustManager != null) {
                            builder.sslSocketFactory(sslSocketFactory, trustManager)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                // For development/local network: trust all certificates and bypass hostname verification
                try {
                    val trustAllManager = SslUtils.getTrustAllManager()
                    val sslSocketFactory = SslUtils.getTrustAllSSLSocketFactory()
                    if (sslSocketFactory != null) {
                        builder.sslSocketFactory(sslSocketFactory, trustAllManager)
                        builder.hostnameVerifier(HostnameVerifier { _, _ -> true })
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            okHttpClient = builder.build()
        }
        return okHttpClient!!
    }

    /**
     * Create service interface
     */
    fun createService(
        serviceClass: Class<SurveillanceService>,
        baseUrl: String,
        context: Context,
        certificatePinning: Boolean = false
    ): SurveillanceService {
        return getRetrofit(baseUrl, context, certificatePinning).create(serviceClass)
    }

    /**
     * Normalize URL to ensure proper format with trailing slash
     */
    private fun normalizeUrl(url: String): String {
        var normalizedUrl = url
        if (!normalizedUrl.startsWith("http://") && !normalizedUrl.startsWith("https://")) {
            normalizedUrl = "https://$normalizedUrl"
        }
        if (!normalizedUrl.endsWith("/")) {
            normalizedUrl = "$normalizedUrl/"
        }
        return normalizedUrl
    }

    /**
     * Clear the cached Retrofit instance (when switching servers)
     */
    fun clearCache() {
        retrofit = null
        okHttpClient = null
    }
}
