# Aerith

Aerith is an Android media gallery for the Blossom (Blob Storage on Nostr) protocol. It allows you to view, upload, and delete media across multiple Blossom servers.

## How it works

- **Multi-Server Sync**: The app pulls your Blossom server list (Kind 10063) from Nostr relays and fetches file lists from each server.
- **Authentication**: All server requests are authenticated via NIP-55 (external signers like Amber). It attempts authorization with both `Nostr` and `Blossom` header prefixes for maximum compatibility.
- **Deduplication**: Files are indexed by their SHA-256 hash. Identical files stored on multiple servers are shown as a single entry in the gallery.
- **Caching**: 
    - Media is cached locally using the file hash as the key. This prevents redundant downloads even when switching between different server views.
    - Videos viewed in fullscreen are automatically cached to disk using Media3 SimpleCache.
    - User settings (pubkey, relays, server lists) and file metadata are persisted locally to speed up app launch.
- **Performance**: Thumbnails are downsampled and decoded using 16-bit color (RGB_565) to maintain smooth scrolling in the grid view.

## Features

- **Authenticated Refresh**: Default behavior is to request signatures on launch to fetch authorized/private lists.
- **Video Support**: Includes native video thumbnails and an integrated player with standard controls.
- **File Info**: View metadata for any item, including its SHA-256 hash and a list of all servers currently hosting it.
- **Upload/Delete**: Supports the 2-step Blossom upload flow and authenticated deletion.

## Implementation Details

- **Language**: Kotlin
- **Framework**: Jetpack Compose
- **Media Loading**: Coil (with video-frame-decoder extension)
- **Video Player**: Media3 ExoPlayer
- **Networking**: OkHttp & Moshi
- **Signer Support**: NIP-55 (Android Intents)
