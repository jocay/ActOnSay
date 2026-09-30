package com.voice.actonsay.ai

import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object SmartDns : Dns {
    private const val TAG = "SmartDns"
    private val cache = ConcurrentHashMap<String, List<InetAddress>>()

    // Direct HTTP client used ONLY for resolving DNS by IP to avoid recursive DNS lookups
    private val rawHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .dns(Dns.SYSTEM)
        .build()

    private val STATIC_FALLBACKS = mapOf(
        "api.xiaomimimo.com" to listOf("111.13.213.62", "111.13.213.63"),
        "api.deepseek.com" to listOf("117.163.57.167", "111.4.224.113"),
        "api.openai.com" to listOf("104.18.7.192", "104.18.6.192")
    )

    override fun lookup(hostname: String): List<InetAddress> {
        // 1. Check local in-memory cache
        cache[hostname]?.let { return it }

        // 2. Try standard system DNS first
        try {
            // IPv4 优先：移动网络上的 IPv6 路由经常不通，放前面会白等一次连接超时
            val systemAddresses = Dns.SYSTEM.lookup(hostname).sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
            if (systemAddresses.isNotEmpty()) {
                cache[hostname] = systemAddresses
                return systemAddresses
            }
        } catch (e: Exception) {
            Log.w(TAG, "System DNS lookup failed for $hostname, trying HTTP DNS: ${e.message}")
        }

        // 3. Fallback to AliDNS HTTP DNS via direct IP (223.5.5.5)
        try {
            val httpDnsUrl = "http://223.5.5.5/resolve?name=$hostname&type=A"
            val request = Request.Builder().url(httpDnsUrl).build()
            rawHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val answers = json.optJSONArray("Answer")
                    if (answers != null && answers.length() > 0) {
                        val addresses = mutableListOf<InetAddress>()
                        for (i in 0 until answers.length()) {
                            val item = answers.getJSONObject(i)
                            // type 1 == A record
                            if (item.optInt("type") == 1) {
                                val ip = item.optString("data")
                                if (ip.isNotBlank()) {
                                    try {
                                        addresses.add(InetAddress.getByName(ip))
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                        if (addresses.isNotEmpty()) {
                            Log.i(TAG, "HTTP DNS resolved $hostname -> $addresses")
                            cache[hostname] = addresses
                            return addresses
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTTP DNS lookup failed for $hostname: ${e.message}")
        }

        // 4. Fallback to static IPs if configured
        val fallbackIps = STATIC_FALLBACKS[hostname.lowercase()]
        if (!fallbackIps.isNullOrEmpty()) {
            val addresses = fallbackIps.mapNotNull { ip ->
                try {
                    InetAddress.getByName(ip)
                } catch (_: Exception) {
                    null
                }
            }
            if (addresses.isNotEmpty()) {
                Log.i(TAG, "Static fallback resolved $hostname -> $addresses")
                cache[hostname] = addresses
                return addresses
            }
        }

        throw UnknownHostException("Unable to resolve host $hostname via System, HTTP, or Static DNS")
    }
}
