package eu.flora.essi.ingestor.annals;

import java.io.File;

public class ObservedProperties extends CSVTable {

    public ObservedProperties(File compartmentFile) throws Exception {
	super(compartmentFile, new String[] { "SIGLA_STRUMENTO", "TIPO_GRANDEZZA", "SAMPLE_MEDIUM", "GRANDEZZA", "INTERPOLATION_TYPE",
		"AGGREGATION_PERIOD", "DESCRIZIONE_GRANDEZZA" }, "GRANDEZZA");
    }

    public String getInstrumentClass(String code) {
	return super.getRecord(code).get("SIGLA_STRUMENTO");
    }

    public String getObservedPropertyURI(String code) {
	return super.getRecord(code).get("TIPO_GRANDEZZA");
    }

    public String getSampleMedium(String code) {
	return super.getRecord(code).get("SAMPLE_MEDIUM");
    }

    public String getInterpolationType(String code) {
	return super.getRecord(code).get("INTERPOLATION_TYPE");
    }

    public String getAggregationPeriod(String code) {
	return super.getRecord(code).get("AGGREGATION_PERIOD");
    }

    public String getObservedPropertyDescription(String code) {
	return super.getRecord(code).get("DESCRIZIONE_GRANDEZZA");
    }

}
