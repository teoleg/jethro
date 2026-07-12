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
)
