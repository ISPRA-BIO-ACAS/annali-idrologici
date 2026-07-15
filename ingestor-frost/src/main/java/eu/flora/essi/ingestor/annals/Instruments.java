package eu.flora.essi.ingestor.annals;

import java.io.File;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.csv.CSVRecord;

public class Instruments extends CSVTable {

    public Instruments(File compartmentFile) throws Exception {
	super(compartmentFile,
		new String[] { "SIGLA_STRUMENTO", "CATEGORIA_STRUMENTO", "TIPO_STRUMENTO_ANNALE", "DESCRIZIONE_TIPO_STRUMENTO_ANNALE" },
		"TIPO_STRUMENTO_ANNALE");
    }

    public String getDescription(String annalInstrumentType) {
	return super.getRecord(annalInstrumentType).get("Descrizione_TIPO_STRUMENTO_ANNALE");
    }

    public String getClassLabel(String annalInstrumentType) {
	return super.getRecord(annalInstrumentType).get("CATEGORIA_STRUMENTO");
    }

    public String getClass(String annalInstrumentType) {
	return super.getRecord(annalInstrumentType).get("SIGLA_STRUMENTO");
    }

    public HashSet<String> getClasses() {
	HashSet<String> ret = new HashSet<>();
	Set<Entry<String, CSVRecord>> entries = super.getMap().entrySet();
	for (Entry<String, CSVRecord> entry : entries) {
	    CSVRecord record = entry.getValue();
	    String clazz = record.get("SIGLA_STRUMENTO");
	    ret.add(clazz);
	}
	return ret;
    }

    public String getClassLabelByClass(String instrumentClazz) {
	Set<Entry<String, CSVRecord>> entries = super.getMap().entrySet();
	for (Entry<String, CSVRecord> entry : entries) {
	    CSVRecord record = entry.getValue();
	    String clazz = record.get("SIGLA_STRUMENTO");
	    String category = record.get("CATEGORIA_STRUMENTO");
	    if (clazz.equals(instrumentClazz)) {
		return category;
	    }
	}
	return null;
    }

}
