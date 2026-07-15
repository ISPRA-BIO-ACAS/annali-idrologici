package eu.flora.essi.frost;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Example demonstrating filtering capabilities of FROST Server.
 * 
 * Based on: https://fraunhoferiosb.github.io/FROST-Server/sensorthingsapi/requestingData/STA-Filtering.html
 */
public class FROSTClientFilterExample {

    public static void main(String[] args) {
	try {
	    FROSTClient client = new FROSTClient("http://localhost:8080/FROST-Server/v1.1/");

	    // Example 1: Filter Things by property value (string)
	    System.out.println("=== Example 1: Filter Things by property value (string) ===");
	    PagedResult<Thing> thingsWithOven = client.getThingsByProperty("oven", "true");
	    System.out.println("Things with oven=true: " + thingsWithOven.getItems().size());
	    for (Thing thing : thingsWithOven.getItems()) {
		System.out.println("  - " + thing.getName() + " (ID: " + thing.getId() + ")");
	    }

	    // Example 2: Filter Things by property value (numeric)
	    System.out.println("\n=== Example 2: Filter Things by property value (numeric) ===");
	    PagedResult<Thing> thingsWithHeatingPlates = client.getThingsByProperty("heatingPlates", 4);
	    System.out.println("Things with heatingPlates=4: " + thingsWithHeatingPlates.getItems().size());
	    for (Thing thing : thingsWithHeatingPlates.getItems()) {
		System.out.println("  - " + thing.getName() + " (ID: " + thing.getId() + ")");
	    }

	    // Example 3: Filter Things by property value (boolean)
	    System.out.println("\n=== Example 3: Filter Things by property value (boolean) ===");
	    PagedResult<Thing> thingsWithOvenBoolean = client.getThingsByProperty("oven", true);
	    System.out.println("Things with oven=true: " + thingsWithOvenBoolean.getItems().size());

	    // Example 4: Filter Things by property value in a list
	    System.out.println("\n=== Example 4: Filter Things by property value in a list ===");
	    PagedResult<Thing> thingsInCountries = client.getThingsByPropertyIn("countryCode", "IT", "NL", "FR");
	    System.out.println("Things in IT, NL, or FR: " + thingsInCountries.getItems().size());

	    // Example 5: Using FilterBuilder for complex filters
	    System.out.println("\n=== Example 5: Complex filter using FilterBuilder ===");
	    String complexFilter = FilterBuilder.and(
		    FilterBuilder.propertyEquals("oven", true),
		    FilterBuilder.propertyGreaterThan("heatingPlates", 2));
	    PagedResult<Thing> complexResult = client.getThings(complexFilter);
	    System.out.println("Things with oven=true AND heatingPlates>2: " + complexResult.getItems().size());

	    // Example 6: Filter by name
	    System.out.println("\n=== Example 6: Filter Things by name ===");
	    PagedResult<Thing> kitchenThings = client.getThings(FilterBuilder.nameStartsWith("Kitchen"));
	    System.out.println("Things with name starting with 'Kitchen': " + kitchenThings.getItems().size());

	    // Example 7: Filter Observations by result value
	    System.out.println("\n=== Example 7: Filter Observations by result value ===");
	    PagedResult<Observation> highObservations = client
		    .getObservations(FilterBuilder.resultGreaterThan(5));
	    System.out.println("Observations with result > 5: " + highObservations.getItems().size());

	    // Example 8: Filter Observations by time range
	    System.out.println("\n=== Example 8: Filter Observations by time range ===");
	    OffsetDateTime startTime = OffsetDateTime.now().minusDays(7);
	    OffsetDateTime endTime = OffsetDateTime.now();
	    String timeFilter = FilterBuilder.and(
		    FilterBuilder.phenomenonTimeGreaterThanOrEqual(startTime),
		    FilterBuilder.phenomenonTimeLessThanOrEqual(endTime));
	    PagedResult<Observation> recentObservations = client.getObservations(timeFilter);
	    System.out.println("Observations in last 7 days: " + recentObservations.getItems().size());

	    // Example 9: Pagination with filters
	    System.out.println("\n=== Example 9: Pagination with filters ===");
	    PagedResult<Thing> page = client.getThingsByProperty("oven", true);
	    int totalCount = 0;
	    while (page != null) {
		List<Thing> items = page.getItems();
		totalCount += items.size();
		System.out.println("  Page: " + items.size() + " items (Total so far: " + totalCount + ")");
		if (!page.hasNext()) {
		    break;
		}
		page = client.getThingsByNextLink(page.getNextLink());
	    }
	    System.out.println("Total items across all pages: " + totalCount);

	} catch (Exception e) {
	    System.err.println("Error: " + e.getMessage());
	    e.printStackTrace();
	}
    }
}

