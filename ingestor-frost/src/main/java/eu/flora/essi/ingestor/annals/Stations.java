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
import java.math.BigDecimal;

import org.apache.commons.csv.CSVRecord;

public class Stations extends CSVTable {

    public static final String X_LONG = "X_LONG";
    public static final String Y_LAT = "Y_LAT";
    public static final String Z_MSLM = "Z_MSLM";
    private static final BigDecimal MISSING_ELEVATION = new BigDecimal("-9999");

    public Stations(File csvFile) throws Exception {
	loadCSVFile(csvFile);
    }

    public void loadCSVFile(File csvFile) throws Exception {
	loadCSVFile(csvFile, new String[] { "COMPARTIMENTO", "ALIAS_STAZIONE", "ALIAS_BACINO", X_LONG, Y_LAT, Z_MSLM },
		new String[] { "COMPARTIMENTO", "ALIAS_BACINO", "ALIAS_STAZIONE" });
    }

    public BigDecimal getXLong(String compartment, String basin, String station) {
	return parseDecimal(stationRecord(compartment, basin, station).get(X_LONG));
    }

    public BigDecimal getYLat(String compartment, String basin, String station) {
	return parseDecimal(stationRecord(compartment, basin, station).get(Y_LAT));
    }

    public BigDecimal getZmslm(String compartment, String basin, String station) {
	BigDecimal elevation = parseDecimal(stationRecord(compartment, basin, station).get(Z_MSLM));
	if (elevation != null && elevation.compareTo(MISSING_ELEVATION) == 0) {
	    return null;
	}
	return elevation;
    }

    private CSVRecord stationRecord(String compartment, String basin, String station) {
	return super.getRecord(new String[] { compartment.trim(), basin.trim(), station.trim() });
    }

    private static BigDecimal parseDecimal(String raw) {
	if (raw == null) {
	    return null;
	}
	String value = raw.trim().replace(",", ".");
	if (value.isEmpty()) {
	    return null;
	}
	return new BigDecimal(value);
    }

}
