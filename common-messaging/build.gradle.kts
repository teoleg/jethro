plugins {
    alias(libs.plugins.avro)
}

dependencies {
    api(libs.avro)
}

avro {
    // Decimal logical types map to BigDecimal — invariant 1 (no binary FP on money)
    setEnableDecimalLogicalType(true)
}
