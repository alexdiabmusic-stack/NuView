package app.tvessentials.core.version

/** How an installed build relates to the newest verified build of the selected channel. */
enum class UpdateStatus {
    /** Installed build is the same as the latest verified one. */
    UP_TO_DATE,

    /** A strictly newer verified build exists. */
    UPDATE_AVAILABLE,

    /** Installed build is newer than the channel's latest (e.g. a beta installed while on Stable). Never downgraded. */
    INSTALLED_IS_NEWER,

    /** The latest version could not be verified, or the schemes are not comparable. */
    UNKNOWN,
}

object UpdateDecision {
    /**
     * Decides using version codes when both are known (they are what Android itself enforces),
     * otherwise by parsed version names. If neither pair is comparable the answer is UNKNOWN:
     * the installer never infers an order from raw text.
     */
    fun decide(
        installedName: String?,
        installedCode: Long?,
        latestName: String?,
        latestCode: Long?,
    ): UpdateStatus {
        if (installedCode != null && latestCode != null) {
            return when {
                latestCode > installedCode -> UpdateStatus.UPDATE_AVAILABLE
                latestCode < installedCode -> UpdateStatus.INSTALLED_IS_NEWER
                else -> UpdateStatus.UP_TO_DATE
            }
        }
        val installed = Version.parseOrNull(installedName) ?: return UpdateStatus.UNKNOWN
        val latest = Version.parseOrNull(latestName) ?: return UpdateStatus.UNKNOWN
        return when {
            latest > installed -> UpdateStatus.UPDATE_AVAILABLE
            latest < installed -> UpdateStatus.INSTALLED_IS_NEWER
            else -> UpdateStatus.UP_TO_DATE
        }
    }
}
