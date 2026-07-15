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
