// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

//! Windows Service Control Manager (SCM) integration.
//!
//! When the daemon is installed as a Windows service (see
//! `core/dist/install-service.ps1`) the SCM launches the binary with the
//! `--system-daemon` flag and expects it to (a) connect back to the SCM, (b)
//! register a control handler, (c) report `Running`, and (d) react to
//! `Stop`/`Shutdown` by winding down and reporting `Stopped`. Without this the
//! service can be started but `sc.exe stop` (and clean machine shutdown) never
//! completes — Windows force-kills it after a timeout.
//!
//! This module is only compiled on Windows; on unix the service managers
//! (launchd/systemd) signal the process directly.

use std::ffi::OsString;
use std::sync::Arc;
use std::time::Duration;

use anyhow::Result;
use clap::Parser;
use tokio::sync::Notify;
use windows_service::{
    define_windows_service,
    service::{
        ServiceControl, ServiceControlAccept, ServiceExitCode, ServiceState, ServiceStatus,
        ServiceType,
    },
    service_control_handler::{self, ServiceControlHandlerResult},
    service_dispatcher,
};

use crate::Args;

/// Must match the service name used by `install-service.ps1` (`sc.exe create`).
const SERVICE_NAME: &str = "ReelVaultCore";
const SERVICE_TYPE: ServiceType = ServiceType::OWN_PROCESS;

/// Win32 `ERROR_FAILED_SERVICE_CONTROLLER_CONNECT` — returned by the dispatcher
/// when the process was not started by the SCM (i.e. it's a console launch).
const ERROR_FAILED_SERVICE_CONTROLLER_CONNECT: i32 = 1063;

define_windows_service!(ffi_service_main, service_main);

/// Try to run under the SCM. Returns `Ok(true)` if we were started by the SCM
/// (and have now stopped), `Ok(false)` if we were *not* (the caller should run
/// in the foreground instead), or `Err(_)` on a genuine dispatcher failure.
pub fn try_run_as_service() -> Result<bool> {
    match service_dispatcher::start(SERVICE_NAME, ffi_service_main) {
        Ok(()) => Ok(true),
        Err(windows_service::Error::Winapi(e))
            if e.raw_os_error() == Some(ERROR_FAILED_SERVICE_CONTROLLER_CONNECT) =>
        {
            Ok(false)
        }
        Err(e) => Err(anyhow::anyhow!("Windows service dispatcher failed: {e}")),
    }
}

/// SCM entry point (invoked on a dispatcher thread). Any error here can't reach
/// a logger yet, so it's reported best-effort to stderr.
fn service_main(_arguments: Vec<OsString>) {
    if let Err(e) = run_service() {
        eprintln!("ReelVault service error: {e}");
    }
}

fn run_service() -> Result<()> {
    // A Stop/Shutdown control fires this notification; the daemon's serve loop
    // awaits it. `notify_one` stores a permit, so a Stop that arrives before the
    // daemon starts awaiting still triggers a clean shutdown (no lost signal).
    let shutdown = Arc::new(Notify::new());
    let handler_shutdown = Arc::clone(&shutdown);

    let event_handler = move |control: ServiceControl| -> ServiceControlHandlerResult {
        match control {
            ServiceControl::Stop | ServiceControl::Shutdown => {
                handler_shutdown.notify_one();
                ServiceControlHandlerResult::NoError
            }
            ServiceControl::Interrogate => ServiceControlHandlerResult::NoError,
            _ => ServiceControlHandlerResult::NotImplemented,
        }
    };

    let status_handle = service_control_handler::register(SERVICE_NAME, event_handler)?;

    let set_state = |state: ServiceState, accept: ServiceControlAccept, exit: u32| {
        status_handle.set_service_status(ServiceStatus {
            service_type: SERVICE_TYPE,
            current_state: state,
            controls_accepted: accept,
            exit_code: ServiceExitCode::Win32(exit),
            checkpoint: 0,
            wait_hint: Duration::default(),
            process_id: None,
        })
    };

    set_state(
        ServiceState::Running,
        ServiceControlAccept::STOP | ServiceControlAccept::SHUTDOWN,
        0,
    )?;

    // Re-parse the command line the SCM launched us with (the `binPath` args)
    // and run the daemon until a Stop/Shutdown control fires.
    let args = Args::parse();
    let wait_for_stop = async move { shutdown.notified().await };
    let result = crate::run_in_runtime(args, wait_for_stop);

    set_state(
        ServiceState::Stopped,
        ServiceControlAccept::empty(),
        if result.is_ok() { 0 } else { 1 },
    )?;

    result
}
