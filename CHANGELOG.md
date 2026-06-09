# Changelog

## 2.2.0

This release scopes the mod back down to **only chunk pre-generation**. Several unrelated
quality-of-life features that had been prototyped inside this jar (a mod-profile switcher, a custom
menu-background/music engine, menu/loading-screen theming, transparent widget sprites, and an
Xaero minimap throttle) have been **moved out into their own standalone mods** so Chunky
Pregenerator does one thing and does it well.

### Added
- **Master switch that also hides the HUD.** "Enable Auto-generation" is now **Enable Chunky
  Pregenerator** — a true master toggle. When off, no generation runs **and** the on-screen HUD
  widget is hidden entirely (the mod goes fully dormant). It still won't cancel an already-running
  job — use Stop for that.
- **Auto-retrigger on Movement** toggle (on by default). When on, a new bundle fires automatically
  once you move past the deadzone — evaluated only every *Position Check Interval* (your poll rate),
  not every tick. When off, generation only runs on world join or a manual `/chunkypregen trigger`;
  moving never re-triggers.
- **Boss-bar suppression** (`MixinBossBarHud`): when Progress Relay is on, Chunky's server-sent
  boss-bar progress is hidden so it doesn't duplicate the relay summary.

### Fixed
- **Thread Count slider unlock.** The manual Thread Count slider no longer shows struck-through and
  permanently locked. It now uses a dynamic enabled-provider that unlocks the moment **Automatic
  Thread Count** is turned off (previously the enabled state was evaluated once at menu-build time,
  so toggling Automatic didn't free the slider; the `§m` strikethrough was also hard-coded).

### Changed
- **Defaults now ship the tuned pack values** (baked into the code, so a fresh config matches the
  intended setup):
  - Generation radius `250 → 100` chunks (deadzone `2000 → 800` blocks)
  - End pre-generation `off → on` (still gated behind an actual visit to The End)
  - Automatic Thread Count `on → off`, with a manual Thread Count of `6`
  - HUD scale `1.0 → 0.7`
  - Spiral ring step `25 → 10` (more, finer rings)
- Tooltips for the master switch and thread settings reworded to match the new behaviour.

### Removed (split into separate mods)
- Mod-profile switcher → **nonprofit-profiles**
- Custom backgrounds + menu music + menu/loading-screen theming + transparent widget sprites →
  **nonprofit-menu**
- Xaero's Minimap framebuffer throttle → **nonprofit-xaero-throttle**

The build no longer pulls in LWJGL (TinyFD/STB) or ASM — none of that is needed for pre-generation.

## 2.1.0
- HUD progress widget, `lodRefreshSeconds` setting, Chunky thread defaults, progress-relay
  passthrough fixes, settings reorganization, persistence fixes.
