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

import org.json.JSONObject;

/**
 * Wrapper class for FROST Server Datastream entity
 */
public class Datastream {
    private JSONObject json;

    public Datastream() {
	this.json = new JSONObject();
    }

    public Datastream(JSONObject json) {
	this.json = json;
    }

    public Datastream(String name, String description, String observationType, UnitOfMeasurement unitOfMeasurement) {
	this();
	setName(name);
	setDescription(description);
	setObservationType(observationType);
	setUnitOfMeasurement(unitOfMeasurement);
    }

    public String getName() {
	return json.optString("name", null);
    }

    public void setName(String name) {
	json.put("name", name);
    }

    public String getDescription() {
	return json.optString("description", null);
    }

    public void setDescription(String description) {
	json.put("description", description);
    }

    public String getObservationType() {
	return json.optString("observationType", null);
    }

    public void setObservationType(String observationType) {
	json.put("observationType", observationType);
    }

    public UnitOfMeasurement getUnitOfMeasurement() {
	JSONObject uom = json.optJSONObject("unitOfMeasurement");
	if (uom != null) {
	    return new UnitOfMeasurement(uom);
	}
	return null;
    }

    public void setUnitOfMeasurement(UnitOfMeasurement unitOfMeasurement) {
	json.put("unitOfMeasurement", unitOfMeasurement.toJSON());
    }

    public void setThingId(Long thingId) {
	JSONObject thing = new JSONObject();
	thing.put("@iot.id", thingId);
	json.put("Thing", thing);
    }

    /** Remove Thing reference (e.g. for deep insert where server links the parent). */
    public void removeThingReference() {
	json.remove("Thing");
    }

    public void setSensorId(Long sensorId) {
	JSONObject sensor = new JSONObject();
	sensor.put("@iot.id", sensorId);
	json.put("Sensor", sensor);
    }

    public void setSensor(Sensor sensor) {
	json.put("Sensor", sensor != null ? sensor.toJSON() : new JSONObject());
    }

    public void setObservedPropertyId(Long observedPropertyId) {
	JSONObject observedProperty = new JSONObject();
	observedProperty.put("@iot.id", observedPropertyId);
	json.put("ObservedProperty", observedProperty);
    }

    public void setObservedProperty(ObservedProperty observedProperty) {
	json.put("ObservedProperty", observedProperty.toJSON());
    }

    public Long getId() {
	if (json.has("@iot.id")) {
	    return json.getLong("@iot.id");
	}
	return null;
    }

    /**
     * Get the Thing id from the Datastream's Thing reference (when present, e.g. with $expand=Thing).
     */
    public Long getThingId() {
	JSONObject thing = json.optJSONObject("Thing");
	if (thing != null && thing.has("@iot.id")) {
	    return thing.getLong("@iot.id");
	}
	return null;
    }

    public void setId(Long id) {
	json.put("@iot.id", id);
    }

    public JSONObject toJSON() {
	return json;
    }

    @Override
    public String toString() {
	return json.toString();
    }

    public JSONObject getProperties() {
	return json.optJSONObject("properties");
    }

    public void setProperties(JSONObject properties) {
	json.put("properties", properties);
    }

    /**
     * Inner class for UnitOfMeasurement
     */
    public static class UnitOfMeasurement {
	private JSONObject json;

	public UnitOfMeasurement() {
	    this.json = new JSONObject();
	}

	public UnitOfMeasurement(JSONObject json) {
	    this.json = json;
	}

	public UnitOfMeasurement(String name, String symbol) {
	    this(name, symbol, null);
	}

	public UnitOfMeasurement(String name, String symbol, String definition) {
	    this();
	    setName(name);
	    setSymbol(symbol);
	    if (definition != null) {
		setDefinition(definition);
	    }
	}

	public String getName() {
	    return json.optString("name", null);
	}

	public void setName(String name) {
	    json.put("name", name);
	}

	public String getSymbol() {
	    return json.optString("symbol", null);
	}

	public void setSymbol(String symbol) {
	    json.put("symbol", symbol);
	}

	public String getDefinition() {
	    return json.optString("definition", null);
	}

	public void setDefinition(String definition) {
	    json.put("definition", definition);
	}

	public JSONObject toJSON() {
	    return new JSONObject(json.toString());
	}
    }
}
