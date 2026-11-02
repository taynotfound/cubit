---
version: "0.1.2"
level: auto
processes:
  design: copilot
  implementation: auto
  testing: auto
  documentation: auto
  review: none
  deployment: auto
---

This format is based on [AI-DECLARATION.md](https://ai-declaration.md/en/0.1.2).

## Notes

- Cubit was built with Claude (Anthropic), working as a coding agent in Claude Code.
- **The human side:** the game idea, every feature request and design decision (modes, Clean mode, look and feel, sound, difficulty), play-testing on a real phone, deciding what ships, and maintaining the project.
- **The AI side:** most of the code (the Android app, the Go leaderboard server, the level generator), the tests, the synthesized music and swoosh scripts, the website and docs, release builds, and server deployment. Gameplay was also checked on an Android emulator.
- **Design** is `copilot`: the AI proposed the architecture and visual system, and the human directed and approved it.
- **Review** is `none`: there is no separate code-review process.
- **Contributors:** if you use AI for a contribution, please say so in the pull request.
