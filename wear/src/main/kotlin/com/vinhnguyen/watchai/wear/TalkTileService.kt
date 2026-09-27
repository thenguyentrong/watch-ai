package com.vinhnguyen.watchai.wear

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture

/** A tile with one big "Talk" button: tapping it opens the app, which starts a conversation. */
class TalkTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val open =
            ActionBuilders.LaunchAction
                .Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity
                        .Builder()
                        .setPackageName(packageName)
                        .setClassName(WearActivity::class.java.name)
                        .build(),
                ).build()
        val button =
            LayoutElementBuilders.Box
                .Builder()
                .setWidth(dp(BUTTON_DP))
                .setHeight(dp(BUTTON_DP))
                .setModifiers(
                    ModifiersBuilders.Modifiers
                        .Builder()
                        .setBackground(
                            ModifiersBuilders.Background
                                .Builder()
                                .setColor(argb(BLUE))
                                .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(BUTTON_DP / 2)).build())
                                .build(),
                        ).setClickable(ModifiersBuilders.Clickable.Builder().setId("talk").setOnClick(open).build())
                        .setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription("Talk to Buddy").build())
                        .build(),
                ).addContent(
                    LayoutElementBuilders.Text
                        .Builder()
                        .setText("Talk")
                        .setFontStyle(
                            LayoutElementBuilders.FontStyle
                                .Builder()
                                .setSize(sp(22f))
                                .setColor(argb(WHITE))
                                .build(),
                        ).build(),
                ).build()
        val root =
            LayoutElementBuilders.Box
                .Builder()
                .setWidth(expand())
                .setHeight(expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .addContent(button)
                .build()
        val tile =
            TileBuilders.Tile
                .Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
                .build()
        return CallbackToFutureAdapter.getFuture { completer ->
            completer.set(tile)
            "tile"
        }
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        val resources = ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()
        return CallbackToFutureAdapter.getFuture { completer ->
            completer.set(resources)
            "resources"
        }
    }

    private companion object {
        const val RESOURCES_VERSION = "1"
        const val BUTTON_DP = 120f
        const val BLUE = 0xFF3D7BFF.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
