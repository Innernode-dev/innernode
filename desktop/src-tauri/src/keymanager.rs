use anyhow::Result;
use base64::{engine::general_purpose::STANDARD, Engine};
use std::fs;
use x25519_dalek::{PublicKey, StaticSecret};

fn key_path() -> std::path::PathBuf {
    let mut p = dirs::config_dir().unwrap_or_else(|| std::path::PathBuf::from("."));
    p.push("InnerNode");
    fs::create_dir_all(&p).ok();
    p.push("keypair.json");
    p
}

/// Возвращает (private_key_b64, public_key_b64)
/// Ключи совместимы с WireGuard (x25519, base64)
pub fn get_or_create_keypair() -> Result<(String, String)> {
    let path = key_path();

    // Пробуем загрузить существующий keypair
    if path.exists() {
        let data = fs::read_to_string(&path)?;
        let v: serde_json::Value = serde_json::from_str(&data)?;
        let priv_b64 = v["private"].as_str().unwrap_or("").to_string();
        let pub_b64 = v["public"].as_str().unwrap_or("").to_string();

        // Валидируем — оба должны быть 32 байта в base64
        if let (Ok(priv_bytes), Ok(pub_bytes)) =
            (STANDARD.decode(&priv_b64), STANDARD.decode(&pub_b64))
        {
            if priv_bytes.len() == 32 && pub_bytes.len() == 32 {
                return Ok((priv_b64, pub_b64));
            }
        }

        // Старый keypair битый или от старой версии — перегенерируем
    }

    generate_and_save_keypair(&path)
}

fn generate_and_save_keypair(path: &std::path::Path) -> Result<(String, String)> {
    // Используем OsRng — криптографически безопасный генератор ОС
    let secret = StaticSecret::random_from_rng(rand::rngs::OsRng);
    let public = PublicKey::from(&secret);

    let priv_b64 = STANDARD.encode(secret.to_bytes());
    let pub_b64 = STANDARD.encode(public.to_bytes());

    let json = serde_json::json!({
        "private": priv_b64,
        "public":  pub_b64,
    });

    // Сохраняем с безопасными правами (только для текущего пользователя)
    fs::write(path, json.to_string())?;

    // На Windows выставляем права только для владельца
    #[cfg(windows)]
    restrict_file_permissions(path);

    Ok((priv_b64, pub_b64))
}

/// Убираем права других пользователей на файл с ключами
#[cfg(windows)]
fn restrict_file_permissions(path: &std::path::Path) {
    // icacls: убираем права Everyone, оставляем только текущего юзера
    std::process::Command::new("icacls")
        .args([
            path.to_str().unwrap_or(""),
            "/inheritance:r",
            "/grant:r",
            &format!("{}:(F)", get_current_user()),
        ])
        .output()
        .ok();
}

#[cfg(windows)]
fn get_current_user() -> String {
    std::env::var("USERNAME").unwrap_or_else(|_| "Users".to_string())
}