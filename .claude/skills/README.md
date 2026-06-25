# Skills Index

> This file is the routing table. Read this FIRST to decide which skill to load.
> Do NOT load all skills at once — only load the one that matches the task.

## Available Skills

| Skill | Path | Load When |
|-------|------|-----------|
| **feature-delivery** | `feature-delivery/SKILL.md` | User drops a large requirement and wants Claude to handle the full lifecycle (spec → design → code → review → tests) |
| **code-quality** | `code-quality/SKILL.md` | Code review, refactoring, clean code metrics, naming, API contract review |
| **design-patterns** | `design-patterns/SKILL.md` | Factory, Builder, Strategy, Observer, Decorator, DDD Lite, Value Objects |
| **jpa-patterns** | `jpa-patterns/SKILL.md` | N+1, lazy loading, entity design, DB schema, indexes, query optimization |
| **transaction-patterns** | `transaction-patterns/SKILL.md` | @Transactional rules, self-invocation trap, rollback, propagation, service layer boundaries |
| **logging-patterns** | `logging-patterns/SKILL.md` | Structured logging, SLF4J, JSON logs, MDC, log security |
| **spring-boot** | `spring-boot/SKILL.md` | REST API, Security, architecture, project structure, Spring Boot general |
| **coding-conventions** | `coding-conventions/SKILL.md` | Project-specific patterns: MyBatis mapper, service layer separation, package structure, constants, enums, DTOs, Redis scripts, Lombok |
| **web-infra** | `web-infra/SKILL.md` | Exception hierarchy, `GlobalExceptionHandler`, `ErrorCode`, response helpers, `HandlerInterceptor` vs `Filter` patterns |

## Spring Boot Sub-References

Only load these when the spring-boot skill needs deeper detail on a specific topic:

| Reference | Path | Load When |
|-----------|------|-----------|
| cloud | `spring-boot/references/cloud.md` | Config Server, Gateway, Resilience4j, service discovery |
| data | `spring-boot/references/data.md` | JPA entities, Flyway migrations, specifications, projections |
| security | `spring-boot/references/security.md` | Spring Security 6, JWT, OAuth2, method security |
| testing | `spring-boot/references/testing.md` | JUnit 5, MockMvc, Testcontainers, @DataJpaTest |
| web | `spring-boot/references/web.md` | REST controllers, WebClient, CORS, validation, exception handling |

## Routing Rules

1. User drops a large feature requirement and wants full delivery → **feature-delivery** ← START HERE
2. User asks to "review code" or "refactor" → **code-quality**
3. User asks about design patterns or DDD → **design-patterns**
4. User has JPA/DB/query issues → **jpa-patterns**
5. User is writing service logic with @Transactional → **transaction-patterns**
6. User asks about logging or debugging → **logging-patterns**
7. User is building a specific Spring Boot feature (targeted, not full lifecycle) → **spring-boot** (then load sub-reference if needed)
8. User asks about error handling, interceptors, filters, or response helpers → **web-infra**
9. User asks how to write a mapper / service / constants / package structure for this project → **coding-conventions**
10. Multiple topics overlap → load at most 2 skills, primary first

## Override Notes

`coding-conventions` **overrides** generic `spring-boot/` patterns for this project:
- Use MyBatis `@Mapper` — NOT Spring Data JPA
- `spring-boot/references/data.md` is JPA-based; ignore for data access in this project
