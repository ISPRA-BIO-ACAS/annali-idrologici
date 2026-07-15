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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;

/**
 * Example demonstrating the advanced entity creation feature of FROST Server.
 * This example creates a Thing with its Location and multiple Datastreams
 * (each with their own ObservedProperty) in a single POST request.
 * Based on: https://fraunhoferiosb.github.io/FROST-Server/sensorthingsapi/deploy/5_CreatingEntitiesAdvanced.html
 */
public class FROSTClientAdvancedExample {

    public static void main(String[] args) {
	try {
	    // Initialize the FROST client
	    FROSTClient client = new FROSTClient("http://localhost:8080/FROST-Server/v1.1/");

	    // First, we need to create a Sensor (it will be referenced by ID in the Datastreams)
	    Sensor sensor = new Sensor("HDT22", "A cheap sensor that measures Temperature and Humidity", "application/pdf",
		    "https://www.sparkfun.com/datasheets/Sensors/Temperature/DHT22.pdf");
	    client.postSensor(sensor);

	    // Get the created sensor ID (in a real scenario, you'd need to query for it)
	    // For this example, we'll assume sensor ID is 5 (as in the documentation example)
	    Long sensorId = 5L;

	    // Create the Thing
	    Thing thing = new Thing("Kitchen", "The Kitchen in my house");
	    JSONObject properties = new JSONObject();
	    properties.put("oven", true);
	    properties.put("heatingPlates", 4);
	    thing.setProperties(properties);

	    // Create the Location
	    Location location = new Location("Location of the kitchen", "This is where the kitchen is", new BigDecimal("8.438889"),
		    new BigDecimal("44.27253"), null);
	    List<Location> locations = new ArrayList<>();
	    locations.add(location);

	    // Create the first Datastream for Temperature
	    Datastream.UnitOfMeasurement tempUOM = new Datastream.UnitOfMeasurement("Degree Celsius", "°C", "ucum:Cel");
	    Datastream tempDatastream = new Datastream("Temperature in the Kitchen",
		    "The temperature in the kitchen, measured by the sensor next to the window",
		    "http://www.opengis.net/def/observationType/OGC-OM/2.0/OM_Measurement", tempUOM);
	    tempDatastream.setSensorId(sensorId);

	    // Create ObservedProperty for Temperature (nested in Datastream)
	    ObservedProperty tempObservedProperty = new ObservedProperty("Temperature", "Temperature",
		    "http://dd.eionet.europa.eu/vocabularyconcept/aq/meteoparameter/54");
	    tempDatastream.setObservedProperty(tempObservedProperty);

	    // Create the second Datastream for Humidity
	    Datastream.UnitOfMeasurement humidityUOM = new Datastream.UnitOfMeasurement("Percent", "%", "ucum:%");
	    Datastream humidityDatastream = new Datastream("Humidity in the Kitchen",
		    "The relative humidity in the kitchen, measured by the sensor next to the window",
		    "http://www.opengis.net/def/observationType/OGC-OM/2.0/OM_Measurement", humidityUOM);
	    humidityDatastream.setSensorId(sensorId);

	    // Create ObservedProperty for Humidity (nested in Datastream)
	    ObservedProperty humidityObservedProperty = new ObservedProperty("Relative humidity", "Relative humidity",
		    "http://dd.eionet.europa.eu/vocabularyconcept/aq/meteoparameter/58");
	    humidityDatastream.setObservedProperty(humidityObservedProperty);

	    // Add both Datastreams to the list
	    List<Datastream> datastreams = new ArrayList<>();
	    datastreams.add(tempDatastream);
	    datastreams.add(humidityDatastream);

	    // Create everything in one POST request!
	    System.out.println("Creating Thing with Location and Datastreams in a single POST request...");
	    client.postThingWithRelations(thing, locations, datastreams);
	    System.out.println("Successfully created Thing with all related entities!");

	    // Verify by retrieving the created Thing
	    System.out.println("\nRetrieving created Things to verify...");
	    PagedResult<Thing> thingsPage = client.getThings();
	    while (thingsPage != null) {
		for (Thing t : thingsPage.getItems()) {
		    if ("Kitchen".equals(t.getName())) {
			System.out.println("Found created Thing: " + t.getName() + " (ID: " + t.getId() + ")");
		    }
		}
		if (!thingsPage.hasNext()) {
		    break;
		}
		thingsPage = client.getThingsByNextLink(thingsPage.getNextLink());
	    }

	} catch (Exception e) {
	    System.err.println("Error: " + e.getMessage());
	    e.printStackTrace();
	}
    }
}
