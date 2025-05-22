package live.videosdk.android.hlsdemo.common.utils

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import live.videosdk.rtc.android.Meeting

class StreamingService : Service() {
    private val binder = LocalBinder()
    private var meeting: Meeting? = null

    inner class LocalBinder : Binder() {
        fun getService(): StreamingService = this@StreamingService
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    fun setMeeting(meeting: Meeting) {
        this.meeting = meeting
    }

    fun getMeeting(): Meeting? {
        return meeting
    }

    override fun onDestroy() {
        super.onDestroy()
        meeting?.leave()
    }
} 