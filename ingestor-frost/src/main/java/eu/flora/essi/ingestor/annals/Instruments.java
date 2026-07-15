/*
 * Ingestor
 * Copyright (C) 2026 National Research Council of Italy (CNR)/Institute of Technologies and Environmental Intelligence (ITIAm)/ESSI-Lab
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
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
