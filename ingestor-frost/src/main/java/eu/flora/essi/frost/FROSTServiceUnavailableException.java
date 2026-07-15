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

/**
 * Thrown when the FROST Server cannot be reached or is not accepting requests.
 */
public class FROSTServiceUnavailableException extends IOException {

    private final String serviceUrl;

    public FROSTServiceUnavailableException(String serviceUrl, Throwable cause) {
        super(formatMessage(serviceUrl, cause != null ? cause.getMessage() : null), cause);
        this.serviceUrl = serviceUrl;
    }

    public FROSTServiceUnavailableException(String serviceUrl, String detail) {
        super(formatMessage(serviceUrl, detail));
        this.serviceUrl = serviceUrl;
    }

    public String getServiceUrl() {
        return serviceUrl;
    }

    private static String formatMessage(String serviceUrl, String detail) {
        StringBuilder message = new StringBuilder("FROST server is unavailable at ");
        message.append(serviceUrl);
        if (detail != null && !detail.isEmpty()) {
            message.append(": ").append(detail);
        }
        return message.toString();
    }
}
