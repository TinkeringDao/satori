package com.satori.domain.model

import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadFailureTest {

    @Test
    fun `a host that cannot be found is no connection`() {
        // What airplane mode throws first: Unable to resolve host.
        assertEquals(DownloadFailure.NO_CONNECTION, DownloadFailure.of(UnknownHostException("huggingface.co")))
    }

    @Test
    fun `a refused or reset connection and a timeout are no connection`() {
        assertEquals(DownloadFailure.NO_CONNECTION, DownloadFailure.of(ConnectException("Connection refused")))
        assertEquals(DownloadFailure.NO_CONNECTION, DownloadFailure.of(SocketTimeoutException("timeout")))
        assertEquals(
            DownloadFailure.NO_CONNECTION,
            DownloadFailure.of(SocketException("Software caused connection abort"))
        )
    }

    @Test
    fun `a socket Android refuses to this app is blocked, not no connection`() {
        // What an app whose network is denied gets at the socket or the connect.
        assertEquals(
            DownloadFailure.BLOCKED,
            DownloadFailure.of(SocketException("socket failed: EPERM (Operation not permitted)"))
        )
        assertEquals(
            DownloadFailure.BLOCKED,
            DownloadFailure.of(ConnectException("failed to connect to /10.0.2.2 (port 8099): connect failed: EPERM (Operation not permitted)"))
        )
        assertEquals(
            DownloadFailure.BLOCKED,
            DownloadFailure.of(DownloadException(DownloadFailure.of(SocketException("socket failed: EPERM")), "speech download"))
        )
    }

    @Test
    fun `a stream that ends early is no connection`() {
        assertEquals(DownloadFailure.NO_CONNECTION, DownloadFailure.of(EOFException()))
    }

    @Test
    fun `a connection cut in the middle of a file is no connection, not a damaged file`() {
        // What HttpURLConnection throws when the answer ends before its length.
        assertEquals(DownloadFailure.NO_CONNECTION, DownloadFailure.of(ProtocolException("unexpected end of stream")))
        assertEquals(
            DownloadFailure.NO_CONNECTION,
            DownloadFailure.of(IOException("unexpected end of stream on Connection{10.0.2.2:8099}"))
        )
    }

    @Test
    fun `a full disk is not enough storage`() {
        assertEquals(
            DownloadFailure.NOT_ENOUGH_STORAGE,
            DownloadFailure.of(IOException("write failed: ENOSPC (No space left on device)"))
        )
        assertEquals(
            DownloadFailure.NOT_ENOUGH_STORAGE,
            DownloadFailure.of(FileNotFoundException("/models/x.partial: open failed: ENOSPC (No space left on device)"))
        )
    }

    @Test
    fun `another input and output error is not blamed on storage`() {
        assertEquals(DownloadFailure.OTHER, DownloadFailure.of(IOException("Stream closed")))
    }

    @Test
    fun `a typed download exception keeps its reason`() {
        assertEquals(
            DownloadFailure.SERVER,
            DownloadFailure.of(DownloadException(DownloadFailure.SERVER, "HTTP 503"))
        )
    }

    @Test
    fun `the reason is found through wrapped exceptions`() {
        val wrapped = RuntimeException("outer", IllegalStateException("middle", UnknownHostException("host")))

        assertEquals(DownloadFailure.NO_CONNECTION, DownloadFailure.of(wrapped))
    }

    @Test
    fun `the outer typed reason wins over what it wraps`() {
        val error = DownloadException(DownloadFailure.SERVER, "HTTP 500", UnknownHostException())

        assertEquals(DownloadFailure.SERVER, DownloadFailure.of(error))
    }

    @Test
    fun `anything else is other`() {
        assertEquals(DownloadFailure.OTHER, DownloadFailure.of(IllegalStateException("Download failed")))
        assertEquals(DownloadFailure.OTHER, DownloadFailure.of(Exception()))
    }

    @Test
    fun `a chain of causes that loops back ends`() {
        val first = Exception("first")
        val second = Exception("second", first)
        first.initCause(second)

        assertEquals(DownloadFailure.OTHER, DownloadFailure.of(first))
    }
}
