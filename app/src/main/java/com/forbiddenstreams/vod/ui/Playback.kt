package com.forbiddenstreams.vod.ui

import android.content.Context
import android.widget.Toast
import com.forbiddenstreams.vod.appContainer
import com.forbiddenstreams.vod.data.ResumeEntry
import com.forbiddenstreams.vod.player.PlayerActivity

object Playback {
    /** Opens the player for a movie or episode, resuming from the saved position unless [fromStart]. */
    fun start(context: Context, entry: ResumeEntry, fromStart: Boolean = false) {
        val container = context.appContainer()
        val repo = container.repository
        val account = container.account
        if (repo == null || account == null) {
            Toast.makeText(context, "Please sign in again", Toast.LENGTH_SHORT).show()
            return
        }
        val startMs = if (fromStart) 0L else entry.positionMs
        context.startActivity(
            PlayerActivity.intent(context, repo.streamUrl(entry), entry, account.id, startMs),
        )
    }
}
