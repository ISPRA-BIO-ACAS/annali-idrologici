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

public class ObservedProperties extends CSVTable {

    public ObservedProperties(File compartmentFile) throws Exception {
	super(compartmentFile, new String[] { "SIGLA_STRUMENTO", "TIPO_GRANDEZZA", "SAMPLE_MEDIUM", "GRANDEZZA", "INTERPOLATION_TYPE",
		"AGGREGATION_PERIOD", "DESCRIZIONE_GRANDEZZA" }, "GRANDEZZA");
    }

    public String getInstrumentClass(String code) {
	return super.getRecord(code).get("SIGLA_STRUMENTO");
    }

    public String getObservedPropertyURI(String code) {
	return super.getRecord(code).get("TIPO_GRANDEZZA");
    }

    public String getSampleMedium(String code) {
	return super.getRecord(code).get("SAMPLE_MEDIUM");
    }

    public String getInterpolationType(String code) {
	return super.getRecord(code).get("INTERPOLATION_TYPE");
    }

    public String getAggregationPeriod(String code) {
	return super.getRecord(code).get("AGGREGATION_PERIOD");
    }

    public String getObservedPropertyDescription(String code) {
	return super.getRecord(code).get("DESCRIZIONE_GRANDEZZA");
    }

}
