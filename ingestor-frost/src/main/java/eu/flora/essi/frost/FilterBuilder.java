package eu.flora.essi.frost;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Utility class for building FROST Server filter expressions.
 * 
 * Based on: https://fraunhoferiosb.github.io/FROST-Server/sensorthingsapi/requestingData/STA-Filtering.html
 */
public class FilterBuilder {

    /**
     * Build a filter for property value equality.
     * Example: properties/key eq 'value'
     * 
     * @param propertyKey The key in the properties object
     * @param value The string value to match
     * @return Filter expression string
     */
    public static String propertyEquals(String propertyKey, String value) {
	return "properties/" + propertyKey + " eq " + encodeString(value);
    }

    /**
     * Build a filter for property value equality (numeric).
     * Example: properties/count eq 5
     * 
     * @param propertyKey The key in the properties object
     * @param value The numeric value to match
     * @return Filter expression string
     */
    public static String propertyEquals(String propertyKey, Number value) {
	return "properties/" + propertyKey + " eq " + value;
    }

    /**
     * Build a filter for property value equality (boolean).
     * Example: properties/active eq true
     * 
     * @param propertyKey The key in the properties object
     * @param value The boolean value to match
     * @return Filter expression string
     */
    public static String propertyEquals(String propertyKey, boolean value) {
	return "properties/" + propertyKey + " eq " + value;
    }

    /**
     * Build a filter for property value not equal.
     * 
     * @param propertyKey The key in the properties object
     * @param value The string value to exclude
     * @return Filter expression string
     */
    public static String propertyNotEquals(String propertyKey, String value) {
	return "properties/" + propertyKey + " ne " + encodeString(value);
    }

    /**
     * Build a filter for property value greater than.
     * 
     * @param propertyKey The key in the properties object
     * @param value The numeric value to compare against
     * @return Filter expression string
     */
    public static String propertyGreaterThan(String propertyKey, Number value) {
	return "properties/" + propertyKey + " gt " + value;
    }

    /**
     * Build a filter for property value greater than or equal.
     * 
     * @param propertyKey The key in the properties object
     * @param value The numeric value to compare against
     * @return Filter expression string
     */
    public static String propertyGreaterThanOrEqual(String propertyKey, Number value) {
	return "properties/" + propertyKey + " ge " + value;
    }

    /**
     * Build a filter for property value less than.
     * 
     * @param propertyKey The key in the properties object
     * @param value The numeric value to compare against
     * @return Filter expression string
     */
    public static String propertyLessThan(String propertyKey, Number value) {
	return "properties/" + propertyKey + " lt " + value;
    }

    /**
     * Build a filter for property value less than or equal.
     * 
     * @param propertyKey The key in the properties object
     * @param value The numeric value to compare against
     * @return Filter expression string
     */
    public static String propertyLessThanOrEqual(String propertyKey, Number value) {
	return "properties/" + propertyKey + " le " + value;
    }

    /**
     * Build a filter for property value in a list of values.
     * Example: properties/countryCode in ('IT','NL')
     * 
     * @param propertyKey The key in the properties object
     * @param values Array of string values
     * @return Filter expression string
     */
    public static String propertyIn(String propertyKey, String... values) {
	StringBuilder sb = new StringBuilder("properties/");
	sb.append(propertyKey).append(" in (");
	for (int i = 0; i < values.length; i++) {
	    if (i > 0) {
		sb.append(",");
	    }
	    sb.append(encodeString(values[i]));
	}
	sb.append(")");
	return sb.toString();
    }

    /**
     * Build a filter for name equality.
     * 
     * @param name The name to match
     * @return Filter expression string
     */
    public static String nameEquals(String name) {
	return "name eq " + encodeString(name);
    }

    /**
     * Build a filter for name starts with.
     * 
     * @param prefix The prefix to match
     * @return Filter expression string
     */
    public static String nameStartsWith(String prefix) {
	return "startswith(name, " + encodeString(prefix) + ")";
    }

    /**
     * Build a filter for name ends with.
     * 
     * @param suffix The suffix to match
     * @return Filter expression string
     */
    public static String nameEndsWith(String suffix) {
	return "endswith(name, " + encodeString(suffix) + ")";
    }

    /**
     * Build a filter for name contains substring.
     * 
     * @param substring The substring to find
     * @return Filter expression string
     */
    public static String nameContains(String substring) {
	return "substringof(" + encodeString(substring) + ", name)";
    }

    /**
     * Build a filter for result greater than (for Observations).
     * 
     * @param value The numeric value to compare against
     * @return Filter expression string
     */
    public static String resultGreaterThan(Number value) {
	return "result gt " + value;
    }

    /**
     * Build a filter for result less than (for Observations).
     * 
     * @param value The numeric value to compare against
     * @return Filter expression string
     */
    public static String resultLessThan(Number value) {
	return "result lt " + value;
    }

    /**
     * Build a filter for phenomenonTime greater than or equal (for Observations).
     * 
     * @param time The datetime to compare against
     * @return Filter expression string
     */
    public static String phenomenonTimeGreaterThanOrEqual(OffsetDateTime time) {
	return "phenomenonTime ge " + encodeDateTime(time);
    }

    /**
     * Build a filter for phenomenonTime less than or equal (for Observations).
     * 
     * @param time The datetime to compare against
     * @return Filter expression string
     */
    public static String phenomenonTimeLessThanOrEqual(OffsetDateTime time) {
	return "phenomenonTime le " + encodeDateTime(time);
    }

    /**
     * Combine multiple filter expressions with AND.
     * 
     * @param filters Filter expressions to combine
     * @return Combined filter expression
     */
    public static String and(String... filters) {
	return combine(" and ", filters);
    }

    /**
     * Combine multiple filter expressions with OR.
     * 
     * @param filters Filter expressions to combine
     * @return Combined filter expression
     */
    public static String or(String... filters) {
	return combine(" or ", filters);
    }

    /**
     * Negate a filter expression.
     * 
     * @param filter The filter expression to negate
     * @return Negated filter expression
     */
    public static String not(String filter) {
	return "not " + filter;
    }

    /**
     * Group a filter expression with parentheses.
     * 
     * @param filter The filter expression to group
     * @return Grouped filter expression
     */
    public static String group(String filter) {
	return "(" + filter + ")";
    }

    /**
     * Encode a string constant for use in filter expressions.
     * Strings are quoted with single quotes, and single quotes are doubled.
     * 
     * @param value The string value to encode
     * @return Encoded string constant
     */
    public static String encodeString(String value) {
	if (value == null) {
	    return "null";
	}
	// Replace single quotes with doubled single quotes
	String escaped = value.replace("'", "''");
	return "'" + escaped + "'";
    }

    /**
     * Encode a datetime constant for use in filter expressions.
     * Uses ISO8601 format with timezone.
     * 
     * @param time The datetime to encode
     * @return Encoded datetime constant (URL encoding should be applied when building the full URL)
     */
    public static String encodeDateTime(OffsetDateTime time) {
	if (time == null) {
	    return "null";
	}
	// Format as ISO8601 with timezone
	return time.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /**
     * URL encode a filter expression for use in URLs.
     * 
     * @param filter The filter expression
     * @return URL-encoded filter expression
     */
    public static String urlEncode(String filter) {
	if (filter == null) {
	    return null;
	}
	try {
	    return URLEncoder.encode(filter, StandardCharsets.UTF_8.toString());
	} catch (Exception e) {
	    throw new RuntimeException("Failed to URL encode filter: " + filter, e);
	}
    }

    private static String combine(String operator, String... filters) {
	if (filters == null || filters.length == 0) {
	    return "";
	}
	if (filters.length == 1) {
	    return filters[0];
	}
	StringBuilder sb = new StringBuilder();
	for (int i = 0; i < filters.length; i++) {
	    if (i > 0) {
		sb.append(operator);
	    }
	    sb.append(filters[i]);
	}
	return sb.toString();
    }
}



