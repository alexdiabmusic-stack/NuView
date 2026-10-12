package app.tvessentials.core.source

/** The facts about this device that decide which build of an app can be installed. */
data class DeviceProfile(
    val sdkInt: Int,
    /** `Build.SUPPORTED_ABIS` in the device's own order of preference. */
    val supportedAbis: List<String>,
)
