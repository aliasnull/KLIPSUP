package alias.klips.net.feature.color

data class CubeLutData(
    val title: String,
    val size: Int,
    val table: FloatArray // size * size * size * 3 RGB triplets in [0..1]
)

object CubeLutParser {

    /**
     * Validates and parses a .cube 3D LUT file. Rejects malformed or unsafe inputs.
     */
    fun parseCubeLut(rawContent: String): Result<CubeLutData> {
        var title = "Custom 3D LUT"
        var lutSize = 0
        val floats = ArrayList<Float>()

        val lines = rawContent.lineSequence()
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            when {
                line.startsWith("TITLE", ignoreCase = true) -> {
                    title = line.substringAfter(" ").trim().removeSurrounding("\"")
                }
                line.startsWith("LUT_3D_SIZE", ignoreCase = true) -> {
                    val sizeStr = line.substringAfter(" ").trim()
                    val parsedSize = sizeStr.toIntOrNull()
                        ?: return Result.failure(IllegalArgumentException("Invalid LUT_3D_SIZE: $sizeStr"))
                    if (parsedSize < 2 || parsedSize > 65) {
                        return Result.failure(IllegalArgumentException("LUT_3D_SIZE ($parsedSize) must be between 2 and 65."))
                    }
                    lutSize = parsedSize
                }
                line.startsWith("DOMAIN_MIN", ignoreCase = true) ||
                line.startsWith("DOMAIN_MAX", ignoreCase = true) ||
                line.startsWith("LUT_1D_SIZE", ignoreCase = true) -> {
                    // Valid metadata headers
                }
                else -> {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size == 3) {
                        val r = parts[0].toFloatOrNull()
                        val g = parts[1].toFloatOrNull()
                        val b = parts[2].toFloatOrNull()
                        if (r != null && g != null && b != null) {
                            floats.add(r.coerceIn(0f, 1f))
                            floats.add(g.coerceIn(0f, 1f))
                            floats.add(b.coerceIn(0f, 1f))
                        }
                    }
                }
            }
        }

        if (lutSize == 0) {
            return Result.failure(IllegalArgumentException("Missing LUT_3D_SIZE header in .cube file."))
        }
        val expectedFloats = lutSize * lutSize * lutSize * 3
        if (floats.size != expectedFloats) {
            return Result.failure(
                IllegalArgumentException("LUT table count mismatch: expected ${expectedFloats / 3} RGB rows for size $lutSize, found ${floats.size / 3}.")
            )
        }

        return Result.success(
            CubeLutData(
                title = title,
                size = lutSize,
                table = floats.toFloatArray()
            )
        )
    }

    /**
     * Generates built-in studio 3D LUT presets (size 17x17x17) for real trilinear grading.
     */
    fun generateStudioPresetLut(presetName: String, size: Int = 17): CubeLutData {
        val table = FloatArray(size * size * size * 3)
        var ptr = 0
        val denom = (size - 1).toFloat()
        for (bIdx in 0 until size) {
            for (gIdx in 0 until size) {
                for (rIdx in 0 until size) {
                    var r = rIdx / denom
                    var g = gIdx / denom
                    var b = bIdx / denom

                    when (presetName) {
                        "Teal & Orange Cinema" -> {
                            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                            r = (r * 1.08f + luma * 0.06f).coerceIn(0f, 1f)
                            g = (g * 0.98f).coerceIn(0f, 1f)
                            b = (b * 0.90f + (1f - luma) * 0.12f).coerceIn(0f, 1f)
                        }
                        "Kodak 2383 Film Print" -> {
                            r = (r * r * (3f - 2f * r) * 1.04f).coerceIn(0f, 1f)
                            g = (g * g * (3f - 2f * g)).coerceIn(0f, 1f)
                            b = (b * b * (3f - 2f * b) * 0.94f + 0.02f).coerceIn(0f, 1f)
                        }
                        "Bleach Bypass Silver" -> {
                            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                            r = ((r * 0.45f + luma * 0.55f - 0.5f) * 1.25f + 0.5f).coerceIn(0f, 1f)
                            g = ((g * 0.45f + luma * 0.55f - 0.5f) * 1.25f + 0.5f).coerceIn(0f, 1f)
                            b = ((b * 0.45f + luma * 0.55f - 0.5f) * 1.25f + 0.5f).coerceIn(0f, 1f)
                        }
                        "Cyberpunk Night Neon" -> {
                            r = (r * 1.12f).coerceIn(0f, 1f)
                            g = (g * 0.86f).coerceIn(0f, 1f)
                            b = (b * 1.18f + 0.04f).coerceIn(0f, 1f)
                        }
                    }
                    table[ptr++] = r
                    table[ptr++] = g
                    table[ptr++] = b
                }
            }
        }
        return CubeLutData(title = presetName, size = size, table = table)
    }
}
