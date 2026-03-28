package com.bypass.innernode

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface VpnApi {
    @GET("/rooms")
    suspend fun getRooms(): List<Room>

    @POST("/create_room")
    suspend fun createRoom(@Body request: CreateRoomRequest): VpnConfigResponse

    @POST("/join_room")
    suspend fun joinRoom(@Body request: JoinRoomRequest): VpnConfigResponse

    @POST("/leave_room")
    suspend fun leaveRoom(@Body request: LeaveRoomRequest)

    @GET("/room_peers/{room_name}")
    suspend fun getRoomPeers(@Path("room_name") roomName: String): List<Peer>
}
