use anyhow::{Context, Result};
use std::fs;
use std::path::PathBuf;
use std::process::Command;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

// ── Вшиваем wireguard.exe и wintun.dll прямо в бинарник ─────────────────────
static WG_EXE_BYTES: &[u8]    = include_bytes!("../../assets/wireguard.exe");
static WINTUN_DLL_BYTES: &[u8] = include_bytes!("../../assets/wintun.dll");

const TUNNEL_NAME: &str = "innernode";

// Имя правила фаервола — по нему же удаляем при disconnect
const FW_RULE_NAME: &str = "InnerNode VPN";

// Подсеть, которую разрешаем (весь пул WireGuard)
const VPN_SUBNET: &str = "10.66.66.0/24";

pub struct TunnelHandle {
    running: Arc<AtomicBool>,
}

impl Drop for TunnelHandle {
    fn drop(&mut self) {
        if self.running.load(Ordering::SeqCst) {
            self.running.store(false, Ordering::SeqCst);
            let _ = stop_tunnel();
            remove_firewall_rules();
        }
    }
}

fn work_dir() -> PathBuf {
    let mut p = std::env::temp_dir();
    p.push("innernode_wg");
    p
}

fn wg_exe_path() -> PathBuf { work_dir().join("wireguard.exe") }
fn wintun_path() -> PathBuf { work_dir().join("wintun.dll") }

fn conf_path() -> PathBuf {
    work_dir().join(format!("{}.conf", TUNNEL_NAME))
}

fn service_name() -> String {
    format!("WireGuardTunnel${}", TUNNEL_NAME)
}

fn extract_binaries() -> Result<()> {
    let dir = work_dir();
    fs::create_dir_all(&dir).context("Не удалось создать temp директорию")?;
    extract_if_changed(&wg_exe_path(), WG_EXE_BYTES)?;
    extract_if_changed(&wintun_path(), WINTUN_DLL_BYTES)?;
    Ok(())
}

fn extract_if_changed(path: &PathBuf, data: &[u8]) -> Result<()> {
    let need_write = if path.exists() {
        fs::metadata(path).map(|m| m.len()).unwrap_or(0) != data.len() as u64
    } else {
        true
    };
    if need_write {
        fs::write(path, data)?;
    }
    Ok(())
}

// ── Firewall ─────────────────────────────────────────────────────────────────

/// Применяем все нужные правила фаервола и переключаем профиль на Private.
/// Вызывается ПОСЛЕ того как туннель поднялся.
fn apply_firewall_rules() {
    // 1. Переключаем сетевой профиль интерфейса на Private —
    //    это снимает основную блокировку входящих соединений.
    //    Ошибки игнорируем: если не сработает — сработают правила ниже.
    Command::new("powershell")
        .args([
            "-NonInteractive",
            "-Command",
            &format!(
                "Set-NetConnectionProfile -InterfaceAlias '{}' -NetworkCategory Private",
                TUNNEL_NAME
            ),
        ])
        .output()
        .ok();

    // Сначала удаляем старые правила с тем же именем, чтобы не дублировать
    remove_firewall_rules();

    // 2. Разрешаем ВЕСЬ входящий трафик из VPN-подсети (любой протокол, любой порт)
    netsh_add_rule("in",  "any", "any");

    // 3. Разрешаем ВЕСЬ исходящий трафик в VPN-подсеть
    netsh_add_rule("out", "any", "any");

    // 4. Отдельно разрешаем ICMPv4 (ping) — некоторые версии Windows
    //    блокируют его даже при наличии общего правила
    Command::new("netsh")
        .args([
            "advfirewall", "firewall", "add", "rule",
            &format!("name={} ICMP", FW_RULE_NAME),
            "dir=in",
            "action=allow",
            "protocol=icmpv4",
            &format!("remoteip={}", VPN_SUBNET),
            "profile=any",
            "enable=yes",
        ])
        .output()
        .ok();

    // 5. Включаем встроенное правило "File and Printer Sharing (Echo Request)"
    //    — это разблокирует ping глобально для Private-профиля
    Command::new("netsh")
        .args([
            "advfirewall", "firewall", "set", "rule",
            "name=File and Printer Sharing (Echo Request - ICMPv4-In)",
            "new", "enable=yes",
        ])
        .output()
        .ok();
}

fn netsh_add_rule(dir: &str, protocol: &str, localport: &str) {
    let mut args = vec![
        "advfirewall".to_string(),
        "firewall".to_string(),
        "add".to_string(),
        "rule".to_string(),
        format!("name={}", FW_RULE_NAME),
        format!("dir={}", dir),
        "action=allow".to_string(),
        format!("protocol={}", protocol),
        format!("remoteip={}", VPN_SUBNET),
        "profile=any".to_string(),
        "enable=yes".to_string(),
    ];
    if protocol != "any" && localport != "any" {
        args.push(format!("localport={}", localport));
    }
    Command::new("netsh").args(&args).output().ok();
}

/// Удаляем все правила фаервола InnerNode (вызывается при disconnect и пересоздании)
fn remove_firewall_rules() {
    // Удаляем по имени — netsh удалит ВСЕ правила с таким именем (in + out)
    Command::new("netsh")
        .args([
            "advfirewall", "firewall", "delete", "rule",
            &format!("name={}", FW_RULE_NAME),
        ])
        .output()
        .ok();

    // Удаляем ICMP-правило
    Command::new("netsh")
        .args([
            "advfirewall", "firewall", "delete", "rule",
            &format!("name={} ICMP", FW_RULE_NAME),
        ])
        .output()
        .ok();
}

// ── Connect / Disconnect ─────────────────────────────────────────────────────

pub fn connect(
    client_ip: &str,
    server_endpoint: &str,
    server_pubkey: &str,
    private_key: &str,
) -> Result<TunnelHandle> {
    extract_binaries()?;

    let config = format!(
        "[Interface]\n\
         PrivateKey = {private_key}\n\
         Address = {client_ip}/32\n\
         \n\
         [Peer]\n\
         PublicKey = {server_pubkey}\n\
         Endpoint = {server_endpoint}\n\
         AllowedIPs = 10.66.66.0/24\n\
         PersistentKeepalive = 25\n"
    );

    fs::write(conf_path(), &config)
        .context("Не удалось записать конфиг WireGuard")?;

    // Убиваем предыдущий туннель
    let _ = stop_tunnel();
    std::thread::sleep(std::time::Duration::from_millis(800));

    let conf = conf_path();
    let conf_str = conf.to_str()
        .context("Путь к конфигу содержит невалидные символы")?;

    let output = Command::new(wg_exe_path())
        .args(["/installtunnelservice", conf_str])
        .output()
        .context("Не удалось запустить wireguard.exe")?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        let stdout = String::from_utf8_lossy(&output.stdout);
        return Err(anyhow::anyhow!(
            "wireguard.exe /installtunnelservice завершился с ошибкой.\n\
             stdout: {stdout}\nstderr: {stderr}\n\
             Запущен от администратора?"
        ));
    }

    // Ждём старта туннеля
    wait_for_running(30, std::time::Duration::from_millis(500))
        .context("Туннель не запустился за 15 секунд")?;

    // ── ПРИМЕНЯЕМ ФАЕРВОЛ ────────────────────────────────────────────────────
    // Небольшая пауза — даём Windows зарегистрировать интерфейс
    std::thread::sleep(std::time::Duration::from_millis(1000));
    apply_firewall_rules();
    // ────────────────────────────────────────────────────────────────────────

    Ok(TunnelHandle {
        running: Arc::new(AtomicBool::new(true)),
    })
}

pub fn disconnect() -> Result<()> {
    stop_tunnel()?;
    remove_firewall_rules();
    let _ = fs::remove_file(conf_path());
    Ok(())
}

fn stop_tunnel() -> Result<()> {
    if wg_exe_path().exists() {
        Command::new(wg_exe_path())
            .args(["/uninstalltunnelservice", TUNNEL_NAME])
            .output()
            .ok();
        std::thread::sleep(std::time::Duration::from_millis(600));
    }

    let svc = service_name();
    Command::new("sc").args(["stop",   &svc]).output().ok();
    std::thread::sleep(std::time::Duration::from_millis(400));
    Command::new("sc").args(["delete", &svc]).output().ok();

    Ok(())
}

fn wait_for_running(attempts: u32, interval: std::time::Duration) -> Result<()> {
    let svc = service_name();
    for attempt in 0..attempts {
        std::thread::sleep(interval);

        if let Ok(out) = Command::new("sc").args(["query", &svc]).output() {
            let s = String::from_utf8_lossy(&out.stdout);

            if s.contains("RUNNING") {
                return Ok(());
            }

            if s.contains("STOPPED") && !s.contains("PENDING") && attempt > 3 {
                return Err(anyhow::anyhow!(
                    "Сервис {} неожиданно остановлен. sc query:\n{}", svc, s
                ));
            }
        }
    }

    Err(anyhow::anyhow!(
        "Сервис {} не перешёл в RUNNING за {} попыток ({} сек)",
        svc,
        attempts,
        attempts as f32 * interval.as_secs_f32()
    ))
}

pub fn get_tunnel_status() -> TunnelStatus {
    let svc = service_name();
    match Command::new("sc").args(["query", &svc]).output() {
        Ok(o) => {
            let s = String::from_utf8_lossy(&o.stdout);
            if      s.contains("RUNNING") { TunnelStatus::Running }
            else if s.contains("STOPPED") { TunnelStatus::Stopped }
            else                          { TunnelStatus::Unknown  }
        }
        Err(_) => TunnelStatus::Unknown,
    }
}

#[derive(Debug, PartialEq)]
pub enum TunnelStatus {
    Running,
    Stopped,
    Unknown,
}