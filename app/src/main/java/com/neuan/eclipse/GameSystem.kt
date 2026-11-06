package com.neuan.eclipse

enum class GameSystem(
    val label: String,
    val short: String,
    val exts: List<String>,
    val coreLib: String,
    val hasXY: Boolean,
    val hasShoulder: Boolean,
    val defaultAspect: Float,
    val color: Long,
) {
    GB("Game Boy", "GB", listOf("gb"), "libgambatte_libretro.so", false, false, 10f / 9f, 0xFF6B8E23),
    GBC("Game Boy Color", "GBC", listOf("gbc"), "libgambatte_libretro.so", false, false, 10f / 9f, 0xFF2E8B57),
    GBA("Game Boy Advance", "GBA", listOf("gba"), "libmgba_libretro.so", false, true, 3f / 2f, 0xFF5B4A9E),
    NES("Nintendo Entertainment System", "NES", listOf("nes"), "libfceumm_libretro.so", false, false, 4f / 3f, 0xFFB22222),
    SNES("Super Nintendo", "SNES", listOf("sfc", "smc"), "libsnes9x_libretro.so", true, true, 4f / 3f, 0xFF4B5FA8);

    companion object {
        fun fromExt(ext: String): GameSystem? = values().firstOrNull { ext.lowercase() in it.exts }
    }
}
