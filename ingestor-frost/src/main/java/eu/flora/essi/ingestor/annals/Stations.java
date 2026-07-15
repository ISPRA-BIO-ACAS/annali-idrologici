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

    public Stations(File csvFile) throws Exception {
	loadCSVFile(csvFile);
    }

    public void loadCSVFile(File csvFile) throws Exception {
	loadCSVFile(csvFile, new String[] { "Compartimento", "ALIAS_STAZIONE", "ALIAS_BACINO", X_LONG, Y_LAT, Z_MSLM },
		new String[] { "Compartimento", "ALIAS_BACINO", "ALIAS_STAZIONE" });
    }

    public BigDecimal getXLong(String compartment, String basin, String station) {
	String ret = super.getRecord(new String[] { compartment.trim(), basin.trim(), station.trim() }).get("X_LONG");
	ret = ret.replace(",", ".");
	return new BigDecimal(ret);
    }

    public BigDecimal getYLat(String compartment, String basin, String station) {
	CSVRecord record = super.getRecord(new String[] { compartment.trim(), basin.trim(), station.trim() });
	String ret = record.get("Y_LAT");
	ret = ret.replace(",", ".");
	return new BigDecimal(ret);
    }

    public BigDecimal getZmslm(String compartment, String basin, String station) {
	String ret = super.getRecord(new String[] { compartment.trim(), basin.trim(), station.trim() }).get("Z_MSLM");
	ret = ret.replace(",", ".");
	return new BigDecimal(ret);
    }

}
