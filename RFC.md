# RFC: Java Repository Map (JRM)

## Status
Draft

## Authors
- OpenAI (assistant)

## Audience
Engineering, tooling, and platform teams building or consuming repository-level structural maps for Java.

## Summary
This RFC defines the Java Repository Map (JRM): a lossy, topology-preserving structural representation of a Java repository. The JRM captures architectural shape (not logic), fits into a bounded data budget, can be consumed by both humans and machines, and biases automated tools toward conservative, architecture-preserving changes. The JRM is not documentation, not a diagram, and not a full graph; it is a change-safety primitive.

## Motivation
Java systems encode architecture in package boundaries, build modules, annotations, and dependency flow rather than explicit diagrams. In AI-assisted development, tools frequently operate on local context and can unintentionally cross architectural boundaries. The JRM provides a compact, explicit artifact of system topology that helps automated tools remain conservative and preserve architectural intent during changes.

## Goals
- Preserve architectural topology while discarding implementation details.
- Fit within a bounded token or data budget.
- Provide a stable, deterministic representation suitable for diffing.
- Be consumable by both humans and machines without requiring a live service.
- Bias automated tools toward conservative, architecture-preserving changes.

## Non-Goals
- Explain business logic or runtime behavior.
- Replace or synthesize architecture documentation.
- Provide a full dependency graph or complete semantic model.
- Enforce architectural rules.
- Act as a dependency resolver or build system.

## Terminology
- **Topology**: The structural shape of the codebase (modules, packages, type boundaries, dependency direction).
- **Lossy**: Intentional omission of implementation details and low-signal edges to stay within budget.
- **Importance**: A relative heuristic ranking for structural entities (not absolute correctness).
- **Boundary**: A module, package, or component that should be preserved by automated changes.

## Design Principles
1. **Lossy by default**: Every map is a projection, not a snapshot.
2. **Stable ordering**: Deterministic serialization for consistent diffs.
3. **Conservative bias**: Prioritize boundaries and dependency direction over completeness.
4. **Small, self-contained artifacts**: No services or databases required to consume.
5. **Explainable heuristics**: Importance signals are traceable to inputs.

## Data Model (Conceptual)
The JRM is a bounded structural map with the following logical sections:

1. **Repository Metadata**
   - Coordinates (group/artifact/version when available)
   - Build tool(s), build modules
   - Generation timestamp and schema version

2. **Module Index**
   - Module identifiers (JPMS or logical build modules)
   - Module boundaries (source roots, build roots)
   - Module dependency edges (direction preserved)

3. **Package Index**
   - Package identifiers per module
   - Package-level dependency edges
   - Package role annotations (e.g., entry point, adapter, core)

4. **Type Index**
   - Public types (class, interface, enum, record)
   - Type-level annotations (presence only)
   - Public member signatures (names and visibility only)

5. **Importance Signals**
   - Fan-in / fan-out per package and type
   - Dependency depth approximations
   - Annotation density signals
   - Entry-point heuristics (e.g., main, REST endpoints)

6. **Budget Report**
   - Input size, output size
   - Pruning summary (e.g., edges or entities dropped)

## Serialization Format
- **Allowed**: JSON or YAML
- **Requirements**:
  - Deterministic ordering (stable sort keys)
  - Explicit schema version
  - Single-file output
  - Line-oriented diff friendliness

### Example (Non-Normative)
```json
{
  "schemaVersion": "1.0",
  "repository": {
    "name": "code-with-quarkus",
    "buildTool": "maven"
  },
  "modules": [
    {
      "id": "main",
      "packages": ["org.acme"],
      "dependsOn": []
    }
  ],
  "packages": [
    {
      "id": "org.acme",
      "module": "main",
      "dependsOn": [],
      "importance": "high"
    }
  ],
  "types": [
    {
      "id": "org.acme.GreetingResource",
      "kind": "class",
      "annotations": ["Path"],
      "publicMembers": ["hello()"]
    }
  ]
}
```

## Extraction Pipeline (Normative)
1. **Ingest**
   - Read source roots and build metadata (e.g., Maven/Gradle).
   - Load compiled bytecode if available, falling back to source parsing.

2. **Construct Structural Graph**
   - Capture module, package, and type boundaries.
   - Capture dependency direction at module and package levels.

3. **Compute Importance Signals**
   - Estimate fan-in/fan-out counts.
   - Estimate dependency depth.
   - Flag structural roles based on annotations (e.g., REST endpoints).

4. **Project to Budget**
   - Apply pruning rules:
     - Drop low-signal edges and private members.
     - Merge small leaf packages where necessary.
   - Preserve boundary identifiers and dependency direction.

5. **Serialize**
   - Emit deterministic JSON/YAML.
   - Include a budget report describing reductions.

## Budgeting Rules
- **Configurable target size** in tokens or bytes.
- **Priority order**:
  1. Module and package boundaries
  2. Dependency direction between modules/packages
  3. Importance signals
  4. Public types and member names
- **Pruning strategy**:
  - Remove edges with low support or minimal effect on topology.
  - Collapse leaf nodes under a parent if they exceed budget.

## Change-Safety Guidance (Consumer-Side)
Tools should treat the JRM as a conservative constraint:
- Avoid moving types across boundaries unless necessary.
- Prefer local changes within existing packages.
- Preserve dependency directionality.
- Treat high-importance packages/types as change-sensitive.

## Determinism & Stability
- Stable sorting by identifiers.
- Stable identifiers across runs (when possible).
- Repeatable outputs given the same inputs and configuration.

## Security & Safety
- Read-only operation on source/bytecode/build metadata.
- No execution of user code.
- No network access required for extraction.

## Compatibility
- Works with Maven/Gradle builds.
- JVM bytecode ingestion when available.
- Frameworks with annotation-driven boundaries (Quarkus, Jakarta EE, Spring-compatible stacks).

## Open Questions
- Best strategy for stable identifiers across multi-module builds?
- Default pruning thresholds for different repository sizes?
- How to standardize role annotations across frameworks?

## Success Metrics
- Reduced architecture-breaking automated changes.
- Stable diffs across builds.
- Increased acceptance rate of automated refactorings.

## Appendix: Rationale for Lossiness
Lossiness is required to keep the map bounded and consumable by automated tools. The JRM favors structure-preserving signals over exhaustive completeness, making it safe to use as a change-safety primitive rather than a full documentation artifact.
