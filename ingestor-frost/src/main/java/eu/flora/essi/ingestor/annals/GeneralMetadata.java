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
import java.io.FileReader;
import java.io.Reader;
import java.util.HashMap;
import java.util.Iterator;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

public class GeneralMetadata {

    private HashMap<String, String> map = new HashMap<String, String>();

    public GeneralMetadata(File csvFile) throws Exception {
	System.out.println("Loading table " + csvFile.getName() + " in memory");
	try (Reader in = new FileReader(csvFile)) {
	    Iterable<CSVRecord> records = CSVFormat.DEFAULT.parse(in);
	    Iterator<CSVRecord> iterator = records.iterator();
	    CSVRecord header = iterator.next();
	    CSVRecord values = iterator.next();
	    int total = 0;
	    for (int i = 0; i < header.size(); i++) {
		String h = header.get(i);
		if (h == null || h.isEmpty()) {
		    throw new IllegalArgumentException("invalid header");
		}
		String v = values.get(i);
		if (v == null || v.isEmpty()) {
		    throw new IllegalArgumentException("invalid value");
		}
		map.put(h, v);
		total++;
	    }

	    System.out.println("Loaded " + csvFile.getName() + ", total metadata: " + total);
	}
    }

    public String getTerritoryName() {
	return map.get("TerritoryName");
    }

    public String getTimeZone() {
	return map.get("TimeZone");
    }

    public String getApplicationArea() {
	return map.get("ApplicationArea");
    }

    public String getDomain() {
	return map.get("Domain");
    }

    public String getLevelOfData() {
	return map.get("LevelOfData");
    }

    public String getDataPolicy() {
	return map.get("DataPolicy");
    }

    public String getLicence() {
	return map.get("licence");
    }

    public String getDisclaimer() {
	return map.get("Disclaimer");
    }

    public String getDataSource() {
	return map.get("DataSource");
    }

    public String getFunding() {
	return map.get("Funding");
    }

    public String getReferenceTitle() {
	String ret = map.get("Referece_Title");
	if (ret == null) {
	    ret = map.get("Reference_Title");
	}
	return ret;
    }
    
    public String getReferenceAuthor() {
	String ret = map.get("Referece_Author");
	if (ret == null) {
	    ret = map.get("Reference_Author");
	}
	return ret;
    }

}
