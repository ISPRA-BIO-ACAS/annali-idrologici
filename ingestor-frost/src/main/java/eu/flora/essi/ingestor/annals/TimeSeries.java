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
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.csv.CSVRecord;

public class TimeSeries extends CSVTable {

    public TimeSeries(File csvFile) throws Exception {
	loadCSVFile(csvFile);
    }

    public void loadCSVFile(File csvFile) throws Exception {
	loadCSVFile(csvFile, new String[] { "COMPARTIMENTO", "ALIAS_STAZIONE", "ALIAS_BACINO", "TIPO_GRANDEZZA", "GRANDEZZA", "VERSION" },
		new String[] { "COMPARTIMENTO", "ALIAS_BACINO", "ALIAS_STAZIONE", "GRANDEZZA" });
	Set<Entry<String, CSVRecord>> timeSeriesSet = getMap().entrySet();

	for (Entry<String, CSVRecord> ts : timeSeriesSet) {
	    CSVRecord record = ts.getValue();
	    String compartment = record.get("COMPARTIMENTO");
	    String basin = record.get("ALIAS_BACINO");
	    String station = record.get("ALIAS_STAZIONE");
	    String observedProperty = record.get("GRANDEZZA");

	    String[] stationKey = new String[] { compartment.trim(), basin.trim(), station.trim() };
	    String superKey = getSuperKey(stationKey);
	    Set<String> props = propertiesMap.get(superKey);
	    if (props == null) {
		props = new HashSet<String>();
		propertiesMap.put(superKey, props);
	    }
	    props.add(observedProperty);
	}
    }

    private HashMap<String, Set<String>> propertiesMap = new HashMap<>();

    public Set<String> getObservedProperties(String compartment, String basin, String station) {
	String[] stationKey = new String[] { compartment.trim(), basin.trim(), station.trim() };
	String superKey = getSuperKey(stationKey);
	return propertiesMap.get(superKey);
    }

    public HashMap<String, Set<String>> getPropertiesMap() {
	return propertiesMap;
    }

    public String getVersion(String compartment, String basin, String station, String observedProperty) {
	String ret = super.getRecord(new String[] { compartment.trim(), basin.trim(), station.trim(), observedProperty.trim() })
		.get("VERSION");
	return ret;
    }

}
