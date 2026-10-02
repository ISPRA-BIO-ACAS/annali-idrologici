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

import org.apache.commons.csv.CSVRecord;

/**
 * Yearbook instrument table ({@code STRUMENTO.csv}): one row per {@code TIPO_STRUMENTO_ANNALE}.
 */
public class AnnalInstruments extends CSVTable {

    public AnnalInstruments(File compartmentFile) throws Exception {
	super(compartmentFile,
		new String[] { "SIGLA_STRUMENTO", "TIPO_STRUMENTO_ANNALE", "Descrizione_TIPO_STRUMENTO_ANNALE" },
		"TIPO_STRUMENTO_ANNALE");
    }

    public String getDescription(String annalInstrumentType) {
	CSVRecord record = super.getRecord(annalInstrumentType);
	if (record == null) {
	    return null;
	}
	return record.get("Descrizione_TIPO_STRUMENTO_ANNALE");
    }

    public String getInstrumentClass(String annalInstrumentType) {
	CSVRecord record = super.getRecord(annalInstrumentType);
	if (record == null) {
	    return null;
	}
	return record.get("SIGLA_STRUMENTO");
    }

}
