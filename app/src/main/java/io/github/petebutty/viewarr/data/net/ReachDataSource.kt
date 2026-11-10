package io.github.petebutty.viewarr.data.net

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource

/** [f] with home-or-away addresses: [Reach.map] on every URL a player opens. */
@OptIn(UnstableApi::class)
fun reachOf(f: DataSource.Factory): DataSource.Factory =
    ResolvingDataSource.Factory(f) { spec -> spec.withUri(Uri.parse(Reach.map(spec.uri.toString()))) }
