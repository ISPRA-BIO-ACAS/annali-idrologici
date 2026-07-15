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
import java.util.Set;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

public class CSVTable {

    private HashMap<String, CSVRecord> map = new HashMap<String, CSVRecord>();
    private String[] keys;

    public HashMap<String, CSVRecord> getMap() {
	return map;
    }

    public Set<String> getKeys() {
	return map.keySet();
    }

    public CSVTable() {

    }

    public CSVTable(File csvFile, String[] fields, String key) throws Exception {
	this(csvFile, fields, new String[] { key });
    }

    public CSVTable(File csvFile, String[] fields, String[] keys) throws Exception {
	if (csvFile != null && csvFile.exists()) {
	    loadCSVFile(csvFile, fields, keys);
	}
    }

    public void loadCSVFile(File csvFile, String[] fields, String[] keys) throws Exception {
	if (csvFile == null) {
	    return;
	}
	System.out.println("Loading table " + csvFile.getName() + " in memory");
	this.keys = keys;
	try (Reader in = new FileReader(csvFile)) {
	    Iterable<CSVRecord> records = CSVFormat.DEFAULT.withFirstRecordAsHeader().parse(in);
	    int total = 0;
	    for (CSVRecord record : records) {
		String superKey = "";
		for (int i = 0; i < keys.length; i++) {
		    String key = keys[i].trim();
		    String value = record.get(key);
		    if (value == null || value.isEmpty()) {
			throw new IllegalArgumentException("key field not found: " + key);
		    }
		    superKey = superKey + value.trim() + "-";
		}
		superKey = superKey.substring(0, superKey.length() - 1);
		map.put(superKey, record);
		total++;
	    }
	    if (total == 0) {
		throw new IllegalArgumentException("error reading file" + csvFile.getName());
	    }
	    System.out.println("Loaded " + csvFile.getName() + ", total records: " + total);
	}

    }

    public CSVRecord getRecord(String key) {
	return getRecord(new String[] { key });
    }

    public CSVRecord getRecord(String[] keys) {
	String superKey = getSuperKey(keys);
	return map.get(superKey);
    }

    public String getSuperKey(String[] keys) {
	String ret = "";
	for (int i = 0; i < keys.length; i++) {
	    String key = keys[i];
	    ret += key + "-";
	}
	ret = ret.substring(0, ret.length() - 1);
	return ret;
    }
}
