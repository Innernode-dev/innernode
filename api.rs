use anyhow::Result;
use serde::{Deserialize, Serialize};

const BASE_URL: &str = "https://bypass-vpn.duckdns.org";

#[derive(Debug, Deserialize, Clone)]
pub struct Room {
    pub name: String,
    pub users: i32,
    pub max: i32,
    pub is_private: bool,
}

#[derive(Debug, Deserialize, Clone)]
pub struct Peer {
    pub ip: String,
    pub role: String,
}

#[derive(Debug, Deserialize)]
pub struct VpnConfig {
    pub client_ip: String,
    pub server_endpoint: String,
    pub server_public_key: String,
}

#[derive(Serialize)]
struct CreateRoomRequest<'a> {
    room_name: &'a str,
    password: Option<&'a str>,
    public_key: &'a str,
    max_users: i32,
}

#[derive(Serialize)]
struct JoinRoomRequest<'a> {
    room_name: &'a str,
    password: Option<&'a str>,
    public_key: &'a str,
}

#[derive(Serialize)]
struct LeaveRoomRequest<'a> {
    room_name: &'a str,
    public_key: &'a str,
}

pub async fn get_rooms() -> Result<Vec<Room>> {
    let resp = reqwest::get(format!("{}/rooms", BASE_URL))
        .await?
        .json::<Vec<Room>>()
        .await?;
    Ok(resp)
}

pub async fn get_peers(room_name: &str) -> Result<Vec<Peer>> {
    let resp = reqwest::get(format!("{}/room_peers/{}", BASE_URL, room_name))
        .await?
        .json::<Vec<Peer>>()
        .await?;
    Ok(resp)
}

pub async fn create_room(name: &str, password: Option<&str>, public_key: &str) -> Result<VpnConfig> {
    let client = reqwest::Client::new();
    let resp = client
        .post(format!("{}/create_room", BASE_URL))
        .json(&CreateRoomRequest {
            room_name: name,
            password,
            public_key,
            max_users: 20,
        })
        .send()
        .await?
        .json::<VpnConfig>()
        .await?;
    Ok(resp)
}

pub async fn join_room(name: &str, password: Option<&str>, public_key: &str) -> Result<VpnConfig> {
    let client = reqwest::Client::new();
    let resp = client
        .post(format!("{}/join_room", BASE_URL))
        .json(&JoinRoomRequest {
            room_name: name,
            password,
            public_key,
        })
        .send()
        .await?
        .json::<VpnConfig>()
        .await?;
    Ok(resp)
}

pub async fn leave_room(name: &str, public_key: &str) -> Result<()> {
    let client = reqwest::Client::new();
    client
        .post(format!("{}/leave_room", BASE_URL))
        .json(&LeaveRoomRequest {
            room_name: name,
            public_key,
        })
        .send()
        .await?;
    Ok(())
}
