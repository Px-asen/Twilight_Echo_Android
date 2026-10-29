package com.twilightecho.android.plugins

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal class TestRequest(val method: String, val path: String, val headers: List<Pair<String, String>>, val body: ByteArray) {
    fun header(name: String) = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
}
/** [length] false sends no Content-Length (the body ends with the connection); with [pause] the second half waits for it. */
internal class TestReply(val status: Int, val body: ByteArray = ByteArray(0), val headers: List<Pair<String, String>> = emptyList(),
    val length: Boolean = true, val pause: CountDownLatch? = null)

/**
 * A loopback HTTP/1.1 server answering one request per connection. Unit tests compile against android.jar,
 * which has no `com.sun.net.httpserver`. Header bytes are read and written as ISO-8859-1.
 */
internal class TestHttpServer(private val handler: (TestRequest) -> TestReply) : Closeable {
    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int = socket.localPort

    init {
        thread(isDaemon = true) {
            while (true) {
                val client = try { socket.accept() } catch (_: IOException) { break }
                thread(isDaemon = true) { client.use { serve(it) } }
            }
        }
    }

    private fun serve(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val head = ByteArrayOutputStream()
        while (!head.toString(Charsets.ISO_8859_1.name()).endsWith("\r\n\r\n")) head.write(input.read().takeIf { it >= 0 } ?: return)
        val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n").filter { it.isNotEmpty() }
        val (method, target) = lines[0].split(' ')
        val headers = lines.drop(1).map { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
        val length = headers.firstOrNull { it.first.equals("Content-Length", ignoreCase = true) }?.second?.toInt() ?: 0
        val reply = handler(TestRequest(method, target.substringBefore('?'), headers, input.readNBytes(length)))
        val response = StringBuilder("HTTP/1.1 ${reply.status} Test\r\n")
        reply.headers.forEach { (name, value) -> response.append("$name: $value\r\n") }
        if (reply.length) response.append("Content-Length: ${reply.body.size}\r\n")
        response.append("Connection: close\r\n\r\n")
        try {
            client.getOutputStream().apply {
                write(response.toString().toByteArray(Charsets.ISO_8859_1))
                val half = if (reply.pause == null) reply.body.size else reply.body.size / 2
                write(reply.body, 0, half); flush()
                reply.pause?.await(10, TimeUnit.SECONDS)
                write(reply.body, half, reply.body.size - half); flush()
            }
        } catch (_: IOException) {
            // The client stopped reading (an oversized body); nothing to report.
        }
    }

    override fun close() = socket.close()
}
