package com.tdev.mplayr.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SleepTimerReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val i = Intent(ctx, PlayerService::class.java).setAction(PlayerService.ACTION_PAUSE)
        ctx.startService(i)
    }
}
