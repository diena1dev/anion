### For `./database`

`./database/migrators`   - Database Version Migrators | Create a new object for a new Migrator, then add it to [Migrators.kt].
`./database/serializers` - Database Serializers | Create a new object for a new Migrator, then use it in [AnionPersistence.kt].

The other classes within `database` should be self-explanatory.

### For `./datagen`

Currently only houses the `resourcepack` subdirectory.

[AnionResourcePackDatagen.kt] automatically generates a resource pack for Anion's custom items and blocks, pulling from the registry.

If Anion requires datapack components in the future (e.g. if LevelStem proves to be unstable for dimensions), a `datapack` subdirectory will be added.

### For `./registry`

```kotlin

TODO()
// add documentation on how the registry system is structured and what should and should not be added to it.

```