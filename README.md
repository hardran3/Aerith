# Aerith

Aerith is a high-performance, decentralized Android media gallery for the Blossom (Blob Storage on Nostr) protocol. It allows you to view, manage, and synchronize your media library across multiple Blossom servers with a focus on speed, privacy, and local-first reliability.

## Key Concepts

- **Multi-Server Synchronization**: Automatically pulls your Blossom server list (Kind 10063) from Nostr relays and aggregates file lists from all sources into a unified gallery.
- **Local Blossom Cache**: Integrates seamlessly with local Blossom servers (like Morganite) on port 24242. Media found on remote servers is automatically mirrored to your local device for instant, offline access and zero-bandwidth browsing.
- **Smart Sourcing**: Automatically prioritizes the local cache for all media loading. If a file is available on your device, Aerith bypasses the internet entirely.
- **Privacy-First Authentication**: All operations are authenticated via NIP-55 (external signers like Amber). Aerith uses NIP-98 headers with intelligent fallback between `Nostr` and `Blossom` prefixes for maximum server compatibility.
- **Robust Deduplication**: Media is indexed by SHA-256 hash. Identical files across different servers are merged into a single gallery entry, providing a clean and organized view of your entire library.

## Features

- **Dynamic Tagging & Filtering**: 
    - Powerful tag-based navigation with live file counts.
    - Advanced filtering by media type (Images/Videos) and specific file extensions.
    - Intelligent tag ordering: selected filters move to the front in the order they were chosen.
- **Bulk Operations**: 
    - Parallelized multi-select for efficient management.
    - Bulk label updates (Nostr Kind 1063).
    - Bulk mirroring to all available servers.
    - Safe bulk deletion with automatic local Trash backup.
- **Local Trash System**: Orphaned or deleted files are "pinned" to persistent local storage, ensuring your thumbnails and media remain accessible even after removal from the cloud.
- **Rich Media Support**: 
    - Native video thumbnails and integrated ExoPlayer.
    - Support for Animated GIFs and WebP.
    - High-contrast info badges on thumbnails showing file types and server distribution.
- **Optimized Performance**: 
    - 400px RGB_565 thumbnail downsampling for smooth 60fps scrolling.
    - Background metadata-only refreshes to prevent redundant network fetches.
    - Automatic proxy-fetching for fast local synchronization.

## Settings & Customization

- **Display Preferences**: Toggle thumbnail badges (Server Count, File Type) to suit your preferred information density.
- **Unified Management**: Manage Nostr identity, Relay lists, and Blossom server configurations in one place.
- **Local Server Support**: Automatic detection of local Blossom caches on both real devices (`127.0.0.1`) and emulators (`10.0.2.2`).

## Implementation Details

- **Language**: Kotlin
- **Framework**: Jetpack Compose
- **Media Loading**: Coil (with video, GIF, and animated WebP support)
- **Video Player**: Media3 ExoPlayer
- **Networking**: OkHttp, HttpUrl, and Moshi
- **Signer Support**: NIP-55 (Android Intents via ContentResolver)
