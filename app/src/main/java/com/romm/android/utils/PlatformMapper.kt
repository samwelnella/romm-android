package com.romm.android.utils

/**
 * Maps RomM platform slugs to ES-DE (EmulationStation Desktop Edition) folder names
 */
object PlatformMapper {
    
    private val rommToEsdeMapping = mapOf(
        // Nintendo consoles
        "nes" to "nes",
        "snes" to "snes",
        "n64" to "n64",
        "gb" to "gb",
        "gbc" to "gbc", 
        "gba" to "gba",
        "nds" to "nds",
        "3ds" to "3ds",
        "gc" to "gc",
        "wii" to "wii",
        "wiiu" to "wiiu",
        "switch" to "switch",
        
        // Sega consoles
        "sms" to "mastersystem",
        "md" to "megadrive",
        "genesis" to "genesis",
        "scd" to "segacd",
        "32x" to "sega32x",
        "saturn" to "saturn",
        "dc" to "dreamcast",
        "gg" to "gamegear",
        
        // Sony consoles
        "psx" to "psx",
        "ps2" to "ps2",
        "psp" to "psp",
        "psv" to "psvita",
        
        // Atari systems
        "atari2600" to "atari2600",
        "atari5200" to "atari5200",
        "atari7800" to "atari7800",
        "atarist" to "atarist",
        "atarilynx" to "atarilynx",
        "atarijaguar" to "atarijaguar",
        
        // SNK systems
        "ngp" to "ngp",
        "ngpc" to "ngpc",
        "neogeo" to "neogeo",
        "neogeocd" to "neogeocd",
        
        // Other handhelds
        "wonderswan" to "wonderswan",
        "wonderswancolor" to "wonderswancolor",
        
        // Arcade
        "mame" to "arcade",
        "fbneo" to "fbneo",
        "cps1" to "cps1",
        "cps2" to "cps2",
        "cps3" to "cps3",
        
        // Computers
        "c64" to "c64",
        "amiga" to "amiga",
        "amstradcpc" to "amstradcpc",
        "zxspectrum" to "zxspectrum",
        "msx" to "msx",
        "msx2" to "msx2",
        
        // Other systems
        "pcengine" to "pcengine",
        "pcenginecd" to "pcenginecd",
        "tg16" to "tg16",
        "tg-cd" to "tgcd",
        "3do" to "3do",
        "channelf" to "channelf",
        "colecovision" to "colecovision",
        "intellivision" to "intellivision",
        "odyssey2" to "odyssey2",
        "vectrex" to "vectrex",
        
        // Modern systems
        "dos" to "dos",
        "scummvm" to "scummvm",
        "ports" to "ports"
    )
    
    /**
     * Get the ES-DE folder name for a given RomM platform slug
     * @param rommSlug The RomM platform slug
     * @return The ES-DE folder name, or the original slug if no mapping exists
     */
    fun getEsdeFolderName(rommSlug: String): String {
        return rommToEsdeMapping[rommSlug] ?: rommSlug
    }
    
    /**
     * Check if a mapping exists for the given RomM platform slug
     * @param rommSlug The RomM platform slug
     * @return True if mapping exists, false otherwise
     */
    fun hasMappingFor(rommSlug: String): Boolean {
        return rommToEsdeMapping.containsKey(rommSlug)
    }
    
    /**
     * Get all supported RomM platform slugs
     * @return Set of all RomM platform slugs that have ES-DE mappings
     */
    fun getSupportedRommSlugs(): Set<String> {
        return rommToEsdeMapping.keys
    }
    
    /**
     * Get all ES-DE folder names
     * @return Set of all ES-DE folder names
     */
    fun getEsdeFolderNames(): Set<String> {
        return rommToEsdeMapping.values.toSet()
    }
}