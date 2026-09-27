package com.vinhnguyen.watchai.watch

import android.content.Context
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.vinhnguyen.watchai.watchlink.WatchLink
import kotlinx.coroutines.tasks.await
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Accepts calls from the watch app: the watch opens a voice channel, the phone runs the
 * conversation with the watch as microphone and speaker. For now the phone app has to be open on
 * the Voice tab; a background service comes with the full watch milestone.
 */
class WatchBridge(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val channels = Wearable.getChannelClient(appContext)
    private var callback: ChannelClient.ChannelCallback? = null

    fun listen(
        onCall: (ChannelClient.Channel) -> Unit,
        onHangUp: (ChannelClient.Channel) -> Unit,
    ) {
        if (callback != null) return
        val cb =
            object : ChannelClient.ChannelCallback() {
                override fun onChannelOpened(channel: ChannelClient.Channel) {
                    if (channel.path == WatchLink.VOICE_PATH) onCall(channel)
                }

                override fun onChannelClosed(
                    channel: ChannelClient.Channel,
                    closeReason: Int,
                    appSpecificErrorCode: Int,
                ) {
                    if (channel.path == WatchLink.VOICE_PATH) onHangUp(channel)
                }
            }
        callback = cb
        channels.registerChannelCallback(cb)
    }

    fun stopListening() {
        callback?.let { channels.unregisterChannelCallback(it) }
        callback = null
    }

    suspend fun answer(
        channel: ChannelClient.Channel,
        onEnd: () -> Unit,
    ): WatchAudio {
        val input = DataInputStream(BufferedInputStream(channels.getInputStream(channel).await()))
        val output = DataOutputStream(BufferedOutputStream(channels.getOutputStream(channel).await()))
        val name =
            runCatching {
                Wearable
                    .getNodeClient(appContext)
                    .connectedNodes
                    .await()
                    .firstOrNull { it.id == channel.nodeId }
                    ?.displayName
            }.getOrNull() ?: "the watch"
        return WatchAudio(input, output, name, onEnd).also { it.start() }
    }

    suspend fun hangUp(channel: ChannelClient.Channel) {
        runCatching { channels.close(channel).await() }
    }
}
