# CleanTV Deployment & Management Tools

Utility scripts for zero-downtime ADB deployment, TV debloating, and system recovery.

---

## 🛠 Scripts

| Script | Purpose |
|---|---|
| `install-mitv4a.sh` | One-shot automated installer for Xiaomi Mi TV 4A: installs CleanTV, grants permissions, enables `HomeKeyService`, and safely disables ad/telemetry bloatware. |
| `debloat-mitv4a.sh` | Dedicated debloat tool for Xiaomi Mi TV 4A: disables PatchWall, telemetry, ad containers, voice assistant, and background daemons. |
| `restore-mitv4a.sh` | Safely re-enables all disabled system packages and PatchWall if you wish to restore the factory TV setup. |
| `tv.py` | Python wrapper around ADB shell execution with automatic retry and batch support. Used by build scripts. |
| `b64push.py` | Fast Base64 streaming file uploader over ADB, avoiding broken ADB push implementations on certain older Android TV builds. |

---

## 🚀 Usage

### Install on Mi TV 4A
```bash
./tools/install-mitv4a.sh 100.97.35.51
```

### Restore Stock Launcher & Services
```bash
./tools/restore-mitv4a.sh 100.97.35.51
```
