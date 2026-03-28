#![cfg_attr(windows, windows_subsystem = "windows")]

mod api;
mod keymanager;
mod wireguard;

use std::sync::{Arc, Mutex};
use tauri::{
    AppHandle, Manager, State,
    tray::{TrayIconBuilder, TrayIconEvent, MouseButton, MouseButtonState},
    menu::{Menu, MenuItem},
};
use serde::Serialize;

#[derive(Default)]
struct AppState {
    pub_key:   String,
    priv_key:  String,
    room_name: Option<String>,
    my_ip:     Option<String>,
    tunnel:    Option<wireguard::TunnelHandle>,
}

type SharedState = Arc<Mutex<AppState>>;

#[derive(Serialize, Clone)]
pub struct RoomDto {
    name:       String,
    users:      i32,
    max:        i32,
    is_private: bool,
}

#[derive(Serialize, Clone)]
pub struct PeerDto {
    ip:   String,
    role: String,
}

#[tauri::command]
async fn get_keys(state: State<'_, SharedState>) -> Result<serde_json::Value, String> {
    let st = state.lock().unwrap();
    Ok(serde_json::json!({ "pub_key": st.pub_key, "priv_key": st.priv_key }))
}

#[tauri::command]
async fn cmd_get_rooms() -> Result<Vec<RoomDto>, String> {
    let rooms = api::get_rooms().await.map_err(|e| e.to_string())?;
    Ok(rooms.into_iter().map(|r| RoomDto {
        name: r.name, users: r.users, max: r.max, is_private: r.is_private,
    }).collect())
}

#[tauri::command]
async fn cmd_get_peers(room_name: String) -> Result<Vec<PeerDto>, String> {
    let peers = api::get_peers(&room_name).await.map_err(|e| e.to_string())?;
    Ok(peers.into_iter().map(|p| PeerDto { ip: p.ip, role: p.role }).collect())
}

#[tauri::command]
async fn cmd_create_room(
    name: String,
    password: Option<String>,
    state: State<'_, SharedState>,
    app: AppHandle,
) -> Result<serde_json::Value, String> {
    let (pub_key, priv_key) = {
        let st = state.lock().unwrap();
        (st.pub_key.clone(), st.priv_key.clone())
    };
    let cfg = api::create_room(&name, password.as_deref(), &pub_key)
        .await.map_err(|e| format!("Ошибка создания: {}", e))?;
    let handle = wireguard::connect(
        &cfg.client_ip, &cfg.server_endpoint, &cfg.server_public_key, &priv_key,
    ).map_err(|e| format!("Ошибка туннеля: {}", e))?;
    {
        let mut st = state.lock().unwrap();
        st.room_name = Some(name.clone());
        st.my_ip     = Some(cfg.client_ip.clone());
        st.tunnel    = Some(handle);
    }
    save_last_room(&app, &name, password.as_deref().unwrap_or(""));
    Ok(serde_json::json!({ "client_ip": cfg.client_ip, "room_name": name }))
}

#[tauri::command]
async fn cmd_join_room(
    name: String,
    password: Option<String>,
    state: State<'_, SharedState>,
    app: AppHandle,
) -> Result<serde_json::Value, String> {
    let (pub_key, priv_key) = {
        let st = state.lock().unwrap();
        (st.pub_key.clone(), st.priv_key.clone())
    };
    let cfg = api::join_room(&name, password.as_deref(), &pub_key)
        .await.map_err(|e| format!("Ошибка входа: {}", e))?;
    let handle = wireguard::connect(
        &cfg.client_ip, &cfg.server_endpoint, &cfg.server_public_key, &priv_key,
    ).map_err(|e| format!("Ошибка туннеля: {}", e))?;
    {
        let mut st = state.lock().unwrap();
        st.room_name = Some(name.clone());
        st.my_ip     = Some(cfg.client_ip.clone());
        st.tunnel    = Some(handle);
    }
    save_last_room(&app, &name, password.as_deref().unwrap_or(""));
    Ok(serde_json::json!({ "client_ip": cfg.client_ip, "room_name": name }))
}

#[tauri::command]
async fn cmd_leave_room(state: State<'_, SharedState>) -> Result<(), String> {
    let (room, pub_key) = {
        let mut st = state.lock().unwrap();
        let room = st.room_name.take();
        st.my_ip  = None;
        st.tunnel = None;
        (room, st.pub_key.clone())
    };
    if let Some(room) = room {
        api::leave_room(&room, &pub_key).await.ok();
    }
    Ok(())
}

#[tauri::command]
async fn cmd_disconnect(state: State<'_, SharedState>) -> Result<(), String> {
    let (room, pub_key) = {
        let mut st = state.lock().unwrap();
        let room = st.room_name.take();
        st.my_ip  = None;
        st.tunnel = None;
        (room, st.pub_key.clone())
    };
    if let Some(room) = room {
        api::leave_room(&room, &pub_key).await.ok();
    }
    Ok(())
}

#[tauri::command]
fn cmd_get_last_room(app: AppHandle) -> serde_json::Value {
    let path = last_room_path(&app);
    if let Ok(data) = std::fs::read_to_string(&path) {
        if let Ok(v) = serde_json::from_str::<serde_json::Value>(&data) {
            return v;
        }
    }
    serde_json::json!({ "name": "", "password": "" })
}

fn last_room_path(app: &AppHandle) -> std::path::PathBuf {
    let mut p = app.path().app_data_dir().unwrap_or_default();
    std::fs::create_dir_all(&p).ok();
    p.push("last_room.json");
    p
}

fn save_last_room(app: &AppHandle, name: &str, password: &str) {
    let path = last_room_path(app);
    let data = serde_json::json!({ "name": name, "password": password });
    std::fs::write(path, data.to_string()).ok();
}

fn main() {
    std::panic::set_hook(Box::new(|info| {
        eprintln!("[InnerNode PANIC] {}", info);
    }));

    let (priv_key, pub_key) = keymanager::get_or_create_keypair()
        .expect("Не удалось создать ключи");

    let state: SharedState = Arc::new(Mutex::new(AppState {
        pub_key:  pub_key.clone(),
        priv_key: priv_key.clone(),
        ..Default::default()
    }));

    tauri::Builder::default()
        .manage(state.clone())
        .plugin(tauri_plugin_shell::init())
        .setup(move |app| {
            let show = MenuItem::with_id(app, "show", "Открыть InnerNode", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "Выйти",             true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&show, &quit])?;

            let _tray = TrayIconBuilder::new()
                .icon(app.default_window_icon().unwrap().clone())
                .tooltip("InnerNode VPN")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up, ..
                    } = event {
                        let app = tray.app_handle();
                        if let Some(win) = app.get_webview_window("main") {
                            win.show().ok();
                            win.set_focus().ok();
                        }
                    }
                })
                .on_menu_event(move |app, event| {
                    match event.id.as_ref() {
                        "show" => {
                            if let Some(win) = app.get_webview_window("main") {
                                win.show().ok();
                                win.set_focus().ok();
                            }
                        }
                        "quit" => {
                            let st    = state.clone();
                            let app_h = app.clone();
                            tauri::async_runtime::spawn(async move {
                                let (room, pub_key) = {
                                    let mut s = st.lock().unwrap();
                                    let room  = s.room_name.take();
                                    s.tunnel  = None;
                                    (room, s.pub_key.clone())
                                };
                                if let Some(room) = room {
                                    api::leave_room(&room, &pub_key).await.ok();
                                }
                                app_h.exit(0);
                            });
                        }
                        _ => {}
                    }
                })
                .build(app)?;

            let win = app.get_webview_window("main").unwrap();
            let win_clone = win.clone();
            win.on_window_event(move |event| {
              if let tauri::WindowEvent::CloseRequested { api, .. } = event {
            api.prevent_close();
            win_clone.hide().ok();
            }
        });

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            get_keys,
            cmd_get_rooms,
            cmd_get_peers,
            cmd_create_room,
            cmd_join_room,
            cmd_leave_room,
            cmd_disconnect,
            cmd_get_last_room,
        ])
        .run(tauri::generate_context!())
        .expect("Ошибка запуска Tauri");
}