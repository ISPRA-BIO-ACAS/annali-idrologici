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

public class UnitsOfMeasurement extends CSVTable {

    public UnitsOfMeasurement(File compartmentFile) throws Exception {
	super(compartmentFile, new String[] { "SIGLA_STRUMENTO", "UDM", "DESCRIZIONE_UDM" }, "SIGLA_STRUMENTO");
    }

    public String getUnitsOfMeasurement(String code) {
	return super.getRecord(code).get("UDM");
    }

    public String getUnitsOfMeasurementDescription(String code) {
	return super.getRecord(code).get("DESCRIZIONE_UDM");
    }

}
