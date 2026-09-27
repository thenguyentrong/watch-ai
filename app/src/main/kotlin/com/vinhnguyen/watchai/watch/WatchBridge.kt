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

/** Opens the streams of a voice channel the watch started, and closes it again. */
class WatchBridge(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val channels = Wearable.getChannelClient(appContext)
    suspend fun answer(
        channel: ChannelClient.Channel,
        route: Route,
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
        return WatchAudio(input, output, name, route, onEnd).also { it.start() }
    }

    suspend fun hangUp(channel: ChannelClient.Channel) {
        runCatching { channels.close(channel).await() }
    }
}
