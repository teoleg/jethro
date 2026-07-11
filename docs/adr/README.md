# Architecture Decision Records

Index of ADRs for the Jethro trading platform. See [template.md](template.md) for the format.

| # | Title | Status |
|---|-------|--------|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0002](0002-backend-language-java-with-cpp-hot-paths.md) | Backend in Java 21, C++ reserved for latency-critical hot paths | Proposed |
| [0003](0003-event-driven-service-architecture.md) | Event-driven services around a streaming backbone | Proposed |
| [0004](0004-messaging-backbone-kafka.md) | Kafka (Amazon MSK) as the messaging backbone | Proposed |
| [0005](0005-data-storage.md) | Aurora PostgreSQL for state, Kafka + S3 for tick history | Proposed |
| [0006](0006-ui-stack.md) | UI in TypeScript + React (Vite), streaming over WebSocket | Proposed |
| [0007](0007-aws-runtime-ecs-fargate-cdk.md) | Runtime on ECS Fargate, infrastructure as code with AWS CDK (Java) | Proposed |
| [0008](0008-domain-model-books-instruments-positions.md) | Domain model: books, instruments, positions, marks | Proposed |
| [0009](0009-market-data-provider-abstraction.md) | Market data provider abstraction | Proposed |

## Lifecycle

`Proposed` → `Accepted` (or `Rejected`) → possibly `Superseded by ADR-XXXX`.

An ADR is **Proposed** when written, **Accepted** once the owner (Oleg) signs off.
Never edit the decision of an Accepted ADR — write a new ADR that supersedes it.
