# RomM Android Sync Integration Examples

This document shows how external applications and scripts can integrate with RomM Android's sync functionality.

## Android App Integration

### Kotlin/Java Example

```kotlin
// In your Android app
class MyEmulatorApp {
    companion object {
        const val REQUEST_SYNC = 1001
    }
    
    fun syncSaves() {
        val intent = Intent().apply {
            setClassName("com.romm.android", "com.romm.android.sync.SyncActivity")
            putExtra("sync_direction", "bidirectional") // or "upload", "download"
            putExtra("sync_save_files", true)
            putExtra("sync_save_states", true)
            putExtra("platform_filter", "snes") // optional - sync only SNES saves
            putExtra("emulator_filter", "snes9x") // optional - sync only snes9x saves
            putExtra("dry_run", false) // set to true for preview only
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        startActivityForResult(intent, REQUEST_SYNC)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_SYNC) {
            if (resultCode == Activity.RESULT_OK) {
                val uploadedCount = data?.getIntExtra("uploaded_count", 0) ?: 0
                val downloadedCount = data?.getIntExtra("downloaded_count", 0) ?: 0
                val skippedCount = data?.getIntExtra("skipped_count", 0) ?: 0
                val duration = data?.getLongExtra("duration", 0) ?: 0
                val errors = data?.getStringArrayListExtra("errors") ?: emptyList()
                
                Log.d("SyncResult", "Sync completed: +$uploadedCount ↓$downloadedCount ⏸$skippedCount in ${duration}ms")
                if (errors.isNotEmpty()) {
                    Log.w("SyncResult", "Sync errors: ${errors.joinToString()}")
                }
            } else {
                Log.e("SyncResult", "Sync failed or was cancelled")
            }
        }
    }
}
```

### React Native Example

```javascript
import { NativeModules } from 'react-native';

const syncWithRomM = async () => {
  try {
    const result = await NativeModules.IntentLauncher.startActivity({
      action: 'com.romm.android.SYNC',
      package: 'com.romm.android',
      className: 'com.romm.android.sync.SyncActivity',
      extra: {
        sync_direction: 'bidirectional',
        sync_save_files: true,
        sync_save_states: true,
        platform_filter: 'psx', // PlayStation saves only
      }
    });
    
    console.log('Sync result:', result);
  } catch (error) {
    console.error('Sync failed:', error);
  }
};
```

### Flutter Example

```dart
import 'package:flutter/services.dart';

class RomMSyncHelper {
  static const platform = MethodChannel('romm_sync');
  
  static Future<Map<String, dynamic>?> syncSaves({
    String direction = 'bidirectional',
    bool saveFiles = true,
    bool saveStates = true,
    String? platformFilter,
    String? emulatorFilter,
    bool dryRun = false,
  }) async {
    try {
      final result = await platform.invokeMethod('startSync', {
        'package': 'com.romm.android',
        'class': 'com.romm.android.sync.SyncActivity',
        'extras': {
          'sync_direction': direction,
          'sync_save_files': saveFiles,
          'sync_save_states': saveStates,
          if (platformFilter != null) 'platform_filter': platformFilter,
          if (emulatorFilter != null) 'emulator_filter': emulatorFilter,
          'dry_run': dryRun,
        },
      });
      
      return result;
    } catch (e) {
      print('Sync error: $e');
      return null;
    }
  }
}

// Usage
final result = await RomMSyncHelper.syncSaves(
  direction: 'upload',
  platformFilter: 'n64',
  emulatorFilter: 'mupen64plus',
);

if (result != null) {
  print('Uploaded: ${result['uploaded_count']}');
  print('Downloaded: ${result['downloaded_count']}');
}
```

## Shell Script Integration (via ADB)

### Bash Script Example

```bash
#!/bin/bash

# sync_romm.sh - Sync saves with RomM server via ADB

PACKAGE="com.romm.android"
ACTIVITY="com.romm.android.sync.SyncActivity"

sync_saves() {
    local direction="${1:-bidirectional}"  # upload, download, or bidirectional
    local platform_filter="$2"             # optional platform filter
    local emulator_filter="$3"              # optional emulator filter
    local dry_run="${4:-false}"            # true for preview only
    
    echo "Starting RomM sync (direction: $direction)..."
    
    # Build ADB intent command
    local intent_cmd="am start-activity"
    intent_cmd="$intent_cmd --es sync_direction '$direction'"
    intent_cmd="$intent_cmd --ez sync_save_files true"
    intent_cmd="$intent_cmd --ez sync_save_states true"
    intent_cmd="$intent_cmd --ez dry_run '$dry_run'"
    
    if [ -n "$platform_filter" ]; then
        intent_cmd="$intent_cmd --es platform_filter '$platform_filter'"
    fi
    
    if [ -n "$emulator_filter" ]; then
        intent_cmd="$intent_cmd --es emulator_filter '$emulator_filter'"
    fi
    
    intent_cmd="$intent_cmd $PACKAGE/$ACTIVITY"
    
    # Execute via ADB
    adb shell "$intent_cmd"
    
    if [ $? -eq 0 ]; then
        echo "Sync started successfully"
        
        # Wait for sync to complete (optional)
        wait_for_sync_completion
    else
        echo "Failed to start sync"
        return 1
    fi
}

wait_for_sync_completion() {
    echo "Waiting for sync to complete..."
    
    # Poll for sync completion (check if activity is still running)
    while adb shell "dumpsys activity activities | grep -q '$ACTIVITY'"; do
        sleep 2
        echo -n "."
    done
    
    echo ""
    echo "Sync completed"
}

# Usage examples
case "${1:-help}" in
    "upload")
        sync_saves "upload" "$2" "$3"
        ;;
    "download") 
        sync_saves "download" "$2" "$3"
        ;;
    "bidirectional"|"sync")
        sync_saves "bidirectional" "$2" "$3"
        ;;
    "preview")
        sync_saves "bidirectional" "$2" "$3" "true"
        ;;
    "help"|*)
        echo "Usage: $0 <command> [platform] [emulator]"
        echo ""
        echo "Commands:"
        echo "  upload         - Upload local saves to RomM server"  
        echo "  download       - Download saves from RomM server"
        echo "  bidirectional  - Two-way sync (default)"
        echo "  sync           - Alias for bidirectional"
        echo "  preview        - Show sync plan without executing"
        echo ""
        echo "Examples:"
        echo "  $0 upload snes snes9x      # Upload SNES saves from snes9x"
        echo "  $0 download                # Download all saves"  
        echo "  $0 sync psx                # Two-way sync for PlayStation saves"
        echo "  $0 preview                 # Preview sync plan"
        ;;
esac
```

### Python Script Example

```python
#!/usr/bin/env python3
"""
romm_sync.py - Python script to sync saves with RomM via ADB
"""

import subprocess
import sys
import time
import json
from typing import Optional, Dict, Any

class RomMSyncClient:
    def __init__(self, package: str = "com.romm.android"):
        self.package = package
        self.activity = f"{package}.sync.SyncActivity"
        
    def sync(self, 
             direction: str = "bidirectional",
             platform_filter: Optional[str] = None,
             emulator_filter: Optional[str] = None,
             save_files: bool = True,
             save_states: bool = True,
             dry_run: bool = False) -> bool:
        """
        Start a sync operation
        
        Args:
            direction: "upload", "download", or "bidirectional"
            platform_filter: Optional platform name filter
            emulator_filter: Optional emulator name filter
            save_files: Whether to sync save files
            save_states: Whether to sync save states  
            dry_run: If true, only show preview without executing
        """
        
        cmd = [
            "adb", "shell", "am", "start-activity",
            "--es", "sync_direction", direction,
            "--ez", "sync_save_files", str(save_files).lower(),
            "--ez", "sync_save_states", str(save_states).lower(),
            "--ez", "dry_run", str(dry_run).lower()
        ]
        
        if platform_filter:
            cmd.extend(["--es", "platform_filter", platform_filter])
            
        if emulator_filter:
            cmd.extend(["--es", "emulator_filter", emulator_filter])
            
        cmd.append(f"{self.package}/{self.activity}")
        
        print(f"Starting {'preview' if dry_run else 'sync'} ({direction})...")
        
        try:
            result = subprocess.run(cmd, capture_output=True, text=True, check=True)
            print("Sync started successfully")
            
            if not dry_run:
                self.wait_for_completion()
                
            return True
            
        except subprocess.CalledProcessError as e:
            print(f"Failed to start sync: {e}")
            print(f"Error output: {e.stderr}")
            return False
    
    def wait_for_completion(self, timeout: int = 300):
        """Wait for sync to complete"""
        print("Waiting for sync to complete...")
        
        start_time = time.time()
        while time.time() - start_time < timeout:
            # Check if activity is still running
            try:
                result = subprocess.run([
                    "adb", "shell", "dumpsys", "activity", "activities"
                ], capture_output=True, text=True, check=True)
                
                if self.activity not in result.stdout:
                    print("\nSync completed")
                    return True
                    
                time.sleep(2)
                print(".", end="", flush=True)
                
            except subprocess.CalledProcessError:
                print("\nFailed to check sync status")
                return False
                
        print(f"\nSync timeout after {timeout} seconds")
        return False

def main():
    if len(sys.argv) < 2:
        print("Usage: romm_sync.py <command> [options]")
        print("\nCommands:")
        print("  upload [platform] [emulator]     - Upload saves to server")
        print("  download [platform] [emulator]   - Download saves from server")  
        print("  sync [platform] [emulator]       - Two-way sync")
        print("  preview [platform] [emulator]    - Show sync plan")
        print("\nExamples:")
        print("  romm_sync.py upload snes snes9x")
        print("  romm_sync.py download")
        print("  romm_sync.py sync psx") 
        print("  romm_sync.py preview")
        return 1
        
    client = RomMSyncClient()
    command = sys.argv[1].lower()
    platform = sys.argv[2] if len(sys.argv) > 2 else None
    emulator = sys.argv[3] if len(sys.argv) > 3 else None
    
    if command == "upload":
        return 0 if client.sync("upload", platform, emulator) else 1
    elif command == "download":
        return 0 if client.sync("download", platform, emulator) else 1
    elif command == "sync":
        return 0 if client.sync("bidirectional", platform, emulator) else 1
    elif command == "preview":
        return 0 if client.sync("bidirectional", platform, emulator, dry_run=True) else 1
    else:
        print(f"Unknown command: {command}")
        return 1

if __name__ == "__main__":
    sys.exit(main())
```

## Integration with Popular Emulators

### RetroArch Integration

RetroArch can be configured to automatically sync saves after gaming sessions:

```bash
# Add to RetroArch's config or create a wrapper script
#!/bin/bash
retroarch "$@"
EXIT_CODE=$?

# After RetroArch exits, sync saves
if [ $EXIT_CODE -eq 0 ]; then
    echo "RetroArch session ended, syncing saves..."
    ./sync_romm.sh upload
fi

exit $EXIT_CODE
```

### Standalone Emulator Integration

Many emulators support post-game scripts that can trigger sync:

```bash
# SNES9x post-game script
#!/bin/bash
GAME_PATH="$1"
SAVE_PATH="$2"

# Extract platform from path
PLATFORM=$(basename $(dirname "$SAVE_PATH"))

echo "Game session ended for $GAME_PATH"
echo "Syncing saves for platform: $PLATFORM"

./sync_romm.sh upload "$PLATFORM" "snes9x"
```

## Directory Structure Requirements

The sync system expects save files to be organized in a specific structure to detect platform and emulator information:

```
Save Files Directory:
├── snes/
│   ├── snes9x/
│   │   ├── Super Mario World.srm
│   │   └── Zelda - A Link to the Past.srm
│   └── bsnes/
│       └── Super Metroid.sav
├── n64/
│   └── mupen64plus/
│       ├── Super Mario 64.eep
│       └── Ocarina of Time.fla
└── psx/
    └── duckstation/
        ├── Final Fantasy VII.mcr
        └── Metal Gear Solid.mcd

Save States Directory:
├── snes/
│   └── snes9x/
│       ├── Super Mario World.st0
│       └── Super Mario World.st1
├── n64/
│   └── mupen64plus/
│       └── Mario Kart 64.st0
└── psx/
    └── epsxe/
        └── Final Fantasy VII.sts
```

## Supported File Extensions

### Save Files
- `.sav`, `.srm`, `.save` - Generic save files
- `.mcr`, `.mc` - Memory card files (PlayStation)
- `.eep`, `.fla` - EEPROM/Flash saves (N64)
- `.gme` - Game saves
- `.dat`, `.bkp` - Data/backup files

### Save States  
- `.state`, `.st0`-`.st9` - Save states
- `.ss0`-`.ss9` - Save states (alternate naming)
- `.sts`, `.savestate` - Save states
- Files containing "state" or "slot" in the name

The sync system automatically detects file types and matches them with the appropriate RomM server endpoints.