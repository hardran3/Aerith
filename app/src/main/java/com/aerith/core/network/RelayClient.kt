package com.aerith.core.network

import android.util.Log
import com.aerith.core.nostr.Event
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RelayClient(private val url: String) {
    private val client = OkHttpClient.Builder()
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val eventAdapter = moshi.adapter(Event::class.java)

    fun fetchEvent(filterJson: String): List<Event> {
        Log.d("RelayClient", "fetchEvent called for $url. Filter: $filterJson")
        val events = mutableListOf<Event>()
        val latch = CountDownLatch(1)
        val subId = UUID.randomUUID().toString()
        var failure: Throwable? = null

        val request = Request.Builder().url(url).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("RelayClient", "WebSocket OPENED: $url")
                val req = "[\"REQ\", \"$subId\", $filterJson]"
                webSocket.send(req)
                Log.d("RelayClient", "Sent REQ: $req")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.v("RelayClient", "Message from $url: $text") // Verbose logging for all messages
                try {
                    val jsonArray = moshi.adapter(List::class.java).fromJson(text) ?: return
                    val type = jsonArray[0] as? String ?: return
                    
                    if (type == "EVENT" && jsonArray.size >= 3) {
                        Log.d("RelayClient", "Received EVENT from $url")
                        val eventMap = jsonArray[2] as? Map<*, *>
                        if (eventMap != null) {
                            val eventJson = moshi.adapter(Map::class.java).toJson(eventMap)
                            val event = eventAdapter.fromJson(eventJson)
                            if (event != null) {
                                events.add(event)
                            }
                        }
                    } else if (type == "EOSE" || type == "CLOSED" || type == "NOTICE") {
                        latch.countDown()
                    }
                } catch (e: Exception) {
                    Log.e("RelayClient", "Error parsing message", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("RelayClient", "WebSocket Failure on $url", t)
                failure = t
                latch.countDown()
            }
            
             override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                latch.countDown()
            }
        }

        val ws = client.newWebSocket(request, listener)
        
        // Wait for EOSE or timeout (10 seconds for discovery)
        val success = latch.await(10, TimeUnit.SECONDS)
        ws.close(1000, "Done")
        
        if (failure != null) {
            throw Exception("Relay connection failed: ${failure?.message}", failure)
        }
        
        return events
    }
}
