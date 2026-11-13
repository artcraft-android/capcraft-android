// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer and Concat contributors
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! Sound: the engine's player, one per process, started on first use. It
//! keeps the clock the editor follows while playing: the app asks for the
//! position each frame and draws the picture for it.
//!
//! Whatever the open session makes audible is handed to the player after
//! every edit ([`sync`]), so play always sounds like the timeline.

use std::path::{Path, PathBuf};
use std::sync::Arc;

use concat_host::playback::{Playback, PlaybackEvents};
use concat_host::session::Session;

use crate::{MobileError, context, micros, seconds};

/// What the player reports that the app should hear about.
#[uniffi::export(with_foreign)]
pub trait PlaybackListener: Send + Sync {
    /// A failure the user should see (the sound device went away, a file
    /// would not decode).
    fn on_error(&self, message: String);
}

struct Events {
    listener: std::sync::Mutex<Option<Arc<dyn PlaybackListener>>>,
}

impl PlaybackEvents for Events {
    fn position(&self, _seconds: f64) {}

    fn error(&self, message: String) {
        log::warn!("playback: {message}");
        let listener = self
            .listener
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone();
        if let Some(listener) = listener {
            listener.on_error(message);
        }
    }
}

static EVENTS: std::sync::OnceLock<Arc<Events>> = std::sync::OnceLock::new();

fn events() -> Arc<Events> {
    Arc::clone(EVENTS.get_or_init(|| {
        Arc::new(Events {
            listener: std::sync::Mutex::new(None),
        })
    }))
}

/// Set once the sound device has failed to open: edits then stop trying,
/// and play says why.
static FAILED: std::sync::OnceLock<String> = std::sync::OnceLock::new();

/// The player, started if it was not. A device that will not open (or a
/// panic inside it) is an error, never a crash, and is not retried on every
/// edit.
fn player() -> Result<Arc<Playback>, MobileError> {
    let context = context()?;
    let mut slot = context
        .playback
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    if let Some(player) = slot.as_ref() {
        return Ok(Arc::clone(player));
    }
    if let Some(reason) = FAILED.get() {
        return Err(MobileError::Failed(reason.clone()));
    }
    let events: Arc<dyn PlaybackEvents> = events();
    let started =
        std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| Playback::start(events)));
    let player = match started {
        Ok(Ok(player)) => player,
        Ok(Err(message)) => {
            let reason = format!("Sound is not available: {message}");
            let _ = FAILED.set(reason.clone());
            return Err(MobileError::Failed(reason));
        }
        Err(_) => {
            let reason = "Sound is not available on this device.".to_owned();
            let _ = FAILED.set(reason.clone());
            return Err(MobileError::Failed(reason));
        }
    };
    *slot = Some(Arc::clone(&player));
    Ok(player)
}

/// The player when it has been started, without starting it.
fn running() -> Option<Arc<Playback>> {
    let context = context().ok()?;
    context
        .playback
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clone()
}

/// Hands the session's audible clips to the player, when it is running.
pub(crate) fn sync(session: &Session) {
    let player = match running() {
        Some(player) => player,
        None => match player() {
            Ok(player) => player,
            Err(_) => return,
        },
    };
    let specs = concat_host::playback::audible(&session.flattened_clips());
    let path = PathBuf::from(session.path());
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        player.set_clips(path, specs)
    }));
}

/// Tells `listener` about the player's failures from now on.
#[uniffi::export]
pub fn set_playback_listener(listener: Arc<dyn PlaybackListener>) {
    *events()
        .listener
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(listener);
}

/// Starts playing from `at_us`.
#[uniffi::export]
pub fn play(at_us: i64) -> Result<(), MobileError> {
    player()?.play(seconds(at_us).max(0.0));
    Ok(())
}

/// Stops playing where it is.
#[uniffi::export]
pub fn pause() {
    if let Some(player) = running() {
        player.pause();
    }
}

/// Moves the playhead to `at_us`, playing or not.
#[uniffi::export]
pub fn seek(at_us: i64) {
    if let Some(player) = running() {
        player.seek(seconds(at_us).max(0.0));
    }
}

/// Where the player is now, in microseconds: the clock to draw by.
#[uniffi::export]
pub fn position_us() -> i64 {
    running().map_or(0, |player| micros(player.position_now()))
}

/// Whether it is playing.
#[uniffi::export]
pub fn is_playing() -> bool {
    running().is_some_and(|player| player.is_playing())
}

/// Silences the player (the editor's mute), or lets it be heard again.
#[uniffi::export]
pub fn set_muted(muted: bool) {
    if let Some(player) = running() {
        player.set_muted(muted);
    }
}

/// Lets the player go quiet with nothing loaded: the editor closed.
#[uniffi::export]
pub fn clear_playback() {
    if let Some(player) = running() {
        player.pause();
        player.set_muted(false);
        player.set_clips(PathBuf::new(), Vec::new());
    }
}

/// Clears the player only when `project_path` still owns it, so a previous
/// editor's teardown cannot wipe a newly opened project's audio clips.
#[uniffi::export]
pub fn clear_playback_for(project_path: String) {
    if let Some(player) = running()
        && player.project_path().as_deref() == Some(Path::new(&project_path))
    {
        player.pause();
        player.set_muted(false);
        player.set_clips(PathBuf::new(), Vec::new());
    }
}

/// Whether background clip preparation (scrub copies or audio caches) is
/// running.
#[uniffi::export]
pub fn is_preparing_media() -> bool {
    concat_host::scrub::is_busy() || running().is_some_and(|player| player.is_preparing())
}
