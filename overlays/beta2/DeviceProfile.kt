package com.oai.perfpilot

/** Runtime identity. Values are read from Android properties instead of being guessed from the app build. */
data class DeviceIdentity(
    val model: String,
    val product: String,
    val soc: String,
    val hardware: String,
    val boardPlatform: String,
    val sdk: Int,
    val release: String,
    val romVersion: String
) {
    val compact: String
        get() = listOf(model, soc.ifBlank { boardPlatform }).filter { it.isNotBlank() }.joinToString(" · ")
}

data class DeviceProfile(
    val id: String,
    val title: String,
    val expectedSocTokens: List<String>,
    val expectedModelTokens: List<String>,
    val refreshRates: List<Int>,
    val fpsgoRoots: List<String>,
    val gedRoots: List<String>,
    val notes: String
) {
    fun score(identity: DeviceIdentity): Int {
        val haystack = listOf(identity.model, identity.product, identity.soc, identity.hardware, identity.boardPlatform)
            .joinToString(" ").lowercase()
        var score = 0
        if (expectedModelTokens.any { haystack.contains(it.lowercase()) }) score += 4
        if (expectedSocTokens.any { haystack.contains(it.lowercase()) }) score += 5
        return score
    }
}

object DeviceProfiles {
    /** Main target: REDMI K90 Max + Dimensity 9500 (MT6993). */
    val K90_MAX_DIMENSITY_9500 = DeviceProfile(
        id = "redmi_k90_max_dimensity_9500",
        title = "REDMI K90 Max · 天玑 9500",
        expectedSocTokens = listOf("Dimensity 9500", "天玑9500", "MT6993", "mt6993"),
        expectedModelTokens = listOf("K90 Max", "REDMI K90 Max"),
        refreshRates = listOf(60, 90, 120, 144, 165),
        fpsgoRoots = listOf(
            "/sys/kernel/fpsgo",
            "/sys/module/mtk_fpsgo/parameters",
            "/proc/perfmgr",
            "/proc/fpsgo"
        ),
        gedRoots = listOf(
            "/sys/kernel/ged/hal",
            "/sys/kernel/ged",
            "/proc/ged"
        ),
        notes = "K90 Max 专项：优先 MTK perfmanager/PowerHAL，sysfs 只在运行时确认节点存在且可访问后使用。"
    )

    val GENERIC_MTK = DeviceProfile(
        id = "generic_mtk",
        title = "通用 MediaTek",
        expectedSocTokens = listOf("MT", "Dimensity", "天玑"),
        expectedModelTokens = emptyList(),
        refreshRates = listOf(60, 90, 120, 144),
        fpsgoRoots = listOf("/sys/kernel/fpsgo", "/sys/module/mtk_fpsgo/parameters", "/proc/perfmgr", "/proc/fpsgo"),
        gedRoots = listOf("/sys/kernel/ged/hal", "/sys/kernel/ged", "/proc/ged"),
        notes = "通用 MTK 自动探测。"
    )

    private val all = listOf(K90_MAX_DIMENSITY_9500, GENERIC_MTK)

    fun resolve(identity: DeviceIdentity): DeviceProfile =
        all.map { it to it.score(identity) }.maxByOrNull { it.second }?.takeIf { it.second > 0 }?.first ?: GENERIC_MTK
}
