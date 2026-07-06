package dev.diena.anion.data.registry

import dev.diena.anion.Anion.Companion.NAMESPACE

open class AnionRegistryKey private constructor(
    val namespace: String,
    val key: String
) {
    constructor(key: String) : this(NAMESPACE, key)

    override fun equals(other: Any?) = other is AnionRegistryKey && namespace == other.namespace && key == other.key
    override fun hashCode() = 31 * namespace.hashCode() + key.hashCode()
    override fun toString() = "$namespace:$key"
}