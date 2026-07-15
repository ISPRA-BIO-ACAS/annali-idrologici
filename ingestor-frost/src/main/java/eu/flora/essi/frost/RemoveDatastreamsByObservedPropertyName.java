package eu.flora.essi.frost;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Standalone utility to remove from a FROST service all datastreams (and their observations)
 * that have a specific ObservedProperty name, and to remove related empty Things and their
 * Locations.
 * <p>
 * Usage: {@code java ... RemoveDatastreamsByObservedPropertyName <frostBaseUrl> <observedPropertyName> [--dry-run]}
 * <p>
 * Example: {@code java ... RemoveDatastreamsByObservedPropertyName http://localhost:8080/FROST-Server/v1.1/ "Temperature" }
 */
public class RemoveDatastreamsByObservedPropertyName {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: RemoveDatastreamsByObservedPropertyName <frostBaseUrl> <observedPropertyName> [--dry-run]");
            System.exit(1);
        }

        String baseUrl = args[0];
        String observedPropertyName = args[1];
        boolean dryRun = args.length > 2 && "--dry-run".equals(args[2]);

        if (!baseUrl.endsWith("/")) {
            baseUrl = baseUrl + "/";
        }

        FROSTClient client = new FROSTClient(baseUrl);
        if (dryRun) {
            System.out.println("DRY RUN – no changes will be made.");
        }

        run(client, observedPropertyName, dryRun);
    }

    /**
     * Removes all datastreams (and their observations) whose ObservedProperty name matches,
     * then removes Things that have no datastreams left and their Locations.
     *
     * @param client                FROST client
     * @param observedPropertyName  exact ObservedProperty name to match
     * @param dryRun                if true, only print what would be deleted
     */
    public static void run(FROSTClient client, String observedPropertyName, boolean dryRun)
            throws Exception {

        String filter = "ObservedProperty/name eq " + FilterBuilder.encodeString(observedPropertyName);

        // Collect all matching datastreams (with Thing id when possible via $expand=Thing)
        List<Datastream> toDelete = new ArrayList<>();
        Set<Long> thingIdsToCheck = new HashSet<>();

        PagedResult<Datastream> page = client.getDatastreams(filter, "Thing");
        while (true) {
            for (Datastream ds : page.getItems()) {
                toDelete.add(ds);
                Long thingId = ds.getThingId();
                if (thingId != null) {
                    thingIdsToCheck.add(thingId);
                }
            }
            if (!page.hasNext()) {
                break;
            }
            page = client.getDatastreamsByNextLink(page.getNextLink());
        }

        // If any datastream had no Thing in response, fetch Thing id with $expand=Thing
        for (Datastream ds : toDelete) {
            if (ds.getThingId() == null) {
                Datastream full = client.getDatastream(ds.getId(), "Thing");
                if (full.getThingId() != null) {
                    thingIdsToCheck.add(full.getThingId());
                }
            }
        }

        System.out.println("Found " + toDelete.size() + " datastream(s) with ObservedProperty name '" + observedPropertyName + "'");
        System.out.println("Things that may become empty: " + thingIdsToCheck.size());

        // Delete observations for each datastream, then delete the datastream
        for (Datastream ds : toDelete) {
            if (!dryRun) {
                client.deleteObservationsByDatastream(ds.getId());
                client.deleteDatastream(ds.getId());
            }
            System.out.println((dryRun ? "Would delete " : "Deleted ") + "datastream " + ds.getId());
        }

        // For each thing that might be empty, check and remove thing + its locations if it has no datastreams
        for (Long thingId : thingIdsToCheck) {
            PagedResult<Datastream> remaining = client.getThingDatastreams(thingId);
            if (!remaining.getItems().isEmpty()) {
                continue;
            }
            // Thing has no datastreams – remove its locations then the thing
            PagedResult<Location> locPage = client.getThingLocations(thingId);
            List<Location> locations = new ArrayList<>(locPage.getItems());
            while (locPage.hasNext()) {
                locPage = client.getLocationsByNextLink(locPage.getNextLink());
                locations.addAll(locPage.getItems());
            }
            for (Location loc : locations) {
                if (!dryRun) {
                    client.deleteLocation(loc.getId());
                }
                System.out.println((dryRun ? "Would delete " : "Deleted ") + "location " + loc.getId());
            }
            if (!dryRun) {
                client.deleteThing(thingId);
            }
            System.out.println((dryRun ? "Would delete " : "Deleted ") + "thing " + thingId);
        }

        if (dryRun) {
            System.out.println("Dry run finished. Run without --dry-run to apply changes.");
        } else {
            System.out.println("Done.");
        }
    }
}
