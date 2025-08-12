# RomM Android Client

A modern Android app for browsing and downloading ROMs from your RomM server instance.

## Features

- 📱 Modern Material Design 3 UI
- 🎮 Browse platforms and collections
- 🎨 **Cover art display** for games in detail view
- 📋 View detailed game information (regions, revisions, languages, tags)
- ⬇️ Download individual games or bulk download entire platforms/collections
- 🔧 Download firmware files for platforms
- 📦 Automatic ZIP extraction for multi-disc games
- 🔄 Smart "download missing games" option
- 📊 **Foreground download service** with persistent notifications
- 📈 **Real-time loading progress** for large game collections
- ⚙️ Configurable concurrent downloads
- 🗂️ Organized file structure ([DOWNLOAD_DIR]/[PLATFORM_SLUG])
- 🎯 **Game controller navigation** support

## Setup

1. Open Android Studio
2. Open this project directory
3. Let Gradle sync
4. Build and run on your Android device (API 24+)

## Configuration

On first launch, configure your RomM server settings:
- **Host**: Your RomM server IP address
- **Port**: Usually 8080
- **Username/Password**: Your RomM credentials  
- **Download Directory**: Where to save ROMs
- **Max Concurrent Downloads**: 1-10 simultaneous downloads

## Usage

1. Configure your RomM server connection in Settings
2. Browse platforms or collections with **game controller** or touch navigation
3. Select a platform/collection to view games (with **real-time loading progress** for large collections)
4. View **game details with cover art** and download individual games
5. Use the menu for bulk download options:
   - Download All Games
   - Download Missing Games (skips already downloaded)
   - Download Firmware (for platforms that have it)
6. Downloads run in **foreground service** with persistent notifications showing progress

## File Organization

Games are automatically organized in your download directory:
```
[DOWNLOAD_DIR]/
├── SNES/
│   ├── Game1.sfc
│   └── Game2.sfc
├── PSX/
│   ├── Multi-Disc Game/
│   │   ├── disc1.bin
│   │   ├── disc2.bin
│   │   └── game.m3u
└── firmware/
    └── bios.bin
```

## Requirements

- Android 7.0+ (API 24)
- RomM server instance
- Internet connection to RomM server

## Technologies

- Kotlin + Jetpack Compose
- Material Design 3
- Retrofit for API communication
- Hilt for dependency injection
- **Foreground services** for reliable downloads
- Coil for cover art image loading
- DataStore for settings persistence

## Permissions

- **INTERNET**: Connect to RomM server
- **WRITE_EXTERNAL_STORAGE**: Save downloaded files
- **POST_NOTIFICATIONS**: Show download progress
- **FOREGROUND_SERVICE**: Background downloads

Built with ❤️ for the retro gaming community.
