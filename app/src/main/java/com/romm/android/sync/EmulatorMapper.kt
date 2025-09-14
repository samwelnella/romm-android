package com.romm.android.sync

object EmulatorMapper {
    
    private val knownEmulators = mapOf(
        // RetroArch
        "retroarch" to "RetroArch",
        
        // Nintendo emulators
        "snes9x" to "Snes9x",
        "zsnes" to "ZSNES",
        "bsnes" to "bsnes",
        "nestopia" to "Nestopia",
        "fceux" to "FCEUX",
        "mupen64" to "Mupen64Plus",
        "project64" to "Project64",
        "dolphin" to "Dolphin",
        "visualboy" to "VisualBoy Advance",
        "mgba" to "mGBA",
        "desmume" to "DeSmuME",
        
        // Sony emulators
        "pcsx2" to "PCSX2",
        "ppsspp" to "PPSSPP",
        "epsxe" to "ePSXe",
        
        // Multi-system emulators
        "mednafen" to "Mednafen",
        
        // Arcade emulators
        "mame" to "MAME",
        "finalburn" to "FinalBurn Neo",
        "fbneo" to "FinalBurn Neo",
        
        // Sega emulators
        "gens" to "Gens",
        "fusion" to "Fusion",
        
        // 3DO emulators
        "opera" to "Opera",

        // Atari emulators
        "virtual jaguar" to "Virtual Jaguar",
        "virtualjaguar" to "Virtual Jaguar",
        "jaguar" to "Virtual Jaguar",

        // TurboGrafx-16 / PC Engine emulators
        "mednaffe" to "Mednaffe",
        "ootake" to "Ootake",
        "magic engine" to "Magic Engine",
        "magicengine" to "Magic Engine"
    )
    
    /**
     * Identifies an emulator from a directory name.
     * Returns null if the emulator is not in the known/whitelisted list.
     */
    fun identifyEmulator(directoryName: String): String? {
        val lower = directoryName.lowercase()
        
        // Check each known emulator pattern
        for ((key, value) in knownEmulators) {
            if (lower.contains(key)) {
                return value
            }
        }
        
        return null
    }
    
    /**
     * Returns true if the emulator is known/whitelisted for syncing.
     */
    fun isKnownEmulator(emulatorName: String?): Boolean {
        if (emulatorName == null) return false
        return knownEmulators.values.any { it.equals(emulatorName, ignoreCase = true) }
    }
    
    /**
     * Gets all known emulator names for reference.
     */
    fun getAllKnownEmulators(): List<String> {
        return knownEmulators.values.distinct().sorted()
    }
}