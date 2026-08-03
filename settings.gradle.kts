rootProject.name = "jethro"

include(
    "common-domain",
    "common-messaging",
    "trading-core:market-data",
    "trading-core:algo-engine",
    "trading-core:risk-pnl",
    "trading-core:runtime",
    "modules:order",
    "modules:reference-data",
    "modules:ui-gateway",
    "modules:finops",
    "app",
    // muni-world — independent subproject (own jar, ADR flow, README, UI); reuses the shared
    // common-* libraries but is not depended on by `app`.
    "muni-world",
)
