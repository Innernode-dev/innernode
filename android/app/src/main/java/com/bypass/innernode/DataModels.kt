package com.bypass.innernode

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

data class Room(
    val name: String,
    val users: Int,
    val max: Int,
    @SerializedName("is_private") val isPrivate: Boolean
)

data class Peer(
    val ip: String,
    val role: String
)

data class CreateRoomRequest(
    @SerializedName("room_name") val roomName: String,
    val password: String?,
    @SerializedName("public_key") val publicKey: String,
    @SerializedName("max_users") val maxUsers: Int = 20
)

data class JoinRoomRequest(
    @SerializedName("room_name") val roomName: String,
    val password: String?,
    @SerializedName("public_key") val publicKey: String
)

data class LeaveRoomRequest(
    @SerializedName("room_name") val roomName: String,
    @SerializedName("public_key") val publicKey: String
)

@Parcelize
data class VpnConfigResponse(
    @SerializedName("client_ip") val clientIp: String,
    @SerializedName("server_endpoint") val serverEndpoint: String,
    @SerializedName("server_public_key") val serverPublicKey: String
) : Parcelable