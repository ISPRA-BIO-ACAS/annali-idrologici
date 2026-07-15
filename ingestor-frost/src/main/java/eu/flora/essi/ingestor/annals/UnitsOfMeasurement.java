package eu.flora.essi.ingestor.annals;

import java.io.File;

public class UnitsOfMeasurement extends CSVTable {

    public UnitsOfMeasurement(File compartmentFile) throws Exception {
	super(compartmentFile, new String[] { "SIGLA_STRUMENTO", "UDM", "DESCRIZIONE_UDM" }, "SIGLA_STRUMENTO");
    }

    public String getUnitsOfMeasurement(String code) {
	return super.getRecord(code).get("UDM");
    }

    public String getUnitsOfMeasurementDescription(String code) {
	return super.getRecord(code).get("DESCRIZIONE_UDM");
    }

}
