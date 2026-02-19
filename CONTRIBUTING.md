# Contributing to ATOX

Thank you for considering contributing to ATOX! Here's how to get started.

## How to Contribute

### Reporting Bugs

1. Check [existing issues](../../issues) to avoid duplicates
2. Open a new issue with:
   - A clear title and description
   - Steps to reproduce
   - Expected vs actual behavior
   - Server version, Paper version, ATOX version
   - Relevant console logs (use a paste service like [mclo.gs](https://mclo.gs))

### Suggesting Features

Open an issue with the `enhancement` label. Describe:
- What the feature does
- Why it would be useful
- Any implementation ideas you have

### Submitting Pull Requests

1. Fork the repository
2. Create a branch: `git checkout -b feature/your-feature-name`
3. Make your changes following the code style below
4. Test your changes on a local Paper server
5. Commit with a clear message: `git commit -m "Add: brief description"`
6. Push and open a Pull Request against `main`

## Code Style

- Follow standard Java conventions
- Keep methods focused and small
- All user-facing strings in English (for the public EN version)
- No hardcoded API keys or webhook URLs — always use `config.yml`
- Thread safety: use async tasks for API calls, main thread for Bukkit commands

## Building

```bash
mvn clean package
```

Output: `target/ATOX-1.0.0.jar`

**Requirements:** Java 16+, Maven 3.6+

## Project Structure

```
src/main/java/com/antitoxicity/
├── AntiToxicity.java      # Main plugin class, message storage, commands
├── GeminiAnalyzer.java    # Gemini API integration and prompt building
├── ChatListener.java      # Chat capture (legacy + modern Paper API)
├── AnalysisTask.java      # Scheduled analysis BukkitRunnable
└── DiscordWebhook.java    # Discord embed report sender

src/main/resources/
├── plugin.yml             # Plugin metadata
└── config.yml             # Default configuration
```

## Questions?

Open a [Discussion](../../discussions) or an issue with the `question` label.
