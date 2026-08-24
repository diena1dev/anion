# dclang prototype syntax
-# (datachannel language)

all editing of .dcprgm files is done via the mainframe. files written can be assigned to different machines, and all files can be backed up and moved using in-game methods.
this is to hopefully make the bulk development of ships more enjoyable for normal people!

`available inputs: []` are the available inputs for the machine being edited- a control seat has all player inputs exposed, while a control panel would have certain buttons and lever states exposed.
`available modes: []` are the modes that the available inputs can be mapped to- should be pretty self-explanatory
`available groups: []` is explained underneath this first block
```dclang
--- editing [main.dcprgm] for [ctrl_seat_1] ---
| dclang v0.1 (for some basic documentation and advanced syntax for dclang, vist https://wiki.anion.diena.dev/mainframes/dclang#v0.1)
|
| available inputs: [lc|rc|w|a|s|d|sp|sh|altlc|altrc|altw|alta|alts|altd|altsp|altsh]
| available modes: [toggle|hold]
| available groups: [primary_weapons|secondary_weapons|weapons|frwd_thrust]

// (what do i put here for logic oml)
// groups defined in groups.dcprgm can have functions called using inputs
// as inputs are broadcast as soon as they are recieved

// for example, since [trbo_lsr_1] has the [fire|reload] functions, those can be called on the [weapons] group like so:

set [altlc] to [weapons:fire] mode [toggle]

// what makes this neat is that it broadcasts that function call to all machines in the [weapons] group.
// it does not need to care if the function actually exists, it just needs to send the signal.
// if there is no need to use a group (though groups should always be used), a machine can also directly reference the other machine's mainframe name and send a function that way.
// e.g. `set [sp] to [med_up_hyro_thrust_1:increase_throttle] mode [hold]`

```

`available groups: []` in `ctrl_seat_1`'s `main.dcprgm` expands with the amount of player-assigned groups
```dclang
--- editing [groups.dcprgm] for [basic_mnfrm_1] ---
| dclang v0.1 (for some basic documentation and advanced syntax for dclang, vist https://wiki.anion.diena.dev/mainframes/dclang#v0.1)
|
| available machines: [ctrl_seat_1|tiny_hor_ion_thrust_1|tiny_hor_ion_thrust_2|med_up_hydro_thrust_1|trbo_lsr_1|trpdo_1|trpdo_2]

// `def_group` defines a group
// ALL variables must be enclosed in brackets `[like_this]`

def_group [primary_weapons]
def_group [secondary_weapons]
def_group [weapons]
def_group [frwd_thrust]

// `set` is an operator for moving references
// `in` specifies where the `put` action is assigning them to
// `and` chains actions; so writing
// `set [e1] and [e2] in [g1] and [g2]`
// is the exact same as writing
// `set [e1] in [g1]; set [e2] in [g1]; set [e1] in [g2]; set [e2] in [g2]`

// weapon groups
set [trpdo_1] and [trpdo_2] in [secondary_weapons] and [weapons]
set [trbo_lsr_1] in [primary_weapons] and [weapons]

// thruster groups
set [tiny_hor_ion_thrust_1] and [tiny_hor_ion_thrust_2] in [frwd_thrust]

```

and most machines have available functions that can be called by data channels
in this context, `available inputs: []` lists the visible input variables. some machines broadcast these immediately upon change, while others (like this turbo laser) only update peroidically.
`available functions" []` lists the available functions that can be called from other machines.
```dclang
--- editing [main.dcprgm] for [trbo_lsr_1] ---
| dclang v0.1 (for some basic documentation and advanced syntax for dclang, vist https://wiki.anion.diena.dev/mainframes/dclang#v0.1)
|
| available inputs: [currnt_chrg]
| available functions: [fire|reload]

```

```dclang
--- editing [main.dcprgm] for [tiny_hor_ion_thrust_1] ---
| dclang v0.1 (for some basic documentation and advanced syntax for dclang, vist https://wiki.anion.diena.dev/mainframes/dclang#v0.1)
|
| available inputs: [currnt_throttle|toggled_state]
| available functions: [increase_throttle|decrease_throttle|toggle|reset]

```

notice how the two thrusters have the same exact functions.
```dclang
--- editing [main.dcprgm] for [med_up_hydro_thrust_1] ---
| dclang v0.1 (for some basic documentation and advanced syntax for dclang, vist https://wiki.anion.diena.dev/mainframes/dclang#v0.1)
|
| available inputs: [currnt_throttle|toggled_state]
| available functions: [increase_throttle|decrease_throttle|toggle|reset]

```
