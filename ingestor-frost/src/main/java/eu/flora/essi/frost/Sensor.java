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
 * Wrapper class for FROST Server Sensor entity
 */
public class Sensor {
    private JSONObject json;

    public Sensor() {
	this.json = new JSONObject();
    }

    public Sensor(JSONObject json) {
	this.json = json;
    }

    public Sensor(String clazz, String name, String encodingType, String metadata) {
	this();
	setName(name);
	setDescription(name);
	setEncodingType(encodingType);
	setMetadata(metadata);
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

    public String getEncodingType() {
	return json.optString("encodingType", null);
    }

    public void setEncodingType(String encodingType) {
	json.put("encodingType", encodingType);
    }

    public String getMetadata() {
	return json.optString("metadata", null);
    }

    public void setMetadata(String metadata) {
	json.put("metadata", metadata);
    }

    public JSONObject getProperties() {
	return json.optJSONObject("properties");
    }

    public void setProperties(JSONObject properties) {
	json.put("properties", properties);
    }

    public Long getId() {
	if (json.has("@iot.id")) {
	    return json.getLong("@iot.id");
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
}


