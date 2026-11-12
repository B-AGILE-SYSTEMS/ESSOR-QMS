# Everything Claude Code (ECC) — sélection intégrée

Source : https://github.com/affaan-m/everything-claude-code, commit d2ef268, licence MIT (voir ECC-LICENSE).
Sélection limitée à notre stack (Java 21 / Spring Boot, PostgreSQL, Angular, Flutter, Docker). Les hooks ECC ne sont pas installés : ils exécutent du code à chaque action.

- Agents (.claude/agents) : java-reviewer, java-build-resolver, typescript-reviewer, flutter-reviewer, dart-build-resolver, database-reviewer, security-reviewer, silent-failure-hunter, e2e-runner, planner, architect, build-error-resolver.
- Skills (.claude/skills) : springboot-*, java-coding-standards, jpa-patterns, postgres-patterns, angular-developer, typescript-patterns, dart-flutter-patterns, flutter-dart-code-review, api-design, docker-patterns, e2e-testing, tdd-workflow, verification-loop, security-review.
- Règles (.claude/rules) : common, java, angular, dart, typescript, web.

En cas de conflit, les règles du projet (CLAUDE.md, AGENTS.md, contexte-global.md) priment sur ECC.
