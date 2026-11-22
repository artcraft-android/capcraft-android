// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! A clip's mask and its keyframes: the mask is a link of the clip's effect
//! chain, and its keys are the link's own. These read and change a link as
//! the panels hold it, as JSON, and touch no session.

use concat_project::model::{AppliedFilter, Clip, KeyEase};
use serde_json::Value;

use crate::MobileError;

/// The mask package.
pub(crate) const MASK: &str = "concat.mask";

/// The mask's parameters that can ride, with the package's defaults.
pub(crate) const KEYED: [(&str, f64); 7] = [
    ("x", 50.0),
    ("y", 50.0),
    ("width", 60.0),
    ("height", 60.0),
    ("rotation", 0.0),
    ("feather", 0.0),
    ("round", 0.0),
];

/// How near a key has to be to count as here, a frame, as a fraction of a
/// clip `duration` seconds long.
pub(crate) fn near(duration: f64) -> f64 {
    1.0 / 30.0 / duration.max(1e-6)
}

/// Where the keys of `clip`'s mask sit, as fractions of the clip.
pub(crate) fn key_times(clip: &Clip) -> impl Iterator<Item = f64> + '_ {
    clip.video_effects
        .iter()
        .filter(|link| link.id == MASK)
        .flat_map(|link| link.keys.values().flatten().map(|key| key.at))
}

fn parse(link: &str) -> Result<AppliedFilter, MobileError> {
    serde_json::from_str(link)
        .map_err(|error| MobileError::Failed(format!("not an effect: {error}")))
}

fn to_json(value: &impl serde::Serialize) -> Result<String, MobileError> {
    serde_json::to_string(value).map_err(|error| MobileError::Failed(error.to_string()))
}

/// The parameters of the mask `link` at `at`, a fraction of its clip, its
/// keys counted, as JSON.
#[uniffi::export]
pub fn mask_params_at(link: String, at: f64) -> Result<String, MobileError> {
    to_json(&parse(&link)?.params_at(at))
}

/// The mask `link` with `params` (a JSON object of numbers, the whole mask
/// as it should be at `at`) set on it. One with keys, or one set while
/// `on_key` (the playhead is on one of the clip's keyframes), takes what can
/// ride as keys at `at` - on the keys a frame or less away, where there are
/// some, keeping their curves - and the rest as it is. The clip is
/// `duration` seconds long.
#[uniffi::export]
pub fn mask_set_at(
    link: String,
    at: f64,
    duration: f64,
    on_key: bool,
    params: String,
) -> Result<String, MobileError> {
    let mut link = parse(&link)?;
    let wanted: std::collections::BTreeMap<String, Value> = serde_json::from_str(&params)
        .map_err(|error| MobileError::Failed(format!("not parameters: {error}")))?;
    let value_of = |key: &str| wanted.get(key).and_then(Value::as_f64);
    let at = at.clamp(0.0, 1.0);
    let rides = |key: &str| KEYED.iter().any(|(name, _)| *name == key);
    let keyed = KEYED.iter().any(|(name, _)| link.is_keyed(name));
    // a change of shape or an invert is no reason for a key
    let moved = KEYED.iter().any(|(name, default)| {
        value_of(name).is_some_and(|value| (value - link.value_at(name, at, *default)).abs() > 1e-9)
    });
    if (keyed || on_key) && moved {
        let here = KEYED
            .iter()
            .flat_map(|(name, _)| link.keys_on(name))
            .map(|key| key.at)
            .filter(|held| (held - at).abs() <= near(duration))
            .min_by(|a, b| (a - at).abs().total_cmp(&(b - at).abs()))
            .unwrap_or(at);
        for (name, default) in KEYED {
            let value = value_of(name).unwrap_or_else(|| link.value_at(name, at, default));
            let ease = link
                .key_at(name, here)
                .map_or(KeyEase::LINEAR, |index| link.keys_on(name)[index].ease);
            link.set_key(name, here, value, ease);
        }
    }
    for key in wanted.keys() {
        // a keyed value's constant stays what it falls back to
        if let Some(value) = value_of(key)
            && !(rides(key) && link.is_keyed(key))
        {
            link.params.insert(key.clone(), value);
        }
    }
    to_json(&link)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn params(link: &str, at: f64) -> Value {
        serde_json::from_str(&mask_params_at(link.to_owned(), at).expect("read")).expect("parses")
    }

    fn set(link: &str, at: f64, on_key: bool, params: &str) -> String {
        mask_set_at(link.to_owned(), at, 10.0, on_key, params.to_owned()).expect("set")
    }

    const PLAIN: &str = r#"{"id":"concat.mask","params":{"x":50,"y":50,"width":50,"height":50,"rotation":0,"feather":0,"round":0,"shape":2}}"#;

    #[test]
    fn a_mask_without_keys_is_set_as_it_is() {
        let link = set(PLAIN, 0.5, false, r#"{"x":20,"shape":3}"#);
        assert!(!link.contains("\"keys\""), "{link}");
        assert_eq!(params(&link, 0.0)["x"], 20.0);
        assert_eq!(params(&link, 0.0)["shape"], 3.0);
    }

    #[test]
    fn a_mask_set_on_two_keyframes_rides_between_them() {
        let link = set(PLAIN, 0.0, true, r#"{"x":20}"#);
        let link = set(&link, 1.0, true, r#"{"x":80}"#);
        assert_eq!(params(&link, 0.0)["x"], 20.0);
        assert_eq!(params(&link, 1.0)["x"], 80.0);
        let middle = params(&link, 0.5)["x"].as_f64().expect("a number");
        assert!((middle - 50.0).abs() < 1e-6, "{middle}");
    }

    #[test]
    fn a_change_a_frame_from_a_key_moves_that_key() {
        let link = set(PLAIN, 0.5, true, r#"{"x":20}"#);
        let link = set(&link, 0.5 + 0.003, false, r#"{"x":30}"#);
        let held: AppliedFilter = serde_json::from_str(&link).expect("parses");
        assert_eq!(held.keys_on("x").len(), 1);
        assert_eq!(held.keys_on("x")[0].value, 30.0);
        assert_eq!(held.keys_on("x")[0].at, 0.5);
    }

    #[test]
    fn a_new_shape_adds_no_key() {
        let link = set(PLAIN, 0.0, true, r#"{"x":20}"#);
        let now = mask_params_at(link.clone(), 0.5).expect("read");
        let mut now: Value = serde_json::from_str(&now).expect("parses");
        now["shape"] = 4.into();
        let link = set(&link, 0.5, false, &now.to_string());
        let held: AppliedFilter = serde_json::from_str(&link).expect("parses");
        assert_eq!(held.keys_on("x").len(), 1);
        assert_eq!(held.params["shape"], 4.0);
    }
}
