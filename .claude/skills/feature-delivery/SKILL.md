---
name: feature-delivery
description: Full-lifecycle feature delivery for Spring Boot. Takes a large requirement and autonomously runs: requirements breakdown → design → implementation → code review → tests. Use when user drops a big feature request and wants Claude to handle the entire flow.
metadata:
  version: "1.0.0"
  domain: backend
  triggers: 大需求, feature request, implement, build feature, new feature, 實作, 開發, 新功能
  role: orchestrator
  scope: full-lifecycle
  output-format: code + review + tests
---

# Feature Delivery Skill

Orchestrates the complete flow from raw requirement to tested, reviewed code.
Load this skill FIRST. It will tell you when to load sub-skills (spring-boot, code-quality, etc.).

---

## Trigger Condition

Use this skill when the user provides a **feature description** (not a bug fix, not a refactor request).
Signs: describes new functionality, mentions new API endpoints, new domain entity, new business process.

---

## Orchestration Protocol

Execute these phases **in strict order**. Do NOT skip phases. Do NOT start the next phase until the current one is complete.

---

### PHASE 1 — Requirements Breakdown

**Goal:** Turn the raw requirement into a structured, unambiguous spec.

Actions:
1. Read the user's requirement carefully.
2. Produce a **Feature Spec** with these sections:
   - **Summary**: one-paragraph description of what this feature does and why.
   - **Scope**: what is IN scope and explicitly what is OUT of scope.
   - **User Stories**: `As a [role], I want [action], so that [value].` (2-5 stories)
   - **Acceptance Criteria**: numbered list of testable conditions that define "done".
   - **Open Questions**: anything ambiguous that needs user confirmation before design.
3. **STOP and show the Feature Spec to the user.**
4. Ask the user: *"Does this spec match your intent? Any corrections before I design?"*
5. Incorporate user feedback. Do not proceed to Phase 2 until the spec is confirmed.

---

### PHASE 2 — Design

**Goal:** Define the architecture before writing any code.

Actions:
1. Load `spring-boot/SKILL.md` for architecture patterns.
2. Load `web-infra/SKILL.md` and answer the cross-cutting checklist (Section 4) before designing endpoints.
3. Produce a **Design Document** with these sections:
   - **API Contract**: list every endpoint (method, path, request body, response, status codes, error cases).
   - **Data Model**: new/changed entities, fields, relationships, constraints.
   - **Package Structure**: which packages/classes will be created or modified.
   - **Cross-Cutting Concerns**: new `ErrorCode` entries, new response helpers, new interceptors or filters needed.
   - **Key Decisions**: any non-obvious choices (e.g., sync vs async, caching strategy).
   - **Dependencies**: new libraries or infra (Redis, RocketMQ, gRPC) required.
4. **STOP and show the Design Document to the user.**
5. Ask: *"Does this design look correct? Any changes before I start coding?"*
6. Incorporate feedback. Do not proceed to Phase 3 until design is confirmed.

---

### PHASE 3 — Implementation

**Goal:** Write clean, production-quality Spring Boot code.

**Before coding:** load `coding-conventions/SKILL.md` for all project patterns (entity, mapper, service layers, DTOs, constants, Lombok). Load `web-infra/SKILL.md` for exception handling, response helpers, interceptors.

Rules:
- Build layer by layer: **Entity → Mapper → CoreService → DTOs → Service → Controller → Cross-Cutting Infra → Config**
- After each layer, verify it compiles: `./mvnw compile -q`. Fix immediately if it fails.
- Load additional sub-references only if needed:
  - `spring-boot/references/web.md` for REST controller edge cases
  - `spring-boot/references/security.md` if auth/security is involved
  - `transaction-patterns/SKILL.md` if complex multi-step writes

Implementation order:
1. Domain entity (`coding-conventions` § Entity)
2. TypeHandler if `List<String>` fields exist (`coding-conventions` § JsonListTypeHandler)
3. Mapper interface (`coding-conventions` § Data Access)
4. CoreService (`coding-conventions` § Service Layer)
5. DTOs — request/response/shared records (`coding-conventions` § DTOs)
6. Service class — business logic (`coding-conventions` § Service Layer)
7. Controller
8. Cross-cutting infra (`web-infra` § Exception Hierarchy, Response Helpers):
   - New `ErrorCode` entries + domain exception classes
   - New response helpers in `common/handler/` if needed
   - New interceptors / filters if designed in Phase 2
9. Configuration: `MetricsConfig` (TimedAspect), `WebConfig` (interceptors if any)

After all layers: `./mvnw compile -q` — must be zero errors before proceeding.

---

### PHASE 4 — Code Review

**Goal:** Self-review the implementation before handing off to tests.

Actions:
1. Load `code-quality/SKILL.md`.
2. Review every file created/modified in Phase 3 against the checklist.
3. Fix all **Critical** and **Important** issues immediately.
4. Document **Code Smells** that were either fixed or consciously accepted.
5. Output a brief review summary in the format defined by `code-quality/SKILL.md`.

Do not skip this phase. A staff engineer would not skip it.

---

### PHASE 5 — Tests

**Goal:** Prove the implementation is correct with automated tests.

Load: `spring-boot/references/testing.md`

Write tests in this order:

#### 5a. Unit Tests (Service Layer)
- For each public method in every Service class, write:
  - **Happy path**: normal input, expected output
  - **Edge cases**: boundary values, empty collections, optional absent
  - **Error paths**: invalid input, resource not found, constraint violations
- Use Mockito to mock repositories.
- File: `src/test/java/.../service/[ServiceName]Test.java`

#### 5b. Integration Tests (Controller Layer)
- For each endpoint, write `@WebMvcTest` tests:
  - **200/201**: valid request returns correct response
  - **400**: invalid/missing fields return validation errors
  - **404**: resource not found returns correct error
  - **405**: wrong HTTP method returns 405
- File: `src/test/java/.../controller/[ControllerName]Test.java`

#### 5c. Run All Tests
```bash
./mvnw test -q
```
- All tests must pass. If any fail, fix the code (not the test) unless the test itself is wrong.
- Do not mark Phase 5 complete until `BUILD SUCCESS` is confirmed.

---

### PHASE 6 — Delivery Summary

**Goal:** Give the user a clear summary of what was built and how to use it.

Produce:
1. **What was built**: brief description of each new class/file.
2. **API Reference**: copy of endpoints from Phase 2 design, updated with any changes.
3. **How to test manually**: example `curl` commands for each endpoint.
4. **Test results**: confirm `./mvnw test` passes with X tests.
5. **Update README.md**: add the new feature to the README under an appropriate section.

---

## Phase Checklist (track progress)

```
[ ] Phase 1 — Feature Spec confirmed by user
[ ] Phase 2 — Design confirmed by user
[ ] Phase 3 — Implementation complete, ./mvnw compile passes
[ ] Phase 4 — Code review done, critical issues fixed
[ ] Phase 5 — All tests pass (./mvnw test BUILD SUCCESS)
[ ] Phase 6 — Delivery summary + README updated
```

---

## Escalation Rules

- If a phase reveals a fundamental problem with the design, **go back** to the relevant earlier phase — do not patch forward.
- If a requirement is ambiguous mid-implementation, **stop and ask** — do not guess.
- If tests reveal a logic bug, **fix the implementation**, not the test assertion.
- If `./mvnw compile` or `./mvnw test` fails, **fix before proceeding** — never skip.

---

## Anti-Patterns to Avoid

- Do NOT start coding before the spec is confirmed (Phase 1 → 2 gate)
- Do NOT start coding before the design is confirmed (Phase 2 → 3 gate)
- Do NOT skip the code review phase
- Do NOT write only happy-path tests
- Do NOT mark complete without `BUILD SUCCESS`
- Do NOT introduce abstractions beyond what the spec requires
- For all coding conventions (entity, mapper, service layers, constants, Lombok, DTOs) → see `coding-conventions/SKILL.md`
