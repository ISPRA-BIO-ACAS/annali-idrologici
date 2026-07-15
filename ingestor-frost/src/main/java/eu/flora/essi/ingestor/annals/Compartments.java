package eu.flora.essi.ingestor.annals;

import java.io.File;

public class Compartments extends CSVTable {

    public Compartments(File compartmentFile) throws Exception {
	super(compartmentFile, new String[] { "COMPARTIMENTO", "NOME_COMPARTIMENTO" }, "COMPARTIMENTO");
    }

    public String getName(String code) {
	return super.getRecord(code).get("NOME_COMPARTIMENTO");
    }

}
