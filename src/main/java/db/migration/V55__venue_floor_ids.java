package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pins named their floor by its position in the map, so taking a floor out of the middle
 * moved every pin after it onto the wrong floor. Floors now carry an id and pins name it.
 *
 * <p>This gives every stored floor an id - f1, f2 ... in their order, unless it has one -
 * and turns each pin's position into the id of the floor that stood there. A pin whose
 * position no longer names a floor of its map is let go rather than guessed. JSON in a
 * text column is beyond portable SQL, so this is a Java migration.
 */
public class V55__venue_floor_ids extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        upgrade(context.getConnection());
    }

    @SuppressWarnings("unchecked")
    public static void upgrade(Connection connection) throws SQLException {
        ObjectMapper mapper = new ObjectMapper();
        record Row(long id, long sessionId, String kind, Map<String, Object> content) {}
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, session_id, kind, content FROM venue_section WHERE kind IN ('MAP', 'PLACES') ORDER BY id");
             ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
                rows.add(new Row(rs.getLong("id"), rs.getLong("session_id"), rs.getString("kind"),
                    new LinkedHashMap<>(mapper.readValue(rs.getString("content"), Map.class))));
            }
        }

        // Maps first: their floors get ids, and each map's ids are remembered in order.
        Map<Long, List<String>> floorsOf = new HashMap<>();
        for (Row row : rows) {
            if (!"MAP".equals(row.kind())) continue;
            List<String> ids = new ArrayList<>();
            List<Object> floors = new ArrayList<>();
            if (row.content().get("floors") instanceof List<?> list) {
                int n = 0;
                for (Object item : list) {
                    n++;
                    if (!(item instanceof Map<?, ?> f)) continue;
                    Map<String, Object> floor = new LinkedHashMap<>((Map<String, Object>) f);
                    Object id = floor.get("id");
                    String floorId = id == null || ids.contains(id.toString()) ? "f" + n : id.toString();
                    while (ids.contains(floorId)) floorId = floorId + "x";
                    floor.put("id", floorId);
                    ids.add(floorId);
                    floors.add(floor);
                }
            }
            row.content().put("floors", floors);
            floorsOf.put(row.id(), ids);
        }

        // Then the places, each pin moved from a position to the id of the floor that was there.
        for (Row row : rows) {
            if (!"PLACES".equals(row.kind())) continue;
            List<String> ids = row.content().get("mapId") instanceof Number mapId
                ? floorsOf.getOrDefault(mapId.longValue(), List.of()) : List.of();
            List<Object> places = new ArrayList<>();
            if (row.content().get("places") instanceof List<?> list) {
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> p)) continue;
                    Map<String, Object> place = new LinkedHashMap<>((Map<String, Object>) p);
                    Object position = place.remove("floor");
                    if (!place.containsKey("floorId")) {
                        String floorId = position instanceof Number n && n.intValue() >= 0 && n.intValue() < ids.size()
                            ? ids.get(n.intValue()) : null;
                        place.put("floorId", floorId);
                        if (floorId == null) {
                            place.put("x", null);
                            place.put("y", null);
                        }
                    }
                    places.add(place);
                }
            }
            row.content().put("places", places);
        }

        try (PreparedStatement update = connection.prepareStatement("UPDATE venue_section SET content = ? WHERE id = ?")) {
            for (Row row : rows) {
                update.setString(1, mapper.writeValueAsString(row.content()));
                update.setLong(2, row.id());
                update.addBatch();
            }
            update.executeBatch();
        }
    }
}
