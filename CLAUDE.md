# Insurance Hub — project instructions for Claude Code

This repo implements the Insurance Hub API as Spring Boot microservices:
hub-gateway (edge, crypto, routing), policy-service (01 create, 02 renew),
claims-service (03 register, 04 status), plus a tiny hub-common library.

Always use the `insurance-hub-microservices` skill in `.claude/skills/` for any work here.
Read the reference file for the current phase before writing code.

Current phase: see docs/progress.md. Work one phase at a time, and stop for review at the end of each phase.

Commands:
- Build + all tests: `mvn verify`
- One module: `mvn -pl policy-service -am verify`
- Local stack: `docker compose up -d --build`
- Send a sample request: `scripts/send-sample.sh 01`

Never commit anything in `.secrets/` or `.env`. Never log payloads or PII.
