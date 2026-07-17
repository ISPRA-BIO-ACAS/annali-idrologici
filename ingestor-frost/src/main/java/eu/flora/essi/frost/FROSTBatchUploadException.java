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
package eu.flora.essi.frost;

import java.io.IOException;
import java.io.PrintStream;

/**
 * Fatal batch upload failure: FROST did not create all observations in a batch.
 * The ingestor must stop immediately when this is thrown.
 */
public class FROSTBatchUploadException extends IOException {

    private final String datastreamLabel;
    private final Long datastreamId;
    private final String batchFileName;
    private final long insertedCount;
    private final int expectedCount;

    public FROSTBatchUploadException(String datastreamLabel, Long datastreamId, String batchFileName,
            long insertedCount, int expectedCount) {
        super(formatMessage(datastreamLabel, datastreamId, batchFileName, insertedCount, expectedCount));
        this.datastreamLabel = datastreamLabel;
        this.datastreamId = datastreamId;
        this.batchFileName = batchFileName;
        this.insertedCount = insertedCount;
        this.expectedCount = expectedCount;
    }

    public String getDatastreamLabel() {
        return datastreamLabel;
    }

    public Long getDatastreamId() {
        return datastreamId;
    }

    public String getBatchFileName() {
        return batchFileName;
    }

    public long getInsertedCount() {
        return insertedCount;
    }

    public int getExpectedCount() {
        return expectedCount;
    }

    public void printFatalBanner(PrintStream out) {
        out.println();
        out.println("================================================================================");
        out.println(" FATAL: FROST batch upload incomplete — stopping ingestion");
        out.println("================================================================================");
        out.println(" Datastream : " + datastreamLabel + (datastreamId != null ? " (id " + datastreamId + ")" : ""));
        out.println(" Batch file : " + batchFileName);
        out.println(" Inserted   : " + insertedCount + " / " + expectedCount + " observations");
        out.println();
        out.println(" FROST accepted the batch (HTTP 200) but not all observations were persisted.");
        out.println(" Check the FROST server logs for messages such as:");
        out.println("   JsonBatchProcessor - Failed to parse json");
        out.println();
        out.println(" To increase FROST logging, restart the FROST stack with e.g.:");
        out.println("   FROST_LL=DEBUG FROST_LL_service=DEBUG docker compose -f docker-compose-frost.yml up -d");
        out.println();
        out.println("================================================================================");
        out.println();
    }

    private static String formatMessage(String datastreamLabel, Long datastreamId, String batchFileName,
            long insertedCount, int expectedCount) {
        return "Batch upload incomplete for " + datastreamLabel
                + (datastreamId != null ? " (datastream " + datastreamId + ")" : "")
                + ", file " + batchFileName + ": inserted " + insertedCount + " of " + expectedCount
                + " observations";
    }
}
