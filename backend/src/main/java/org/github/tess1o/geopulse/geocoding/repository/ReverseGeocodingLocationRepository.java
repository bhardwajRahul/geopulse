package org.github.tess1o.geopulse.geocoding.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.github.tess1o.geopulse.geocoding.model.ReverseGeocodingLocationEntity;
import org.locationtech.jts.geom.Point;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * Repository for reverse geocoding locations using ultra-simplified schema.
 * Only stores essential fields: coordinates, bounding box, formatted display name, provider, timestamps.
 */
@ApplicationScoped
@Slf4j
public class ReverseGeocodingLocationRepository implements PanacheRepository<ReverseGeocodingLocationEntity> {

    /**
     * Find cached location using comprehensive spatial query.
     * Checks: 1) Request coordinates proximity, 2) Result coordinates proximity, 3) Bounding box containment
     *
     * @param requestCoordinates The coordinates to search for
     * @param toleranceMeters Tolerance in meters for spatial matching
     * @return Best matching cached location or null
     */
    public ReverseGeocodingLocationEntity findByRequestCoordinates(Point requestCoordinates, double toleranceMeters) {
        String searchQuery = """
                SELECT *
                FROM reverse_geocoding_location
                WHERE ST_DWithin(result_coordinates::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :tolerance)
                    OR ST_DWithin(request_coordinates::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :tolerance)
                    OR ST_Contains(bounding_box, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326))
                ORDER BY last_accessed_at DESC
                LIMIT 1
                """;

        @SuppressWarnings("unchecked")
        List<ReverseGeocodingLocationEntity> results = getEntityManager()
                .createNativeQuery(searchQuery, ReverseGeocodingLocationEntity.class)
                .setParameter("lon", requestCoordinates.getX())
                .setParameter("lat", requestCoordinates.getY())
                .setParameter("tolerance", toleranceMeters)
                .getResultList();

        if (!results.isEmpty()) {
            ReverseGeocodingLocationEntity result = results.getFirst();
            // Update last accessed time asynchronously to prevent deadlocks
            updateAccessTimestampAsync(result.getId());

            log.debug("Found cached location for coordinates: lon={}, lat={}, provider={}",
                    requestCoordinates.getX(), requestCoordinates.getY(), result.getProviderName());
            return result;
        }

        log.debug("No cached location found for coordinates: lon={}, lat={} within {}m",
                requestCoordinates.getX(), requestCoordinates.getY(), toleranceMeters);
        return null;
    }

    /**
     * Find cached location by exact request coordinates.
     *
     * @param requestCoordinates The exact coordinates to search for
     * @return Cached location or null
     */
    public ReverseGeocodingLocationEntity findByExactCoordinates(Point requestCoordinates) {
        return find("requestCoordinates", requestCoordinates).firstResult();
    }

    /**
     * Find reverse geocoding locations by their IDs.
     * Used for export functionality to collect referenced geocoding data.
     *
     * @param ids List of reverse geocoding location IDs
     * @return List of found reverse geocoding locations
     */
    public List<ReverseGeocodingLocationEntity> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return find("id in ?1", ids).list();
    }

    /**
     * Update access timestamp asynchronously in a separate transaction to prevent deadlocks.
     * This is a non-critical operation that shouldn't block the main geocoding flow.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void updateAccessTimestampAsync(Long entityId) {
        try {
            getEntityManager()
                    .createQuery("UPDATE ReverseGeocodingLocationEntity r SET r.lastAccessedAt = :now WHERE r.id = :id")
                    .setParameter("now", Instant.now())
                    .setParameter("id", entityId)
                    .executeUpdate();
        } catch (Exception e) {
            // Silently ignore - timestamp updates are non-critical
            log.trace("Failed to update access timestamp for geocoding entity {}: {}", entityId, e.getMessage());
        }
    }

    /**
     * True batch find cached locations for multiple coordinates.
     * Replaces the fake batch processing with a single spatial query.
     *
     * @param coordinates List of coordinates to search for
     * @param toleranceMeters Tolerance in meters for spatial matching
     * @return Map of coordinate string (lon,lat) to cached location entity
     */
    public Map<String, ReverseGeocodingLocationEntity> findByCoordinatesBatchReal(
            List<Point> coordinates, double toleranceMeters) {

        if (coordinates == null || coordinates.isEmpty()) {
            return Map.of();
        }

        final int BATCH_SIZE = 10000;
        Map<String, ReverseGeocodingLocationEntity> finalResultMap = new HashMap<>();
        int totalPoints = coordinates.size();

        for (int i = 0; i < totalPoints; i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, totalPoints);
            List<Point> batchPoints = coordinates.subList(i, end);
            finalResultMap.putAll(findForBatch(batchPoints, toleranceMeters));
        }

        return finalResultMap;
    }

    private Map<String, ReverseGeocodingLocationEntity> findForBatch(
            List<Point> batchPoints, double toleranceMeters) {

        // Build VALUES clause for input coordinates
        StringBuilder valuesClause = new StringBuilder();
        for (int i = 0; i < batchPoints.size(); i++) {
            if (i > 0) valuesClause.append(", ");
            valuesClause.append("(ST_SetSRID(ST_MakePoint(:lon").append(i)
                    .append(", :lat").append(i).append("), 4326), :lon").append(i)
                    .append(", :lat").append(i).append(")");
        }

        // Step 1: Get matching geocoding IDs and their corresponding input coordinates
        // This query matches the exact logic from findByRequestCoordinates individual method
        String matchingQuery = """
                WITH input_coords AS (
                    SELECT input_point, input_lon, input_lat
                    FROM (VALUES %s) AS coords(input_point, input_lon, input_lat)
                )
                SELECT DISTINCT ON (ic.input_lon, ic.input_lat) 
                       r.id, ic.input_lon, ic.input_lat
                FROM reverse_geocoding_location r
                CROSS JOIN input_coords ic
                WHERE (
                    ST_DWithin(r.result_coordinates::geography, ic.input_point::geography, :tolerance)
                    OR ST_DWithin(r.request_coordinates::geography, ic.input_point::geography, :tolerance)
                    OR ST_Contains(r.bounding_box, ic.input_point)
                )
                ORDER BY ic.input_lon, ic.input_lat, r.last_accessed_at DESC
                """.formatted(valuesClause.toString());

        var matchingQueryExec = getEntityManager().createNativeQuery(matchingQuery)
                .setParameter("tolerance", toleranceMeters);

        // Set coordinate parameters
        for (int i = 0; i < batchPoints.size(); i++) {
            Point point = batchPoints.get(i);
            matchingQueryExec.setParameter("lon" + i, point.getX());
            matchingQueryExec.setParameter("lat" + i, point.getY());
        }

        @SuppressWarnings("unchecked")
        List<Object[]> matchingResults = matchingQueryExec.getResultList();

        if (matchingResults.isEmpty()) {
            return Map.of();
        }

        // Step 2: Get all matching entities in a single query and build coordinate mapping
        Map<Long, List<String>> idToCoordListMap = new HashMap<>(); // One ID can map to multiple coordinates

        for (Object[] row : matchingResults) {
            Long id = ((Number) row[0]).longValue();
            Double inputLon = (Double) row[1];
            Double inputLat = (Double) row[2];
            String coordKey = inputLon + "," + inputLat;

            idToCoordListMap.computeIfAbsent(id, k -> new ArrayList<>()).add(coordKey);
        }

        // Get unique IDs for the query (removes duplicates)
        List<Long> uniqueGeocodingIds = new ArrayList<>(idToCoordListMap.keySet());

        // Get all entities in a single IN query
        List<ReverseGeocodingLocationEntity> entities = find("id in ?1", uniqueGeocodingIds).list();

        // Build result map
        Map<String, ReverseGeocodingLocationEntity> resultMap = new HashMap<>();
        for (ReverseGeocodingLocationEntity entity : entities) {
            List<String> coordKeys = idToCoordListMap.get(entity.getId());
            if (coordKeys != null) {
                // One entity can serve multiple input coordinates
                for (String coordKey : coordKeys) {
                    resultMap.put(coordKey, entity);
                }
            }
        }

        log.debug("Batch geocoding found {} cached results for {} coordinates",
                resultMap.size(), batchPoints.size());

        return resultMap;
    }

    /**
     * Find geocoding locations with filtering and pagination.
     *
     * @param providerName Filter by provider name (optional)
     * @param searchText Search in displayName, city, or country (optional)
     * @param page Page number (1-based)
     * @param limit Page size
     * @param sortField Field to sort by
     * @param sortOrder Sort order (asc or desc)
     * @return List of matching geocoding locations
     */
    public List<ReverseGeocodingLocationEntity> findWithFilters(
            String providerName, String searchText, int page, int limit,
            String sortField, String sortOrder) {

        StringBuilder queryBuilder = new StringBuilder("FROM ReverseGeocodingLocationEntity r WHERE 1=1");

        if (providerName != null && !providerName.isBlank()) {
            queryBuilder.append(" AND r.providerName = :providerName");
        }

        if (searchText != null && !searchText.isBlank()) {
            queryBuilder.append(" AND (LOWER(r.displayName) LIKE :searchText OR LOWER(r.city) LIKE :searchText OR LOWER(r.country) LIKE :searchText)");
        }

        // Add sorting
        String validSortField = validateSortField(sortField);
        String validSortOrder = sortOrder != null && sortOrder.equalsIgnoreCase("asc") ? "ASC" : "DESC";
        queryBuilder.append(" ORDER BY r.").append(validSortField).append(" ").append(validSortOrder);

        var query = getEntityManager().createQuery(queryBuilder.toString(), ReverseGeocodingLocationEntity.class);

        if (providerName != null && !providerName.isBlank()) {
            query.setParameter("providerName", providerName);
        }

        if (searchText != null && !searchText.isBlank()) {
            query.setParameter("searchText", "%" + searchText.toLowerCase() + "%");
        }

        query.setFirstResult((page - 1) * limit);
        query.setMaxResults(limit);

        return query.getResultList();
    }

    /**
     * Count geocoding locations with filters.
     *
     * @param providerName Filter by provider name (optional)
     * @param searchText Search in displayName, city, or country (optional)
     * @return Count of matching records
     */
    public long countWithFilters(String providerName, String searchText) {
        StringBuilder queryBuilder = new StringBuilder("SELECT COUNT(r) FROM ReverseGeocodingLocationEntity r WHERE 1=1");

        if (providerName != null && !providerName.isBlank()) {
            queryBuilder.append(" AND r.providerName = :providerName");
        }

        if (searchText != null && !searchText.isBlank()) {
            queryBuilder.append(" AND (LOWER(r.displayName) LIKE :searchText OR LOWER(r.city) LIKE :searchText OR LOWER(r.country) LIKE :searchText)");
        }

        var query = getEntityManager().createQuery(queryBuilder.toString(), Long.class);

        if (providerName != null && !providerName.isBlank()) {
            query.setParameter("providerName", providerName);
        }

        if (searchText != null && !searchText.isBlank()) {
            query.setParameter("searchText", "%" + searchText.toLowerCase() + "%");
        }

        return query.getSingleResult();
    }

    /**
     * Count results by provider.
     *
     * @return Map of provider name to count
     */
    public Map<String, Long> countByProvider() {
        String queryStr = "SELECT r.providerName, COUNT(r) FROM ReverseGeocodingLocationEntity r GROUP BY r.providerName";

        @SuppressWarnings("unchecked")
        List<Object[]> results = getEntityManager().createQuery(queryStr).getResultList();

        Map<String, Long> countMap = new HashMap<>();
        for (Object[] row : results) {
            countMap.put((String) row[0], (Long) row[1]);
        }

        return countMap;
    }

    /**
     * Count geocoding results created in the last N days.
     *
     * @param days Number of days to look back
     * @return Count of results
     */
    public long countRecentResults(int days) {
        Instant since = Instant.now().minusSeconds(days * 24L * 60L * 60L);
        return count("createdAt > ?1", since);
    }

    /**
     * Find all IDs matching filters (for bulk operations).
     *
     * @param providerName Filter by provider name (optional)
     * @return List of IDs
     */
    public List<Long> findIdsWithFilters(String providerName) {
        StringBuilder queryBuilder = new StringBuilder("SELECT r.id FROM ReverseGeocodingLocationEntity r WHERE 1=1");

        if (providerName != null && !providerName.isBlank()) {
            queryBuilder.append(" AND r.providerName = :providerName");
        }

        var query = getEntityManager().createQuery(queryBuilder.toString(), Long.class);

        if (providerName != null && !providerName.isBlank()) {
            query.setParameter("providerName", providerName);
        }

        return query.getResultList();
    }

    /**
     * Get distinct provider names from the database.
     * Returns all providers that have data, regardless of current configuration.
     *
     * @return List of distinct provider names
     */
    public List<String> findDistinctProviderNames() {
        String queryStr = "SELECT DISTINCT r.providerName FROM ReverseGeocodingLocationEntity r ORDER BY r.providerName";

        @SuppressWarnings("unchecked")
        List<String> results = getEntityManager().createQuery(queryStr, String.class).getResultList();

        return results;
    }

    /**
     * Validate and return safe sort field name.
     */
    private String validateSortField(String sortField) {
        if (sortField == null || sortField.isBlank()) {
            return "lastAccessedAt";
        }

        return switch (sortField.toLowerCase()) {
            case "displayname" -> "displayName";
            case "city" -> "city";
            case "country" -> "country";
            case "providername" -> "providerName";
            case "createdat" -> "createdAt";
            case "lastaccessedat" -> "lastAccessedAt";
            default -> "lastAccessedAt";
        };
    }
}