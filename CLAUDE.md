### 1. Plan Mode Default
- Enter plan mode for ANY not-trivial task (3+ steps or architectural decisions)
- Use plan mode for verification steps, not just building
- Write detailed specs upfront to reduce ambiguity

### 2. Self-Improvement Loop
- After ANY correction from the user: update `tasks/lessons.md` with the pattern
- Write rules for yourself that prevent the same mistake
- Ruthlessly iterate on these lessons until the mistake rate drops
- Review lessons at session start for a project

### 3. Verification Before Done
- Never mark a task complete without proving it works
- Diff behavior between main and your changes when relevant
- Ask yourself: "Would a staff engineer approve this?"
- Run tests, check logs, demonstrate correctness

### 4. Demand Elegance (Balanced)
- For non-trivial changes: pause and ask "is there a more elegant way?"
- If a fix feels hacky: "Knowing everything I know now, implement the elegant solution"
- Skip this for simple, obvious fixes. Don't overengineer
- Challenge your own work before presenting it

## Core Principles
- **Simplicity First**: Make every change as simple as possible. Impact minimal code
- **No Laziness**: Find root causes. No temporary fixes. Senior developer standards

## Skill Usage Guide

Skills are in `.claude/skills/`. Do NOT load all skills at once — read `.claude/skills/README.md` first as routing table.

| Task Context | Load This Skill |
|---|---|
| **User drops a large feature requirement** | `feature-delivery` ← ALWAYS start here |
| Code review, refactor, clean code | `code-quality` |
| Design patterns, DDD, Value Objects | `design-patterns` |
| MyBatis mapper, query optimization | `jpa-patterns` |
| @Transactional, service layer, rollback | `transaction-patterns` |
| Logging, debugging | `logging-patterns` |
| Building a specific targeted Spring Boot feature | `spring-boot` → then sub-reference if needed |
| Error handling, interceptors, filters, response helpers | `web-infra` |
| MyBatis mapper, service layer, constants, package structure, Lombok | `coding-conventions` |

**Rule:** Load at most 1-2 skills per task. Only load sub-references (e.g. `spring-boot/references/security.md`) when the main skill isn't enough.

**Feature Delivery Rule:** When the user provides a new feature requirement (not a bug fix, not a refactor), ALWAYS load `feature-delivery/SKILL.md` first and follow its orchestration protocol exactly. Do not jump straight to coding.

## Project General Instructions

- This is a Spring Boot 3.5.x project using Java 21.
- Always write Java code as the Spring Boot application.
- Always use Maven for dependency management.
- Always create test cases for the generated code both positive and negative.
- Minimize the amount of code generated.
- Use `com.example` as the group ID for the Maven project and base Java package.
- Key dependencies: Spring Boot Starter, Redis, MyBatis, RocketMQ, gRPC, Actuator.
- Existing Docker Compose is available for infrastructure services.
- Use Lombok for entity and non-record classes where it reduces boilerplate.
- Data access: always use MyBatis `@Mapper` with annotation SQL. Never use Spring Data JPA.
- Metrics: always add `@Timed(value = "db.{entity}.execute", percentiles = {0.99, 0.95})` on every `@Mapper` interface. Register `TimedAspect` bean in a config class.
- DTOs: use Java records. Split into `dto/request/`, `dto/response/`, and `dto/` (shared data objects with `Dto` suffix).
- Update README.md when adding significant new features.
