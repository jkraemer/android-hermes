# Changelog

User-visible changes, in the spirit of [Keep a Changelog](https://keepachangelog.com).

## [Unreleased]

### Changed

- The scroll-to-end button scrolls quickly to the end instead of jumping there.

## [0.1.1] - 2026-11-06

### Fixed

- With the keyboard open, the composer sits right on top of it and the top
  bar stays in place, instead of floating halfway up the screen.
- Closing the keyboard folds an empty composer back into its one-row pill.

## [0.1.0] - 2026-11-06

### Added

- Sign in to a Hermes dashboard (password login) at an address of your choice:
  plain HTTP over Tailscale or HTTPS behind a proxy.
- Opens into a new chat, or into the chat you used in the last 30 minutes.
- Chats stream their replies, render Markdown, collapse tool calls into one
  line, and show approval and clarify cards that you answer in place. An
  approval counts down to the server's own timeout.
- The connection comes back by itself after the phone sleeps or loses the
  network, and the reply continues where it was.
- A drawer with pinned chats, the ten most recent ones and "View all".
- The full list of chats and cron runs by day, with unread dots, rename, pin
  and archive on long-press.
- Search across all messages, showing the text around the match.
- A cron run's chat shows its job prompt collapsed to one line.
- Dark mode follows the system.
