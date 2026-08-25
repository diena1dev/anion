Anion Commands have a generalized structure that must be followed when implementing new commands.

Guidelines
- Respect existing code style (Indentation pattern mainly, its easier for me to read when things are spaced apart.)
- All added command objects MUST be suffixed with "Command" and have a set name specified with the `@Alias()` annotation.
- When adding an `@Inferred` command component, ensure the corresponding function is either `self()` or `other()`, depending on what it targets.
- When adding a `@Subcommand` command component, ensure the name is sensible and fully communicates what the subcommand does (Code comments do not count.)
- Follow the posted ordering of Annotations (Found in the code block below.)

```kotlin

@Command
@Name("command_name")
@Permission("example.utils.fly")
object ExampleFlyCommand {
	
	@Subcommand
    @Permission("example.utils.fly.toggle")
    fun toggleFlight() {
		TODO()
	}
    
    @Inferred
    fun self() {
		TODO()
	}
	
}

```