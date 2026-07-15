package eu.flora.essi.ingestor.annals;

import java.io.File;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.csv.CSVRecord;
import org.json.JSONArray;
import org.json.JSONObject;

public class EditorOrganizations extends CSVTable {

    public EditorOrganizations(File compartmentFile) throws Exception {
	super(compartmentFile, new String[] { "SIGLA_ENTE_COMPILATORE", "NOME_ENTE_COMPILATORE", "RUOLO_ENTE_COMPILATORE",
		"EmailpuntoContatto", "RUOLO_PROGETTO", "GENERAL" }, "SIGLA_ENTE_COMPILATORE");
    }

    public String getName(String code) {
	return super.getRecord(code).get("NOME_ENTE_COMPILATORE");
    }

    public String getRole(String code) {
	return super.getRecord(code).get("RUOLO_ENTE_COMPILATORE");
    }

    public String getEmail(String code) {
	return super.getRecord(code).get("EmailpuntoContatto");
    }

    public String getRoleInProject(String code) {
	return super.getRecord(code).get("RUOLO_PROGETTO");
    }

    public boolean isGeneral(String code) {
	CSVRecord record = super.getRecord(code);
	String ret = record.get("GENERAL");
	if (ret.equalsIgnoreCase("y")) {
	    return true;
	} else {
	    return false;
	}

    }

    public Set<String> getCodes() {
	Set<String> ret = new HashSet<String>();
	Set<Entry<String, CSVRecord>> entries = getMap().entrySet();
	for (Entry<String, CSVRecord> entry : entries) {
	    ret.add(entry.getValue().get("SIGLA_ENTE_COMPILATORE"));
	}
	return ret;
    }

    public boolean addIndividualNameToOrganization(JSONObject organization, String individualName) {
	JSONArray indNames = organization.optJSONArray("individualName");
	if (indNames == null) {
	    indNames = new JSONArray();
	    organization.put("individualName", indNames);
	}
	if (individualName != null && !individualName.isEmpty()) {
	    boolean found = false;
	    for (int i = 0; i < indNames.length(); i++) {
		String name = indNames.getString(i);
		if (name.equals(individualName)) {
		    found = true;
		}
	    }
	    if (!found) {
		indNames.put(individualName);
		return true;
	    }
	}
	return false;
    }

    public JSONObject getJSONObject(String code) {
	JSONObject ret = new JSONObject();
	String name = getName(code);
	String role = getRole(code);
	String[] roles = role.split(",");
	JSONArray roleArray = new JSONArray();
	for (String r : roles) {
	    roleArray.put(r.trim());
	}
	String roleInProject = getRoleInProject(code);
	String email = getEmail(code);
	ret.put("organizationName", name);
	ret.put("role", roleArray);
	if (email != null && !email.isEmpty()) {
	    ret.put("email", email);
	}
	return ret;
    }

}
