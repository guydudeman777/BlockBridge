package dev.blockbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import dev.blockbridge.core.LanRelay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class RelayState(
    val running: Boolean = false,
    val server: Server? = null,
    val online: Boolean = false,
    val motd: String? = null,
    val players: Int = 0,
    val log: List<String> = emptyList(),
)

/** Foreground service so the relay keeps going with the screen off. */
class RelayService : Service() {

    companion object {
        private const val CHANNEL = "relay"
        private const val NOTE_ID = 1
        private const val ACTION_STOP = "dev.blockbridge.STOP"

        private val _state = MutableStateFlow(RelayState())
        val state: StateFlow<RelayState> = _state

        fun start(ctx: Context, s: Server) {
            val i = Intent(ctx, RelayService::class.java)
                .putExtra("id", s.id).putExtra("name", s.name)
                .putExtra("host", s.host).putExtra("port", s.port)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RelayService::class.java).setAction(ACTION_STOP))
        }

        private fun log(msg: String) = _state.update { it.copy(log = (it.log + msg).takeLast(50)) }
    }

    private var relay: LanRelay? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val server = Server(
            id = intent.getStringExtra("id") ?: "",
            name = intent.getStringExtra("name") ?: "Server",
            host = intent.getStringExtra("host") ?: return START_NOT_STICKY,
            port = intent.getIntExtra("port", 19132),
        )

        startInForeground(server)
        stopRelay()
        acquireLocks()

        _state.value = RelayState(running = true, server = server, log = _state.value.log)
        val r = LanRelay(server.host, server.port, server.name, listener = object : LanRelay.Listener {
            override fun onStatus(online: Boolean, motd: String?) {
                _state.update { it.copy(online = online, motd = motd) }
                log(if (online) "Server online" else "Server not answering")
            }
            override fun onPlayers(count: Int) { _state.update { it.copy(players = count) } }
            override fun onError(message: String) = log("Error: $message")
            override fun log(message: String) = Companion.log(message)
        })
        try {
            r.start()
            relay = r
        } catch (e: Exception) {
            log("Could not open port 19132: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRelay()
        releaseLocks()
        _state.update { it.copy(running = false, online = false, players = 0) }
        super.onDestroy()
    }

    private fun stopRelay() {
        relay?.stop()
        relay = null
    }

    private fun startInForeground(s: Server) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Relay", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RelayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val note = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Sharing ${s.name} on Wi-Fi")
            .setContentText("Open Minecraft on console → Friends → LAN Games")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTE_ID, note)
        }
    }

    private fun acquireLocks() {
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        if (multicast == null) multicast = wifi.createMulticastLock("bb-multicast").apply {
            setReferenceCounted(false); acquire()
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wifi.createWifiLock(mode, "bb-wifi").apply { setReferenceCounted(false); acquire() }
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "blockbridge:relay")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        multicast?.release(); multicast = null
        wifiLock?.release(); wifiLock = null
        wakeLock?.release(); wakeLock = null
    }
}
