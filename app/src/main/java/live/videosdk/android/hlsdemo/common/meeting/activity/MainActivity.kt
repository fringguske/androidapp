package live.videosdk.android.hlsdemo.common.meeting.activity

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nabinbhandari.android.permissions.PermissionHandler
import com.nabinbhandari.android.permissions.Permissions
import live.videosdk.android.hlsdemo.R
import live.videosdk.android.hlsdemo.common.utils.StreamingService
import live.videosdk.android.hlsdemo.speakerMode.manageTabs.SpeakerFragment
import live.videosdk.android.hlsdemo.viewerMode.ViewerFragment
import live.videosdk.rtc.android.CustomStreamTrack
import live.videosdk.rtc.android.Meeting
import live.videosdk.rtc.android.Participant
import live.videosdk.rtc.android.VideoSDK
import live.videosdk.rtc.android.listeners.MeetingEventListener

class MainActivity : AppCompatActivity() {

    private var meeting: Meeting? = null
    private var facingMode: String? = null
    private var streamingService: StreamingService? = null
    private var bound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as StreamingService.LocalBinder
            streamingService = binder.getService()
            streamingService?.setMeeting(meeting!!)
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            streamingService = null
            bound = false
        }
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val mode = intent.getStringExtra("mode")
        val token = intent.getStringExtra("token")
        val micEnabled = intent.getBooleanExtra("micEnabled", false)
        val webcamEnabled = intent.getBooleanExtra("webcamEnabled", false)
        val meetingId = intent.getStringExtra("meetingId")
        var localParticipantName = intent.getStringExtra("participantName")
        if (localParticipantName == null) {
            localParticipantName = "John Doe"
        }

        // pass the token generated from api server
        VideoSDK.config(token)
        val customTracks: MutableMap<String, CustomStreamTrack> = HashMap()
        facingMode = "front"
        val videoCustomTrack = VideoSDK.createCameraVideoTrack(
            "h720p_w960p",
            facingMode,
            CustomStreamTrack.VideoMode.TEXT,
            true,
            this
        )
        customTracks["video"] = videoCustomTrack
        val audioCustomTrack = VideoSDK.createAudioTrack("high_quality", this)
        customTracks["mic"] = audioCustomTrack

        // create a new meeting instance
        meeting = VideoSDK.initMeeting(
            this@MainActivity, meetingId, localParticipantName,
            micEnabled, webcamEnabled, null, mode, true,customTracks
        )
        meeting!!.addEventListener(object : MeetingEventListener() {
            override fun onMeetingJoined() {
                if (meeting != null) {
                    if (mode == "CONFERENCE") {
                        meeting!!.localParticipant.pin("SHARE_AND_CAM")
                        supportFragmentManager
                            .beginTransaction()
                            .replace(R.id.mainLayout, SpeakerFragment(), "MainFragment")
                            .commit()
                        findViewById<View>(R.id.progress_layout).visibility = View.GONE
                    } else if (mode == "VIEWER") {
                        supportFragmentManager
                            .beginTransaction()
                            .replace(R.id.mainLayout, ViewerFragment(), "viewerFragment")
                            .commit()
                        findViewById<View>(R.id.progress_layout).visibility = View.GONE
                    }
                }
            }
        })
        checkPermissions()

        // Start the streaming service
        val serviceIntent = Intent(this, StreamingService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }


    private fun checkPermissions() {
        val permissionList: MutableList<String> = ArrayList()
        permissionList.add(Manifest.permission.INTERNET)
        permissionList.add(Manifest.permission.MODIFY_AUDIO_SETTINGS)
        permissionList.add(Manifest.permission.RECORD_AUDIO)
        permissionList.add(Manifest.permission.CAMERA)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) permissionList.add(
            Manifest.permission.BLUETOOTH_CONNECT
        )

        val rationale = "Please provide permissions"
        val options =
            Permissions.Options().setRationaleDialogTitle("Info").setSettingsDialogTitle("Warning")
        Permissions.check(
            this,
            permissionList.toTypedArray(),
            rationale,
            options,
            permissionHandler
        )
    }

    private val permissionHandler: PermissionHandler = object : PermissionHandler() {
        override fun onGranted() {
            if (meeting != null) meeting!!.join()
        }
    }

    fun getMeeting(): Meeting? {
        return meeting
    }

    fun getFacingMode(): String? {
        return facingMode
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        if (meeting != null) {
            stopHLS()
            meeting = null
        }
    }

    fun showLeaveDialog() {
        val alertDialog =
            MaterialAlertDialogBuilder(this@MainActivity, R.style.AlertDialogCustom).create()
        val inflater = layoutInflater
        val dialogView = inflater.inflate(R.layout.dialog_style, null)
        alertDialog.setView(dialogView)
        val message = dialogView.findViewById<TextView>(R.id.message)
        message.text = "Are you sure you want to leave?"
        val positiveButton = dialogView.findViewById<Button>(R.id.positiveBtn)
        positiveButton.text = "Yes"
        positiveButton.setOnClickListener {
            if (bound) {
                unbindService(serviceConnection)
                bound = false
            }
            stopService(Intent(this, StreamingService::class.java))
            meeting!!.leave()
            alertDialog.dismiss()
            val intents = Intent(this@MainActivity, CreateOrJoinActivity::class.java)
            intents.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
            startActivity(intents)
            finish()
        }
        val negativeButton = dialogView.findViewById<Button>(R.id.negativeBtn)
        negativeButton.text = "No"
        negativeButton.setOnClickListener { alertDialog.dismiss() }
        alertDialog.show()
    }

    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else {
            showLeaveDialog()
        }
    }

    private fun stopHLS() {
        val participants: Iterator<Participant> = meeting!!.participants.values.iterator()
        var speakers = 0
        for (i in 0 until meeting!!.participants.size) {
            val participant = participants.next()
            if (participant.mode == "CONFERENCE") {
                speakers++
            }
        }
        if (speakers == 0) meeting!!.stopHls()
    }
}