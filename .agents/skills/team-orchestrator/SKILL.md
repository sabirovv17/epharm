---
name: team-orchestrator
description: 'Coordinate substantial or high-risk software work with parallel, bounded specialist subagents and one integration owner. Use for multi-module features, complex bugs, architecture/release work, or explicit agent-team requests; skip trivial or inseparable tasks.'
---

# Team Orchestrator

Run a small, accountable team—not a fixed cast of simulated employees. Roles are perspectives assigned to capable agents, not credentials or independent approval authority.

## Kickoff

First identify the requested outcome, acceptance criteria, relevant project contracts, risks, and authorized actions. Check available collaboration slots and the shared working tree. At the start of an eligible task, delegate **two independent, useful workstreams** if available; add a third only when its distinct output outweighs coordination and token cost. Keep sequential or tiny tasks with the coordinator. An explicit request for several agents favors delegation, but do not invent redundant work merely to fill seats.

Select roles by the task, rather than spawning every role automatically:

- **Product/PM:** clarify user-visible outcome, acceptance criteria, edge cases and unresolved decisions; read-only, never silently decide scope for the user.
- **Senior engineer/architect:** inspect interfaces, implementation options, migration/security risks; may implement an owned component when boundaries are clear.
- **Implementer:** own one bounded code area or test suite. Multiple implementers must have non-overlapping write scopes.
- **QA/reviewer/SRE:** independently challenge the design or verify changed behavior, tests, rollout and rollback evidence. Can start early by identifying coverage gaps, then review the completed diff.

For each subagent, send a bounded contract: objective; relevant files or where to find them; allowed read/write scope; concrete deliverable and acceptance evidence; dependencies; risks; and when to stop/escalate. Give only the context it needs. In one shared checkout, never assign overlapping files or mutable resources to concurrent writers. Use read-only reviewers when ownership cannot be separated. Do not create separate user-visible Codex tasks to represent these subagents.

## Integration

The coordinator owns shared decisions, interface reconciliation, final diff, tests, and the user-facing result. Continue useful local work while agents run. Compare findings against repository and test evidence; a subagent's confident report is not proof. Resolve conflicting conclusions, fix integration failures, and run proportional cross-component checks after branches converge. Reuse an existing agent for follow-up instead of spawning a duplicate when practical.

Report which workstreams contributed and what was verified, without implying that role labels equal human review. Delegation does not grant permission to deploy, merge, push, access secrets, or take other external actions. If collaboration tools are unavailable or no independent workstream exists, proceed with one agent and say so briefly; the skill cannot change host-level concurrency settings.
