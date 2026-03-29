package com.bypass.innernode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair

class InnerVpnService : android.net.VpnService() {

    // Используем обычный Thread а не корутину — MIUI не убивает его при закрытии приложения
    private var tunnelThread: Thread? = null
    private var activeBackend: GoBackend? = null
    private var activeTunnel: Tunnel? = null

    companion object {
        const val ACTION_START      = "com.bypass.innernode.START"
        const val ACTION_CONNECT    = "com.bypass.innernode.CONNECT"
        const val ACTION_DISCONNECT = "com.bypass.innernode.DISCONNECT"
        const val ACTION_KILL       = "com.bypass.innernode.KILL"

        const val EXTRA_CLIENT_IP         = "client_ip"
        const val EXTRA_SERVER_ENDPOINT   = "server_endpoint"
        const val EXTRA_SERVER_PUBLIC_KEY = "server_public_key"

        private const val CHANNEL_ID = "innernode_vpn"
        private const val NOTIF_ID   = 1
        private const val TAG        = "InnerVpnService"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "VPN Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIF_ID, buildNotification(false, null))
            }
            ACTION_CONNECT -> {
                val clientIp  = intent.getStringExtra(EXTRA_CLIENT_IP)        ?: return START_STICKY
                val endpoint  = intent.getStringExtra(EXTRA_SERVER_ENDPOINT)   ?: return START_STICKY
                val serverKey = intent.getStringExtra(EXTRA_SERVER_PUBLIC_KEY) ?: return START_STICKY
                val roomName  = intent.getStringExtra("room_name")
                startForeground(NOTIF_ID, buildNotification(true, roomName))
                startVpn(clientIp, endpoint, serverKey)
            }
            ACTION_DISCONNECT -> {
                stopVpnTunnel()
                updateNotification(false, null)
            }
            ACTION_KILL -> {
                stopVpnTunnel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startVpn(clientIp: String, endpoint: String, serverKey: String) {
        stopVpnTunnel()

        // Thread с isDaemon=false — живёт независимо от приложения
        tunnelThread = Thread {
            try {
                val privateKeyStr = KeyManager.getPrivateKey(applicationContext)
                Log.d(TAG, "Building WireGuard config, ip=$clientIp endpoint=$endpoint")

                val config = buildWgConfig(clientIp, endpoint, serverKey, privateKeyStr)
                val backend = GoBackend(applicationContext)

                val tunnel = object : Tunnel {
                    override fun getName() = "innernode"
                    override fun onStateChange(newState: Tunnel.State) {
                        Log.d(TAG, "WireGuard state: $newState")
                    }
                }

                activeBackend = backend
                activeTunnel  = tunnel

                backend.setState(tunnel, Tunnel.State.UP, config)
                Log.d(TAG, "WireGuard tunnel UP!")

            } catch (e: Exception) {
                Log.e(TAG, "VPN start error: ${e.message}", e)
                stopVpnTunnel()
            }
        }.apply {
            isDaemon = false  // не демон — не умрёт когда закроют приложение
            name = "innernode-wg"
            start()
        }
    }

    private fun buildWgConfig(
        clientIp: String,
        endpoint: String,
        serverKey: String,
        privateKeyBase64: String
    ): Config {
        val privateKey = Key.fromBase64(privateKeyBase64)
        val keyPair    = KeyPair(privateKey)

        val iface = Interface.Builder()
            .addAddress(InetNetwork.parse("$clientIp/32"))
            .setKeyPair(keyPair)
            .addDnsServer(java.net.InetAddress.getByName("1.1.1.1"))
            .build()

        val peer = Peer.Builder()
            // Весь трафик через VPN — IP сервера будет виден на 2ip и других сайтах
            .addAllowedIp(InetNetwork.parse("0.0.0.0/0"))
            .addAllowedIp(InetNetwork.parse("::/0"))
            .setEndpoint(InetEndpoint.parse(endpoint))
            .setPublicKey(Key.fromBase64(serverKey))
            .setPersistentKeepalive(25)
            .build()

        return Config.Builder()
            .setInterface(iface)
            .addPeer(peer)
            .build()
    }

    private fun stopVpnTunnel() {
        try {
            val t = activeTunnel
            val b = activeBackend
            if (t != null && b != null) {
                Thread {
                    try { b.setState(t, Tunnel.State.DOWN, null) }
                    catch (e: Exception) { Log.e(TAG, "Error stopping tunnel: ${e.message}") }
                }.start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopVpnTunnel error: ${e.message}")
        }
        activeBackend = null
        activeTunnel  = null
        tunnelThread?.interrupt()
        tunnelThread  = null
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "InnerNode VPN",
            NotificationManager.IMPORTANCE_LOW).apply {
            description = "Статус подключения InnerNode"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(connected: Boolean, roomName: String?): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val killIntent = PendingIntent.getService(
            this, 1,
            Intent(this, InnerVpnService::class.java).apply { action = ACTION_KILL },
            PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (connected) "InnerNode · Подключено" else "InnerNode · Активен"
        val text  = when {
            connected && roomName != null -> "Комната: $roomName"
            connected -> "Сеть активна"
            else -> "Нажмите для открытия"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_delete, "Выйти", killIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(connected: Boolean, roomName: String?) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(connected, roomName))
    }

    override fun onDestroy() {
        stopVpnTunnel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // НЕ останавливаем сервис когда пользователь смахнул приложение
        Log.d(TAG, "Task removed but service stays alive")
        super.onTaskRemoved(rootIntent)
    }
}