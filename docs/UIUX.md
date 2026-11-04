# OffconAI UI/UX Direction

This pass intentionally excludes Work mode. The app keeps one primary chat flow, with model management and voice/API tools as secondary destinations.

## Product patterns reviewed

- ChatGPT: persistent sidebar, one dominant composer, attachments integrated into that composer, and a separate canvas/result area.
- Claude: explicit hierarchy between recent conversations, Projects, and Artifacts; generated results should not be mixed into navigation lists.
- Gemini: direct destinations for new chat, search, library, and model tools; the 2026 Neural Expressive direction simplifies the composer while allowing more character in typography and status.
- Perplexity: source/status information belongs close to the answer. OffconAI does not fabricate sources; this pattern is represented through local-runtime and encryption status only.

## What OffconAI uses

1. A theme-aware canvas, with the dark slate treatment as the primary visual direction and a light palette available.
2. One primary message composer with attachment, microphone, and voice actions.
3. A short local-first status statement and three clearly differentiated start actions.
4. A drawer hierarchy with recent conversations separated from tools.
5. Native voice and STT pipeline voice remain visibly distinct.
6. Model management remains a dedicated destination instead of being folded into chat navigation.
7. Light and dark palettes share semantic tokens; the dark theme uses elevated slate surfaces rather than outlines and translucent glass.

## Deliberately excluded

- ChatGPT-style Work/Canvas tab split.
- Claude-style artifact library until OffconAI has persistent generated artifacts to manage.
- Gemini-style feature hub until there are enough first-class modules to justify it.
- Perplexity-style citation cards for local model answers that have no web sources.
- PirateFace BitTorrent integration; manual HTTPS/direct mirror URLs remain available.
