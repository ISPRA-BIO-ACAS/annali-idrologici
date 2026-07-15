package eu.flora.essi.ingestor.annals;

import java.io.File;
import java.util.HashMap;

import org.apache.commons.csv.CSVRecord;

public class QualityFlags extends CSVTable {

    public QualityFlags(File compartmentFile) throws Exception {
	super(compartmentFile, new String[] { "SIGLA_STRUMENTO", "TIPO_VALORE", "FLAG_VALORE", "DESCRIZIONE_FLAG" },
		new String[] { "TIPO_VALORE", "FLAG_VALORE" });
	for (String key : getMap().keySet()) {
	    CSVRecord record = getMap().get(key);
	    String type = record.get("TIPO_VALORE");
	    String flag = record.get("FLAG_VALORE");
	    String description = record.get("DESCRIZIONE_FLAG");
	    String desc = descriptions.get(flag);
	    String composed = type + ": " + description;
	    if (desc == null) {
		desc = composed;
	    } else {
		desc = desc + "; " + composed;
	    }

	    descriptions.put(flag, desc);
	}
    }

    private HashMap<String, String> descriptions = new HashMap<>();

    /**
     * @param code such as P00
     * @return
     */
    public String getFlagDescription(String code) {
	String ret = descriptions.get(code);
	if (ret == null) {
	    return null;
	}
	return ret;
    }

}
