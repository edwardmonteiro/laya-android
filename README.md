# Laya · Duelo no Espaço

An Asteroids-style dogfight for Android where the enemy fighter is flown by **Laya**, the open-source
Jev-style "System One" decision model, running **entirely on the phone**.

Several times a second the game writes a short battle report ("the player is aiming right at you, two
bullets incoming, your hull 1 of 3…") and asks Laya one typed question: *which maneuver next?*
(attack, flank, evade, retreat, take cover). Laya answers with calibrated probabilities in one forward
pass, generating no text, and the enemy samples its tactic from them. A reflex layer flies the chosen
tactic every frame: steering, lead aiming, trigger and dodging rocks. The HUD shows Laya's live
probabilities and latency. Without the model you can practise against a rule-based pilot.

## Install on the phone

1. Open **Releases → latest** on this repo from the phone and download `Laya.apk`.
2. Allow installing from your browser when Android asks, then open **Laya**.
3. Tap **Baixar modelo** (652 MB, Wi-Fi only by default). The system download manager handles it,
   so you can leave the app and the download resumes if the connection drops.
4. After the SHA-256 check, pick a difficulty and tap **Jogar contra o Laya**. Drag on the left half to
   fly and hold the right half to fire. First to 5 kills wins.

## The model

| | |
|---|---|
| Checkpoint | `convaiinnovations/laya-multilingual` (mmBERT-base, 100+ languages) |
| Phone build | int8 weight-only ONNX from [`ti3x-m/laya-multilingual-onnx`](https://huggingface.co/ti3x-m/laya-multilingual-onnx), 100% argmax agreement with PyTorch on 34 reference cases |
| Files | `model.onnx` + `model.onnx_data` (615 MB), `tokenizer.json` (34 MB), `laya_config.json`; all pinned by SHA-256 |
| Licence | Apache-2.0 |
| Runtime | ONNX Runtime Android (CPU), Hugging Face tokenizers via DJL |

## How it is verified

CI runs three jobs on every push:

- **Model parity (JVM):** a Java port of `laya.common.build_sequence` is checked against 60 randomized
  golden cases from the Python reference (see `laya-core/src/test/resources`). Then the real tokenizer and
  int8 model run on the publisher's reference cases, and CI compares `input_ids`, markers and probabilities.
- **APK:** builds and publishes `Laya.apk` to the `latest` release.
- **On-device:** an Android emulator runs the full path: the in-app download from Hugging Face, the
  checksum check, model loading, a decision compared with a reference case, and 40 simulated seconds of a
  match with Laya flying the enemy.

Job logs are pushed to `ci-log-*` branches.

The signing key in `app/` is a personal sideload key, committed so each build installs as an update.
