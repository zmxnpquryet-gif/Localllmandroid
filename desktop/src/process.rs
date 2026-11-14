//! Subprocess lifecycle, executable discovery, log tailing, and resource telemetry.

use crate::contracts::ResourceSnapshot;
use std::fs::{self, File, OpenOptions};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Mutex;
use std::thread;
use std::time::{Duration, Instant};
use sysinfo::{Pid, ProcessesToUpdate, System};

pub struct ProcessSlot {
    child: Mutex<Option<Child>>,
    started_at: Mutex<Option<Instant>>,
    log_file: Mutex<Option<PathBuf>>,
    loaded_model: Mutex<Option<String>>,
    pub active_requests: AtomicU32,
    stats: Mutex<Option<System>>,
}

impl Default for ProcessSlot {
    fn default() -> Self {
        Self::new()
    }
}

impl ProcessSlot {
    pub fn new() -> Self {
        Self {
            child: Mutex::new(None),
            started_at: Mutex::new(None),
            log_file: Mutex::new(None),
            loaded_model: Mutex::new(None),
            active_requests: AtomicU32::new(0),
            stats: Mutex::new(System::new().ok()),
        }
    }

    pub fn store_child(&self, child: Child, log: PathBuf) {
        *self.child.lock().expect("process lock") = Some(child);
        *self.started_at.lock().expect("started lock") = Some(Instant::now());
        *self.log_file.lock().expect("log lock") = Some(log);
    }

    pub fn mark_external_connected(&self, model: &str) {
        *self.started_at.lock().expect("started lock") = Some(Instant::now());
        self.set_loaded(model);
    }

    pub fn clear(&self) {
        let child = self.child.lock().expect("process lock").take();
        stop_child(child);
        *self.started_at.lock().expect("started lock") = None;
        *self.loaded_model.lock().expect("model lock") = None;
    }

    pub fn alive(&self) -> bool {
        let mut guard = self.child.lock().expect("process lock");
        match guard.as_mut() {
            Some(child) => matches!(child.try_wait(), Ok(None)),
            None => false,
        }
    }

    pub fn pid(&self) -> Option<u32> {
        let mut guard = self.child.lock().expect("process lock");
        match guard.as_mut() {
            Some(child) => {
                if matches!(child.try_wait(), Ok(None)) {
                    Some(child.id())
                } else {
                    None
                }
            }
            None => None,
        }
    }

    pub fn uptime(&self) -> Duration {
        self.started_at
            .lock()
            .expect("started lock")
            .map(|started| started.elapsed())
            .unwrap_or_default()
    }

    pub fn log_path(&self) -> Option<PathBuf> {
        self.log_file.lock().expect("log lock").clone()
    }

    pub fn set_loaded(&self, model: &str) {
        *self.loaded_model.lock().expect("model lock") = Some(model.to_string());
    }

    pub fn loaded(&self) -> Option<String> {
        self.loaded_model.lock().expect("model lock").clone()
    }

    fn metrics(&self, pid: u32) -> (Option<f32>, Option<u64>) {
        let mut guard = self.stats.lock().expect("stats lock");
        let Some(system) = guard.as_mut() else {
            return (None, None);
        };
        let pid = Pid::from_u32(pid);
        system.refresh_processes(ProcessesToUpdate::Some(&[pid]), true);
        match system.process(pid) {
            Some(process) => (Some(process.cpu_usage()), Some(process.memory())),
            None => (None, None),
        }
    }

    pub fn snapshot(&self, detail: String) -> ResourceSnapshot {
        let pid = self.pid();
        let (cpu_percent, resident_bytes) =
            pid.map(|pid| self.metrics(pid)).unwrap_or((None, None));
        ResourceSnapshot {
            process_id: pid,
            cpu_percent,
            resident_bytes,
            active_requests: self.active_requests.load(Ordering::Relaxed),
            uptime: self.uptime(),
            detail,
        }
    }
}

fn stop_child(child: Option<Child>) {
    let Some(mut child) = child else { return };
    if matches!(child.try_wait(), Ok(Some(_))) {
        return;
    }
    let _ = child.kill();
    let _ = child.wait();
}

pub fn log_path(name: &str) -> PathBuf {
    PathBuf::from("build").join("desktop").join(name)
}

fn open_log(path: &Path) -> Result<File, String> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .map_err(|error| format!("could not create log directory: {error}"))?;
    }
    OpenOptions::new()
        .create(true)
        .append(true)
        .open(path)
        .map_err(|error| format!("could not open {}: {error}", path.display()))
}

pub fn spawn_with_log(mut command: Command, path: &Path) -> Result<Child, String> {
    let log = open_log(path)?;
    let log_err = log
        .try_clone()
        .map_err(|error| format!("could not clone the log handle: {error}"))?;
    command.stdout(Stdio::from(log)).stderr(Stdio::from(log_err));
    command
        .spawn()
        .map_err(|error| format!("could not launch process (log {}): {error}", path.display()))
}

pub fn log_tail(path: Option<&Path>, lines: usize) -> String {
    let Some(path) = path else {
        return String::new();
    };
    let Ok(text) = fs::read_to_string(path) else {
        return String::new();
    };
    let all: Vec<&str> = text.lines().collect();
    if all.is_empty() {
        return String::new();
    }
    let from = all.len().saturating_sub(lines);
    format!("Log tail: {}", all[from..].join(" | "))
}

pub fn wait_for(timeout: Duration, mut probe: impl FnMut() -> bool) -> bool {
    let deadline = Instant::now() + timeout;
    while Instant::now() < deadline {
        if probe() {
            return true;
        }
        thread::sleep(Duration::from_millis(400));
    }
    false
}

pub fn default_threads() -> usize {
    std::thread::available_parallelism()
        .map(|count| count.get().saturating_sub(1).max(1))
        .unwrap_or(1)
}

pub fn resolve_executable(executable: &str) -> Option<PathBuf> {
    let executable = executable.trim();
    if executable.is_empty() {
        return None;
    }
    let direct = PathBuf::from(executable);
    if direct.is_file() {
        return Some(direct);
    }
    let path = std::env::var_os("PATH")?;
    let extensions: Vec<String> = if cfg!(windows) {
        std::env::var("PATHEXT")
            .unwrap_or_else(|_| ".EXE;.BAT;.CMD".into())
            .split(';')
            .map(|extension| extension.trim().to_lowercase())
            .collect()
    } else {
        vec![String::new()]
    };
    for directory in std::env::split_paths(&path) {
        let candidate = directory.join(executable);
        if candidate.is_file() {
            return Some(candidate);
        }
        let has_extension = Path::new(executable)
            .extension()
            .is_some_and(|extension| !extension.is_empty());
        if !has_extension {
            for extension in &extensions {
                if extension.is_empty() {
                    continue;
                }
                let candidate = directory.join(format!("{executable}{extension}"));
                if candidate.is_file() {
                    return Some(candidate);
                }
            }
        }
    }
    None
}

pub fn parse_command(input: &str) -> Vec<String> {
    let mut result = Vec::new();
    let mut current = String::new();
    let mut quote: Option<char> = None;
    for character in input.chars() {
        match quote {
            Some(active) if character == active => quote = None,
            Some(_) => current.push(character),
            None if character == '"' || character == '\'' => quote = Some(character),
            None if character.is_whitespace() => {
                if !current.is_empty() {
                    result.push(std::mem::take(&mut current));
                }
            }
            None => current.push(character),
        }
    }
    if !current.is_empty() {
        result.push(current);
    }
    result
}

pub fn parse_port(value: &str, fallback: u16) -> u16 {
    value
        .trim()
        .parse::<u16>()
        .ok()
        .filter(|port| *port > 0)
        .unwrap_or(fallback)
}

pub fn parse_u32(value: &str, fallback: u32) -> u32 {
    value.trim().parse::<u32>().unwrap_or(fallback)
}

pub fn parse_u32_clamped(value: &str, fallback: u32, min: u32, max: u32) -> u32 {
    value
        .trim()
        .parse::<u32>()
        .map(|v| v.clamp(min, max))
        .unwrap_or(fallback)
}

pub fn parse_f64_clamped(value: &str, fallback: f64, min: f64, max: f64) -> f64 {
    value
        .trim()
        .parse::<f64>()
        .ok()
        .filter(|v| v.is_finite())
        .map(|v| v.clamp(min, max))
        .unwrap_or(fallback)
}

pub fn format_resources(snapshot: &ResourceSnapshot) -> String {
    let uptime = format_duration(snapshot.uptime);
    match snapshot.process_id {
        Some(pid) => {
            let cpu = snapshot
                .cpu_percent
                .map(|value| format!("{value:.1}%"))
                .unwrap_or_else(|| "-".into());
            let memory = snapshot
                .resident_bytes
                .map(format_bytes)
                .unwrap_or_else(|| "-".into());
            format!(
                "pid {pid} \u{00B7} cpu {cpu} \u{00B7} mem {memory} \u{00B7} req {} \u{00B7} up {uptime}",
                snapshot.active_requests
            )
        }
        None => format!(
            "external \u{00B7} req {} \u{00B7} up {uptime}",
            snapshot.active_requests
        ),
    }
}

pub fn format_duration(duration: Duration) -> String {
    let seconds = duration.as_secs();
    format!(
        "{:02}:{:02}:{:02}",
        seconds / 3600,
        (seconds % 3600) / 60,
        seconds % 60
    )
}

pub fn format_bytes(bytes: u64) -> String {
    const MIB: f64 = 1024.0 * 1024.0;
    const GIB: f64 = MIB * 1024.0;
    let value = bytes as f64;
    if value >= GIB {
        format!("{:.1} GiB", value / GIB)
    } else if value >= MIB {
        format!("{:.0} MiB", value / MIB)
    } else {
        format!("{bytes} B")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn command_parsing_keeps_quoted_arguments() {
        assert_eq!(
            parse_command("strata serve --model \"C:\\models\\a b.gguf\" --x 'q'"),
            vec![
                "strata",
                "serve",
                "--model",
                "C:\\models\\a b.gguf",
                "--x",
                "q"
            ]
        );
        assert!(parse_command("   ").is_empty());
    }

    #[test]
    fn numeric_parsers_clamp_and_fallback_safely() {
        assert_eq!(parse_port("9090", 8080), 9090);
        assert_eq!(parse_port("0", 8080), 8080);
        assert_eq!(parse_port("nope", 8080), 8080);
        assert_eq!(parse_u32("-2", 0), 0);
        assert_eq!(parse_u32_clamped("128", 4096, 512, 32768), 512);
        assert_eq!(parse_f64_clamped("9.0", 0.7, 0.0, 2.0), 2.0);
        assert_eq!(parse_f64_clamped("NaN", 0.7, 0.0, 2.0), 0.7);
        assert!(default_threads() >= 1);
    }

    #[test]
    fn resource_readout_handles_external_and_owned_processes() {
        let snapshot = ResourceSnapshot {
            active_requests: 2,
            uptime: Duration::from_secs(65),
            ..ResourceSnapshot::default()
        };
        assert_eq!(
            format_resources(&snapshot),
            "external \u{00B7} req 2 \u{00B7} up 00:01:05"
        );

        let owned = ResourceSnapshot {
            process_id: Some(42),
            cpu_percent: Some(3.24),
            resident_bytes: Some(512 * 1024 * 1024),
            ..snapshot
        };
        assert_eq!(
            format_resources(&owned),
            "pid 42 \u{00B7} cpu 3.2% \u{00B7} mem 512 MiB \u{00B7} req 2 \u{00B7} up 00:01:05"
        );
    }

    #[test]
    fn byte_formatting_stays_readable() {
        assert_eq!(format_bytes(512), "512 B");
        assert_eq!(format_bytes(2 * 1024 * 1024), "2 MiB");
        assert_eq!(format_bytes(3 * 1024 * 1024 * 1024), "3.0 GiB");
    }
}
