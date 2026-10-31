package com.ashcc.jet;

/**
 * Blocks of a jet engine. An engine is exactly three blocks in a straight line, all facing the same way:
 * intake, core (compressor, combustor and turbine), and a nozzle (plain, or with an afterburner).
 */
public enum JetPart {
    INTAKE("intake"),
    CORE("core"),
    NOZZLE("nozzle"),
    AFTERBURNER_NOZZLE("afterburner_nozzle"),
    VECTORING_NOZZLE("vectoring_nozzle");

    private final String name;

    JetPart(String name) {
        this.name = name;
    }

    public String partName() {
        return name;
    }

    public boolean isNozzle() {
        return this == NOZZLE || this == AFTERBURNER_NOZZLE || this == VECTORING_NOZZLE;
    }
}
