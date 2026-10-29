package dev.pairly.android.network

object LegacyMigrationPolicy {
    fun secureCandidates(discovered: List<DiscoveredPc>, previousHost: String): List<DiscoveredPc> =
        discovered
            .filter {
                it.hostId.isNotBlank() &&
                    it.securePort != null &&
                    it.certificateFingerprint.isNotBlank()
            }
            .distinctBy { "${it.hostId}:${it.certificateFingerprint.lowercase()}" }
            .sortedByDescending { it.ip == previousHost }
}
