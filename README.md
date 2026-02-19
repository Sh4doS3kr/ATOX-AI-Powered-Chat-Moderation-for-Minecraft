# ATOX - AI-Powered Chat Moderation for Minecraft

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-1.17.1--1.21.8-brightgreen?style=for-the-badge&logo=minecraft" alt="Minecraft">
  <img src="https://img.shields.io/badge/Paper-API-blue?style=for-the-badge" alt="Paper">
  <img src="https://img.shields.io/badge/Gemini-AI-orange?style=for-the-badge&logo=google" alt="Gemini">
  <img src="https://img.shields.io/badge/License-MIT-yellow?style=for-the-badge" alt="License">
  <img src="https://img.shields.io/badge/Version-1.0.0-red?style=for-the-badge" alt="Version">
</p>

**ATOX** is a Paper plugin that uses **Google Gemini AI** to automatically moderate your server's chat. Every configurable interval (default: 15 minutes), it analyzes accumulated player messages and applies sanctions through **AdvancedBan** - no manual review needed.

---

## ✨ Features

- **🤖 Gemini AI analysis** — sends full chat context to Gemini and lets it decide if a sanction is warranted
- **⚖️ Smart sanctioning** — WARN, MUTE (temp), KICK, BAN (temp/permanent), IPBAN based on severity
- **🔄 Accumulation on API failure** — if the API is unavailable (503, timeout), messages are retained and sent again next cycle with new messages included
- **🛡️ One sanction per player per cycle** — deduplicates Gemini's output, always applying the most severe action
- **📣 Discord webhook reports** — detailed embed with player, action, reason, trigger message, and duration
- **🔒 Privacy notice on join** — players are informed their chat is AI-moderated
- **🌐 Multi-version support** — compatible with Paper 1.17.1 through 1.21.8+
- **🔨 AdvancedBan integration** — executes `warn`, `tempmute`, `kick`, `tempban`, `ban`, `ipban`, `tempipban`
- **🚫 Conservative threshold** — normal expressions (lol, aaaa, !!!) are never sanctioned

---

## 📋 Requirements

| Requirement | Version |
|---|---|
| Java | 16+ |
| Paper | 1.17.1 – 1.21.8+ |
| [AdvancedBan](https://www.spigotmc.org/resources/advancedban.8695/) | 2.3.0+ |
| Google Gemini API Key | Free tier works |

---

## 🚀 Installation

1. Download the latest `ATOX-x.x.x.jar` from [Releases](../../releases)
2. Place it in your server's `plugins/` folder
3. Restart the server — a `config.yml` will be generated in `plugins/AntiToxicity/`
4. Edit `config.yml` and add your Gemini API key and Discord webhook URL
5. Run `/atox reload` or restart the server

---

## ⚙️ Configuration

```yaml
# Server type — shown in Discord webhook reports
# Examples: SURVIVAL, SKYBLOCK, ANARCHY, PARKOUR, LOBBY, FACTIONS, etc.
server-type: "SURVIVAL"

# Server name (used to identify it in webhook reports)
server-name: "MyServer"

# Gemini API Configuration
gemini:
  api-key: "YOUR_GEMINI_API_KEY_HERE"
  model: "gemini-2.5-flash-lite"

# Discord Webhook URL for notifications (leave empty to disable)
discord-webhook: ""

# Analysis interval in minutes
analysis-interval-minutes: 15

# Maximum age of stored messages (in hours) before they are purged
message-max-age-hours: 24

# Default durations for AdvancedBan temporary sanctions
durations:
  mute: "1h"
  ban: "1d"
```

### Getting a Gemini API Key

1. Go to [Google AI Studio](https://aistudio.google.com/)
2. Click **Get API key** → **Create API key**
3. Copy the key and paste it in `config.yml`

> The free tier is sufficient for most servers.

---

## 🎮 Commands

| Command | Description | Permission |
|---|---|---|
| `/atox status` | Show stored message count and plugin state | `antitoxicity.admin` |
| `/atox analyze` | Force an immediate analysis cycle | `antitoxicity.admin` |
| `/atox reload` | Reload configuration without restarting | `antitoxicity.admin` |

### Permissions

| Permission | Description | Default |
|---|---|---|
| `antitoxicity.admin` | Access to all ATOX commands | OP |
| `antitoxicity.bypass` | Exempt a player from AI analysis | `false` |

---

## 🔨 Sanction System

ATOX uses AdvancedBan commands under the hood. The AI determines the action and duration:

| Action | Command | When |
|---|---|---|
| WARN | `warn <player> <reason>` | Mild offensive language |
| MUTE | `tempmute <player> <duration> <reason>` | Repeated insults, harassment |
| KICK | `kick <player> <reason>` | Severe harassment |
| BAN (temp) | `tempban <player> <duration> <reason>` | Threats, discrimination |
| BAN (permanent) | `ban <player> <reason>` | Extreme toxicity |
| IPBAN | `ipban <player> <reason>` | Doxxing, serious threats |

All sanction reasons are automatically prefixed with `[ATOX AI]`.

---

## 📊 Discord Webhook

ATOX sends a detailed embed to your Discord channel after each analysis cycle:

- ✅ **No sanctions** — green embed confirming clean chat
- ⚠️ **Sanctions applied** — red embed listing each player, action, reason, trigger message, and duration
- Server name, type, message count, and timestamp in the footer

---

## 🔄 API Failure Handling

If the Gemini API returns an error (503, timeout, rate limit, etc.):

1. Messages are **not consumed** — the analysis timestamp does not advance
2. New messages continue to accumulate
3. On the next cycle, **all accumulated messages** are sent together
4. This repeats until the API responds successfully

---

## 🏗️ Building from Source

```bash
git clone https://github.com/Sh4doS3kr/ATOX-AI-Powered-Chat-Moderation-for-Minecraft.git
cd ATOX-AI-Powered-Chat-Moderation-for-Minecraft
mvn clean package
```

The compiled JAR will be at `target/ATOX-1.0.0.jar`.

**Requirements:** Java 16+, Maven 3.6+

---

## 🤝 Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) before submitting a pull request.

---

## 📄 License

This project is licensed under the **MIT License** — see [LICENSE](LICENSE) for details.

---

## 🙏 Acknowledgements

- [Google Gemini](https://deepmind.google/technologies/gemini/) — AI analysis engine
- [PaperMC](https://papermc.io/) — Minecraft server platform
- [AdvancedBan](https://www.spigotmc.org/resources/advancedban.8695/) — punishment system integration

---

<p align="center">Made with ❤️ by <a href="https://github.com/Sh4doS3kr">Sh4doS3kr</a></p>
