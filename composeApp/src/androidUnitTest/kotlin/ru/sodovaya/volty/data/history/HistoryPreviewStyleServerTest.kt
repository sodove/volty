package ru.sodovaya.volty.data.history

import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.*

class HistoryPreviewStyleServerTest {
    private fun request(url: String, method: String = "GET"): Pair<Int,String> {
        val connection=URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout=2000;connection.readTimeout=2000;connection.requestMethod=method
        try{
            val status=connection.responseCode
            val content=(if(status==200)connection.inputStream else connection.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()
            return status to content
        }finally{connection.disconnect()}
    }
    @Test fun servesOnlyTheRegisteredImmutableStylesOnIpv4Loopback() {
        HistoryPreviewStyleServer().use{server->
            val bytes="{\"version\":8,\"name\":\"Тест\"}".toByteArray()
            val url=server.register("a".repeat(64),bytes)
            assertEquals("127.0.0.1",URL(url).host);bytes[0]=0
            assertEquals(200 to "{\"version\":8,\"name\":\"Тест\"}",request(url))
            assertEquals(404,request(url.replace("a".repeat(64),"b".repeat(64))).first)
            assertEquals(405,request(url,"POST").first)
        }
    }
    @Test fun invalidOrExcessStylesCannotGrowTheRegistryOrReplaceAContentKey() {
        HistoryPreviewStyleServer().use{server->
            assertFailsWith<IllegalArgumentException>{server.register("../bad",byteArrayOf(1))}
            assertFailsWith<IllegalArgumentException>{server.register("a".repeat(64),ByteArray(262145))}
            val url=server.register("a".repeat(64),byteArrayOf(1))
            assertEquals(url,server.register("a".repeat(64),byteArrayOf(1)))
            assertFailsWith<IllegalArgumentException>{server.register("a".repeat(64),byteArrayOf(2))}
            server.register("b".repeat(64),byteArrayOf(2))
            assertFailsWith<IllegalArgumentException>{server.register("c".repeat(64),byteArrayOf(3))}
            assertEquals(200,request(url).first)
        }
    }
    @Test fun closeRetiresTheListenerAndRejectsNewRegistrations() {
        val server=HistoryPreviewStyleServer();val url=server.register("a".repeat(64),byteArrayOf(1))
        server.close()
        assertFalse(server.isListening)
        // The freed ephemeral port may be re-bound by another process (parallel builds), so a refused
        // connection is not guaranteed: what must hold is that our style is no longer served there.
        val after=runCatching{request(url)}.getOrNull()
        assertTrue(after==null || after.first!=200 || after.second.toByteArray().contentEquals(byteArrayOf(1)).not())
        assertFailsWith<IllegalStateException>{server.register("b".repeat(64),byteArrayOf(2))}
    }
}
