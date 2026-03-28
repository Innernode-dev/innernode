package com.bypass.innernode

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RoomsViewModel(application: Application) : AndroidViewModel(application) {

    private val api     = RetrofitClient.api
    private val context = getApplication<Application>().applicationContext
    private val prefs   = context.getSharedPreferences("innernode_state", Context.MODE_PRIVATE)

    private val _rooms        = MutableStateFlow<List<Room>>(emptyList())
    val rooms = _rooms.asStateFlow()

    private val _currentPeers = MutableStateFlow<List<Peer>>(emptyList())
    val currentPeers = _currentPeers.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    var vpnState        by mutableStateOf(VpnState.DISCONNECTED)
    var currentRoomName by mutableStateOf<String?>(null)
        private set
    var myIp            by mutableStateOf<String?>(null)
        private set

    enum class VpnState { CONNECTED, DISCONNECTED, CONNECTING }

    init {
        restoreState()

        viewModelScope.launch {
            while (isActive) {
                if (vpnState == VpnState.CONNECTED) {
                    if (currentRoomName != null) fetchPeers() else fetchRooms()
                }
                delay(5000)
            }
        }
    }

    // Восстанавливаем туннель с тем же IP — не меняем пира на сервере
    private fun restoreState() {
        val savedRoom     = prefs.getString("room_name", null)       ?: return
        val savedIp       = prefs.getString("my_ip", null)           ?: return
        val savedEndpoint = prefs.getString("server_endpoint", null) ?: return
        val savedPubKey   = prefs.getString("server_pubkey", null)   ?: return
        val wasActive     = prefs.getBoolean("was_active", false)

        if (!wasActive) return

        // Просто переподнимаем туннель с теми же параметрами — IP не меняется!
        vpnState        = VpnState.CONNECTED
        currentRoomName = savedRoom
        myIp            = savedIp

        context.startService(
            Intent(context, InnerVpnService::class.java).apply {
                action = InnerVpnService.ACTION_CONNECT
                putExtra(InnerVpnService.EXTRA_CLIENT_IP, savedIp)
                putExtra(InnerVpnService.EXTRA_SERVER_ENDPOINT, savedEndpoint)
                putExtra(InnerVpnService.EXTRA_SERVER_PUBLIC_KEY, savedPubKey)
                putExtra("room_name", savedRoom)
            }
        )

        viewModelScope.launch { fetchPeers() }
    }

    private fun saveState(roomName: String, clientIp: String, serverEndpoint: String, serverPubKey: String) {
        prefs.edit()
            .putString("room_name", roomName)
            .putString("my_ip", clientIp)
            .putString("server_endpoint", serverEndpoint)
            .putString("server_pubkey", serverPubKey)
            .putBoolean("was_active", true)
            .apply()
    }

    private fun clearState() {
        prefs.edit()
            .remove("room_name")
            .remove("my_ip")
            .remove("server_endpoint")
            .remove("server_pubkey")
            .putBoolean("was_active", false)
            .apply()
    }

    fun connect() {
        context.startService(
            Intent(context, InnerVpnService::class.java).apply {
                action = InnerVpnService.ACTION_START
            }
        )
        vpnState = VpnState.CONNECTED
        viewModelScope.launch { fetchRooms() }
    }

    fun disconnect() {
        leaveRoom()
        context.startService(
            Intent(context, InnerVpnService::class.java).apply {
                action = InnerVpnService.ACTION_DISCONNECT
            }
        )
        vpnState = VpnState.DISCONNECTED
        myIp = null
        clearState()
    }

    fun killService() {
        leaveRoom()
        context.startService(
            Intent(context, InnerVpnService::class.java).apply {
                action = InnerVpnService.ACTION_KILL
            }
        )
        vpnState = VpnState.DISCONNECTED
        myIp = null
        clearState()
    }

    fun leaveCurrentRoom() {
        leaveRoom()
        context.startService(
            Intent(context, InnerVpnService::class.java).apply {
                action = InnerVpnService.ACTION_DISCONNECT
            }
        )
        myIp = null
        clearState()
        viewModelScope.launch { fetchRooms() }
    }

    fun createRoom(name: String, password: String?) {
        viewModelScope.launch {
            try {
                val pubKey = KeyManager.getPublicKey(context)
                val res = api.createRoom(
                    CreateRoomRequest(roomName = name, password = password,
                        publicKey = pubKey, maxUsers = 20)
                )
                myIp = res.clientIp
                context.startService(
                    Intent(context, InnerVpnService::class.java).apply {
                        action = InnerVpnService.ACTION_CONNECT
                        putExtra(InnerVpnService.EXTRA_CLIENT_IP, res.clientIp)
                        putExtra(InnerVpnService.EXTRA_SERVER_ENDPOINT, res.serverEndpoint)
                        putExtra(InnerVpnService.EXTRA_SERVER_PUBLIC_KEY, res.serverPublicKey)
                        putExtra("room_name", name)
                    }
                )
                currentRoomName = name
                saveState(name, res.clientIp, res.serverEndpoint, res.serverPublicKey)
                fetchPeers()
            } catch (e: Exception) {
                _errorMessage.value = "Ошибка создания комнаты: ${e.message}"
            }
        }
    }

    fun joinRoom(name: String, pass: String?) {
        viewModelScope.launch {
            try {
                val pubKey = KeyManager.getPublicKey(context)
                val res = api.joinRoom(
                    JoinRoomRequest(roomName = name,
                        password = if (pass.isNullOrBlank()) null else pass,
                        publicKey = pubKey)
                )
                myIp = res.clientIp
                context.startService(
                    Intent(context, InnerVpnService::class.java).apply {
                        action = InnerVpnService.ACTION_CONNECT
                        putExtra(InnerVpnService.EXTRA_CLIENT_IP, res.clientIp)
                        putExtra(InnerVpnService.EXTRA_SERVER_ENDPOINT, res.serverEndpoint)
                        putExtra(InnerVpnService.EXTRA_SERVER_PUBLIC_KEY, res.serverPublicKey)
                        putExtra("room_name", name)
                    }
                )
                currentRoomName = name
                saveState(name, res.clientIp, res.serverEndpoint, res.serverPublicKey)
                fetchPeers()
            } catch (e: Exception) {
                _errorMessage.value = "Неверный пароль или комната полна"
            }
        }
    }

    private fun leaveRoom() {
        val room = currentRoomName ?: return
        currentRoomName = null
        _currentPeers.value = emptyList()
        viewModelScope.launch {
            try {
                api.leaveRoom(LeaveRoomRequest(room, KeyManager.getPublicKey(context)))
            } catch (_: Exception) {}
        }
    }

    private suspend fun fetchRooms() {
        try { _rooms.value = api.getRooms() }
        catch (e: Exception) { _errorMessage.value = "Ошибка получения комнат" }
    }

    private suspend fun fetchPeers() {
        currentRoomName?.let { name ->
            try { _currentPeers.value = api.getRoomPeers(name) }
            catch (_: Exception) {}
        }
    }

    fun clearError() { _errorMessage.value = null }
}