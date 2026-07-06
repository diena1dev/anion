package dev.diena.anion.data.registry

import net.minecraft.resources.Identifier
import org.bukkit.NamespacedKey

abstract class AnionRegistry<V> {

    abstract val registryKey: AnionRegistryKey
    abstract val all: MutableMap<AnionRegistryKey, V>

    /** Throws [IllegalStateException] on duplicate key. */
    fun register(key: AnionRegistryKey, value: V) {
        if (all.containsKey(key)) throw IllegalStateException("Duplicate key $key in registry $registryKey. Fix your registrations!")
        all[key] = value
    }

    fun getValue(key: AnionRegistryKey): V? = all[key]
    fun getValue(namespacedKey: NamespacedKey): V? = all[AnionRegistryKey(namespacedKey.key)]
    fun getValue(identifier: Identifier): V? = all[AnionRegistryKey(identifier.path)]

    fun getOrThrow(key: AnionRegistryKey): V =
        all[key] ?: throw IllegalStateException("Missing key $key in registry $registryKey")

    fun containsKey(key: AnionRegistryKey): Boolean = all.containsKey(key)

}
