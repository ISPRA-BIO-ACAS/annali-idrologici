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
