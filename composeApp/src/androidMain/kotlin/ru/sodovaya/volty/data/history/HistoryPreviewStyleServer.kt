package ru.sodovaya.volty.data.history

import java.io.Closeable
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Loopback-only transport for two bundled derived styles; never serves files. */
class HistoryPreviewStyleServer : Closeable {
    private val styles=ConcurrentHashMap<String,ByteArray>()
    private val executor=Executors.newSingleThreadExecutor { runnable -> Thread(runnable,"HistoryPreviewStyles").apply { isDaemon=true } }
    private var socket: ServerSocket?=null
    @Volatile private var closed=false

    @Synchronized fun register(key: String, json: ByteArray): String {
        check(!closed)
        require(key.length==64 && key.all { it in '0'..'9' || it in 'a'..'f' })
        require(json.size in 1..262144)
        val previous=styles[key]
        require(previous?.contentEquals(json) != false)
        require(previous != null || styles.size < 2)
        styles[key]=json.copyOf()
        if(socket==null){
            val listener=ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))
            socket=listener
            executor.execute {
                while(!listener.isClosed){
                    val client=try{listener.accept()}catch(_:IOException){break}
                    try{client.use(::serve)}catch(_:IOException){ /* A disconnected/slow client does not retire the listener. */ }
                }
            }
        }
        return "http://127.0.0.1:${checkNotNull(socket).localPort}/styles/$key.json"
    }
    private fun serve(client: Socket) {
        client.soTimeout=2000
        val input=BufferedInputStream(client.getInputStream())
        val header=ByteArrayOutputStream();var tail=0
        while(header.size()<4096){
            val next=input.read();if(next<0)return
            header.write(next);tail=(tail shl 8) or next
            if(tail==0x0D0A0D0A)break
        }
        if(tail!=0x0D0A0D0A){respond(client,431,ByteArray(0));return}
        val first=header.toString("US-ASCII").substringBefore("\r\n").split(' ')
        if(first.size!=3){respond(client,400,ByteArray(0));return}
        if(first[0]!="GET"){respond(client,405,ByteArray(0));return}
        val path=first[1]
        val key=path.removePrefix("/styles/").removeSuffix(".json")
        val bytes=styles[key]?.takeIf{path=="/styles/$key.json"}
        respond(client,if(bytes==null)404 else 200,bytes?:ByteArray(0))
    }
    private fun respond(client: Socket,status: Int,body: ByteArray) {
        val output=client.getOutputStream()
        output.write(("HTTP/1.1 $status ${if(status==200)"OK" else "Unavailable"}\r\nContent-Type: application/json; charset=utf-8\r\n"+
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
        output.write(body);output.flush()
    }
    @Synchronized override fun close() {
        closed=true;socket?.close();socket=null;styles.clear();executor.shutdownNow()
    }
}
