package kz.epharm.shared.identity

enum class SourceSystem { EPHARM, MERCH_CRM }

enum class SourceEntityType { PHARMACY, BRANCH, PHARMACIST, ADMIN_ACCOUNT, CUSTOMER, ACCOUNT, PERSON }

enum class CanonicalEntityType { PHARMACY, PHARMACIST, ADMIN_ACCOUNT, CUSTOMER, ACCOUNT, PERSON }

/** A trusted source-record ID, not UI input. IDs are opaque: never trim, fold case or infer a type. */
data class SourceIdentity(
    val system: SourceSystem,
    val entityType: SourceEntityType,
    val originalId: String,
) {
    init {
        require(originalId.isNotBlank()) { "Source identity ID must not be blank" }
    }
}

/** Canonical type is part of identity; an account, pharmacist, customer and person are distinct. */
data class CanonicalIdentity(val entityType: CanonicalEntityType, val id: String) {
    init {
        require(id.isNotBlank()) { "Canonical identity ID must not be blank" }
    }
}

/**
 * A caller-supplied, verified crosswalk. The reference records provenance only; this pure value
 * does not verify database endpoints, evidence or approval. Only the established branch-to-pharmacy
 * relationship is supported. Human/account/person bindings need a separately reviewed contract.
 */
data class VerifiedCrosswalkBinding(
    val source: SourceIdentity,
    val canonical: CanonicalIdentity,
    val verificationReference: String,
) {
    init {
        require(verificationReference.isNotBlank()) { "Crosswalk verification reference must not be blank" }
        require(
            source.system == SourceSystem.MERCH_CRM &&
                source.entityType == SourceEntityType.BRANCH &&
                canonical.entityType == CanonicalEntityType.PHARMACY,
        ) { "Unsupported crosswalk relationship" }
    }
}

/**
 * An immutable, one-to-one crosswalk snapshot. Conflicts require review; no source record is removed.
 * Null means unresolved, including CRM-only branches and every account/person association.
 * Existing ePharm IDs must come from trusted DB values (UUID accounts use UUID.toString()).
 * Resolution neither proves existence nor grants access, and never generates an ID.
 */
class SourceIdentityResolver(bindings: Iterable<VerifiedCrosswalkBinding> = emptyList()) {
    private val mappings = buildMap {
        val aliases = mutableMapOf<CanonicalIdentity, SourceIdentity>()
        for (binding in bindings) {
            val previous = get(binding.source)
            require(previous == null || previous == binding.canonical) { "Conflicting source crosswalk" }
            val alias = aliases[binding.canonical]
            require(alias == null || alias == binding.source) { "Conflicting canonical crosswalk alias" }
            put(binding.source, binding.canonical)
            aliases[binding.canonical] = binding.source
        }
    }

    fun resolve(source: SourceIdentity): CanonicalIdentity? {
        if (source.system != SourceSystem.EPHARM) return mappings[source]
        val type = when (source.entityType) {
            SourceEntityType.PHARMACY -> CanonicalEntityType.PHARMACY
            SourceEntityType.PHARMACIST -> CanonicalEntityType.PHARMACIST
            SourceEntityType.ADMIN_ACCOUNT -> CanonicalEntityType.ADMIN_ACCOUNT
            SourceEntityType.CUSTOMER -> CanonicalEntityType.CUSTOMER
            SourceEntityType.BRANCH, SourceEntityType.ACCOUNT, SourceEntityType.PERSON -> return null
        }
        return CanonicalIdentity(type, source.originalId)
    }
}
