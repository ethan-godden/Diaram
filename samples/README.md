# Memory Diagram sample programs

A plain Eclipse Java project (`MemoryDiagramSamples`, JavaSE-21) of small
programs to debug against the **Memory Diagram** view while developing the
plug-in. It is intentionally **not** a Maven module — it is not part of the
Tycho reactor and is never built by `mvn -f parent verify`. It exists only to
give the runtime Eclipse instance something interesting to suspend on.

## One-time setup: import into the runtime workspace

Eclipse does not auto-import projects into a runtime (Eclipse Application)
workspace, so import this project **once**. Because the launch config uses
`clearws=false`, the import persists across every later launch.

1. Run the `diaram` plug-in as an **Eclipse Application** (the
   `diaram.launch` in `plugin/`, runtime workspace
   `runtime-EclipseApplication/`).
2. In the launched instance: **File > Import… > General > Existing Projects into
   Workspace > Next**.
3. Set the root directory to this `samples/` folder (…/diaram/samples).
4. Leave **"Copy projects into workspace" unchecked** so the source stays here in
   the repo (the runtime workspace just references it in place).
5. **Finish.** `MemoryDiagramSamples` now appears in the Package Explorer and
   stays there on subsequent launches.

## Using them

Open any program, set a breakpoint (each file's Javadoc names a good spot),
**Debug As > Java Application**, then open **Window > Show View > Other… >
Debug > Memory Diagram**. Resume/step to watch the diagram update.

| Program | Exercises |
| --- | --- |
| `LinkedListDemo` | Chain of heap nodes, reference arrows, growing locals |
| `BinaryTreeDemo` | Tree fan-out, recursion, in-order walk |
| `ArraysDemo` | Primitive / 2-D / object arrays, element swaps |
| `StringsAndBoxingDemo` | Strings, `StringBuilder`, boxed wrappers, Integer cache |
| `EnumsAndStaticsDemo` | Enum singletons + static fields |
| `CollectionsDemo` | `ArrayList` / `HashMap` / `HashSet` internals |
| `CyclicReferencesDemo` | Reference cycles and self-references |
| `RecursionDemo` | Deep stack, one `n` local per frame |
| `MutationOverTimeDemo` | NEW / UPDATED variable diff highlighting (Resume repeatedly) |
| `PolymorphismDemo` | Runtime subtype labelling under a `List<Shape>` |
| `RecordsDemo` | Nested immutable records |

`MutationOverTimeDemo` is the one to run for the variable diff highlighting: set the
marked breakpoint and press **Resume (F8)** repeatedly.
