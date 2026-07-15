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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;

import eu.flora.essi.ingestor.sta.ObservationUploadStrategy;

/**
 * Prepare raw Annals data and map CSV files to STA folder structure.
 * Does not require a running FROST Server.
 */
public final class AnnalsPrepareAndMap {

    private static final String DUMMY_FROST_URL = "http://localhost:8080/FROST-Server/v1.1/";

    private AnnalsPrepareAndMap() {
    }

    public static void main(String[] args) throws Exception {
	Instant overallStart = Instant.now();

	String dataFolderStr = System.getenv("ANNALS_DATA_FOLDER");
	if (dataFolderStr == null || dataFolderStr.isEmpty()) {
	    dataFolderStr = "data";
	}
	Path rawDataFolder = Paths.get(dataFolderStr);
	Path processedDataFolder = rawDataFolder.resolve(AnnalsDataPreparer.PROCESSED_DIR);

	String localFolder = System.getenv("ANNALS_LOCAL_FOLDER");
	if (localFolder == null || localFolder.isEmpty()) {
	    localFolder = processedDataFolder.toString();
	}

	boolean doPrepare = getBooleanEnv("ANNALS_PREPARE", true);
	boolean prepareForce = getBooleanEnv("ANNALS_PREPARE_FORCE", false);
	boolean doMap = getBooleanEnv("ANNALS_MAP", true);
	boolean fast = getBooleanEnv("ANNALS_FAST", false);

	System.out.println("AnnalsPrepareAndMap configuration:");
	System.out.println("  ANNALS_DATA_FOLDER=" + rawDataFolder.toAbsolutePath());
	System.out.println("  ANNALS_PROCESSED_FOLDER=" + processedDataFolder.toAbsolutePath());
	System.out.println("  ANNALS_LOCAL_FOLDER=" + localFolder);
	System.out.println("  ANNALS_PREPARE=" + doPrepare);
	System.out.println("  ANNALS_PREPARE_FORCE=" + prepareForce);
	System.out.println("  ANNALS_MAP=" + doMap);
	System.out.println("  ANNALS_FAST=" + fast);

	if (!doPrepare && !doMap) {
	    System.err.println("Nothing to do: set ANNALS_PREPARE and/or ANNALS_MAP to true");
	    System.exit(1);
	}

	if (doPrepare) {
	    AnnalsIngestor.prepare(rawDataFolder.toFile(), prepareForce);
	}

	if (doMap) {
	    AnnalsIngestor ingestor = new AnnalsIngestor(
		    rawDataFolder.toFile(),
		    processedDataFolder.toFile(),
		    DUMMY_FROST_URL,
		    localFolder,
		    fast,
		    false,
		    2000,
		    ObservationUploadStrategy.DETERMINISTIC_ID,
		    false);
	    ingestor.map();
	}

	Instant overallEnd = Instant.now();
	Duration overallDuration = Duration.between(overallStart, overallEnd);
	long seconds = overallDuration.getSeconds();
	long absSeconds = Math.abs(seconds);
	String humanDuration = String.format("%d:%02d:%02d.%03d",
		absSeconds / 3600,
		(absSeconds % 3600) / 60,
		absSeconds % 60,
		overallDuration.toMillisPart());
	System.out.println("=== Annals prepare/map ended at " + overallEnd + " ===");
	System.out.println("=== Annals prepare/map duration: " + humanDuration + " (HH:mm:ss.SSS) ===");
    }

    private static boolean getBooleanEnv(String name, boolean defaultValue) {
	String val = System.getenv(name);
	if (val == null || val.isEmpty()) {
	    return defaultValue;
	}
	val = val.trim().toLowerCase();
	return val.equals("true") || val.equals("1") || val.equals("yes") || val.equals("y");
    }
}
