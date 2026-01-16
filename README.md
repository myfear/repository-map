# Product Requirements Document  
## Java Repository Map (JRM)

## 0. Example project
The repository contains a simple example Quarkus project (code-with-quarkus) that should be used as the object to analyze. It is intentionally simple and can be extended with constructs when neccessary.


## 1. Problem Statement

Modern Java codebases have reached a scale and level of indirection where architectural understanding no longer emerges from reading files. Annotations, dependency injection, build-time augmentation, code generation, and modular builds mean that the true shape of the system is implicit, distributed, and mostly undocumented.

Historically, this was survivable because senior engineers carried the system map in their heads, change velocity was bounded by human review, and refactoring was deliberate and infrequent.

AI-assisted development breaks this equilibrium.

AI tools operate locally unless explicitly constrained. Without an explicit structural map, they invent new abstractions instead of reinforcing existing ones, cross architectural boundaries unintentionally, and refactor correct code into structurally invalid systems.

Java has the data needed to describe system shape, but no canonical artifact that represents it.

The Java Repository Map (JRM) addresses this gap.

---

## 2. Goal

Create a lossy, topology-preserving structural representation of a Java repository that:

- Captures architectural shape, not logic  
- Fits into a bounded token or data budget  
- Can be consumed by both humans and machines  
- Biases automated tools toward conservative, architecture-preserving changes  

The JRM is not documentation, not a diagram, and not a full graph.  
It is a change-safety primitive.

---

## 3. Non-Goals

The Java Repository Map explicitly does not aim to:

- Explain business logic  
- Replace architecture documentation  
- Generate code  
- Enforce architectural rules  
- Visualize runtime behavior  
- Act as a dependency resolver  

It describes shape, not meaning.

---

## 4. Target Users

### Primary
- Enterprise Java teams using AI-assisted development tools  
- Platform teams maintaining large Quarkus or Jakarta EE services  
- Architects responsible for long-lived systems  

### Secondary
- Tool vendors integrating AI with Java  
- Static analysis and refactoring tool authors  
- Build tooling and platform engineers  

---

## 5. Core Use Cases

1. AI-assisted refactoring  
   Provide an automated tool with a bounded structural map before it modifies code.

2. Change impact reasoning  
   Identify structurally central packages, classes, and modules.

3. Architectural drift detection  
   Detect when new code diverges from established topology.

4. Onboarding acceleration  
   Give new engineers a compressed system overview.

5. Build-time intelligence  
   Surface architectural metadata during CI or build steps.

---

## 6. Functional Requirements

### 6.1 Structural Extraction

The JRM must extract, at minimum:

- Modules (JPMS or logical)  
- Packages  
- Classes and records  
- Public methods and fields  
- Import relationships  
- Annotation presence at type level  

Sources include:
- Source code  
- Compiled bytecode  
- Build metadata  

Existing tools may be used as inputs, but are not exposed directly.

---

### 6.2 Importance Inference

The system must infer relative importance, not absolute correctness.

Signals include:
- Fan-in and fan-out  
- Transitive dependency depth  
- Usage frequency across modules  
- Annotation density (for example CDI, REST, persistence)  
- Role heuristics such as entry point versus leaf  

Importance is heuristic, probabilistic, and explicitly non-deterministic.

---

### 6.3 Lossy Compression

The map must:

- Discard implementation details  
- Preserve dependency direction  
- Preserve relative importance ordering  
- Fit within a configurable size budget  

Loss is intentional and required.

---

### 6.4 Output Format

The JRM must be representable as:

- Structured text such as YAML or JSON  
- Deterministic ordering  
- Stable identifiers across runs where possible  

It must not require a database or runtime service to consume.

---

### 6.5 Incremental Updates

The JRM should support:

- Regeneration based on version control diffs  
- Partial recomputation  
- Caching between builds  

Full recomputation must remain possible.

---

## 7. Non-Functional Requirements

### Performance
- Runs within typical CI time budgets  
- No runtime impact on application execution  

### Determinism
- Same input yields structurally equivalent output  
- Stable ordering for diffing  

### Transparency
- Inference heuristics must be explainable  
- No opaque scoring without traceability  

### Tool-Agnosticism
- No IDE dependency  
- No assumption of a specific AI vendor  

---

## 8. Architecture Overview

High-level flow:

1. Source and bytecode ingestion  
2. Structural graph construction  
3. Importance scoring  
4. Lossy projection  
5. Stable serialization  

The process is offline and side-effect free.

---

## 9. Integration Points

### Build Tools
- Maven  
- Gradle  

### Framework Awareness
- First-class support for Quarkus  
- Annotation-driven frameworks such as Jakarta EE and Spring-compatible stacks  

### Tooling
- CI pipelines  
- Refactoring tools  
- AI assistants  
- Code review systems  

---

## 10. Security and Safety Considerations

- No execution of user code  
- No reflection-based runtime inspection  
- No network access required  
- Read-only repository access  

The JRM must be safe to run in regulated environments.

---

## 11. Risks and Trade-Offs

### Risk: False importance signals  
Mitigation: Emphasize relative ordering, not absolute ranking.

### Risk: Overfitting to frameworks  
Mitigation: Modular inference layers and pluggable heuristics.

### Risk: Perceived authority  
Mitigation: Position the map as advisory, not normative.

---

## 12. Success Metrics

- Reduction in architecture-breaking automated changes  
- Improved refactoring acceptance rate  
- Faster onboarding without architectural violations  
- Stable diffs across builds  
- Adoption by tooling vendors  

---

## 13. Strategic Insight

The Java Repository Map is not a developer convenience feature.

It is missing infrastructure for a world where code is changed by agents, architecture is implicit, and humans are reviewers rather than sole authors.

Java’s strength has always been structure.  
The JRM makes that structure explicit, compressible, and transferable.

---

## 14. One-Line Summary

The Java Repository Map turns implicit architectural knowledge into a first-class artifact, so automated tools can change systems without breaking them.

---

## 15. Reference Implementation (JRM)

This repository now includes a lightweight reference implementation in `jrm/` that generates a
JSON map for the bundled Quarkus sample (`code-with-quarkus`).

### How it works

| Requirement | Implementation |
| --- | --- |
| Dependency shape | Uses `jdeps` against `target/classes` when available; falls back to import-level package edges from source. |
| Symbol indexing | Extracts packages, types, annotations, and public members from Java source. |
| Usage frequency | Counts symbol references across the repository (static analysis heuristic). |
| Build metadata | Parses `pom.xml` for coordinates, dependencies, plugins, and Quarkus features. |

### Run the tool

```bash
mvn -f jrm/pom.xml -q package
java -jar jrm/target/java-repository-map-0.1.0-SNAPSHOT.jar --project code-with-quarkus --output jrm-output.json
```

The resulting JSON includes `dependencyShape`, `symbolIndex`, `usageFrequency`, and `buildMetadata`
sections so you can feed the map to downstream tooling.

### JRM Implementation TODO

- [x] Dependency shape via `jdeps` with source import fallback.
- [x] Symbol indexing for packages, types, annotations, and public members.
- [x] Usage-frequency heuristic from static source analysis.
- [x] Build metadata extraction from `pom.xml` (coordinates, dependencies, plugins, Quarkus features).
- [ ] Incremental updates based on VCS diffs.
- [ ] Importance scoring (fan-in/fan-out, depth, annotation density).
- [ ] Stable identifiers across multi-module builds.
- [ ] Output size budget controls and lossy projection tuning.
