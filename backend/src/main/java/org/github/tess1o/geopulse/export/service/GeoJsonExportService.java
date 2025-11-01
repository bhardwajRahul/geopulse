package org.github.tess1o.geopulse.export.service;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.github.tess1o.geopulse.export.model.ExportJob;
import org.github.tess1o.geopulse.gps.model.GpsPointEntity;
import org.github.tess1o.geopulse.gps.repository.GpsPointRepository;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Service responsible for generating GeoJSON format exports using streaming approach.
 * Memory-efficient: processes GPS points in batches without loading all data into memory.
 */
@ApplicationScoped
@Slf4j
public class GeoJsonExportService {

    @Inject
    GpsPointRepository gpsPointRepository;

    @Inject
    StreamingExportService streamingExportService;

    /**
     * Generates a GeoJSON export for the given export job using STREAMING approach.
     * Memory usage: O(batch_size) instead of O(total_records).
     *
     * @param job the export job
     * @return the GeoJSON as bytes
     * @throws IOException if an I/O error occurs
     */
    public byte[] generateGeoJsonExport(ExportJob job) throws IOException {
        log.info("Starting streaming GeoJSON export for user {}", job.getUserId());

        job.updateProgress(5, "Initializing GeoJSON export...");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // Count total points for progress tracking (optional, can be skipped for performance)
        // For now, we'll estimate based on batches
        int totalRecords = -1; // Unknown, will update progress based on batches

        job.updateProgress(10, "Starting to stream GPS data...");

        // Stream GeoJSON FeatureCollection with features array
        streamingExportService.streamJsonObjectWithArray(
            baos,
            // Write GeoJSON FeatureCollection metadata
            (gen, mapper) -> {
                try {
                    gen.writeStringField("type", "FeatureCollection");
                } catch (IOException e) {
                    throw new RuntimeException("Failed to write GeoJSON metadata", e);
                }
            },
            // Array field name
            "features",
            // Fetch batch function
            page -> gpsPointRepository.findByUserAndDateRange(
                job.getUserId(),
                job.getDateRange().getStartDate(),
                job.getDateRange().getEndDate(),
                page,
                StreamingExportService.DEFAULT_BATCH_SIZE,
                "timestamp",
                "asc"
            ),
            // Write each GPS point as GeoJSON feature
            this::writeGpsPointAsGeoJsonFeature,
            // Progress tracking
            job,
            totalRecords,
            10,  // progress start: 10%
            90,  // progress end: 90%
            "Exporting GPS points:"
        );

        byte[] result = baos.toByteArray();

        job.updateProgress(95, "Finalizing GeoJSON export...");
        log.info("Completed streaming GeoJSON export: {} bytes", result.length);

        return result;
    }

    /**
     * Writes a single GPS point as a GeoJSON feature directly to the JSON stream.
     * This method is called for each GPS point without accumulating them in memory.
     */
    private void writeGpsPointAsGeoJsonFeature(JsonGenerator gen, GpsPointEntity gpsPoint, ObjectMapper mapper)
            throws IOException {

        // Extract coordinates from PostGIS Point geometry
        double longitude = gpsPoint.getCoordinates().getX();
        double latitude = gpsPoint.getCoordinates().getY();

        // Start Feature object
        gen.writeStartObject();
        gen.writeStringField("type", "Feature");

        // Write ID
        if (gpsPoint.getId() != null) {
            gen.writeStringField("id", gpsPoint.getId().toString());
        }

        // Write geometry
        gen.writeObjectFieldStart("geometry");
        gen.writeStringField("type", "Point");
        gen.writeArrayFieldStart("coordinates");
        gen.writeNumber(longitude);
        gen.writeNumber(latitude);
        if (gpsPoint.getAltitude() != null) {
            gen.writeNumber(gpsPoint.getAltitude());
        }
        gen.writeEndArray(); // coordinates
        gen.writeEndObject(); // geometry

        // Write properties
        gen.writeObjectFieldStart("properties");
        gen.writeStringField("timestamp", gpsPoint.getTimestamp().toString());

        if (gpsPoint.getAltitude() != null) {
            gen.writeNumberField("altitude", gpsPoint.getAltitude());
        }
        if (gpsPoint.getVelocity() != null) {
            gen.writeNumberField("velocity", gpsPoint.getVelocity());
        }
        if (gpsPoint.getAccuracy() != null) {
            gen.writeNumberField("accuracy", gpsPoint.getAccuracy());
        }
        if (gpsPoint.getBattery() != null) {
            gen.writeNumberField("battery", gpsPoint.getBattery().intValue());
        }
        if (gpsPoint.getDeviceId() != null) {
            gen.writeStringField("deviceId", gpsPoint.getDeviceId());
        }
        if (gpsPoint.getSourceType() != null) {
            gen.writeStringField("sourceType", gpsPoint.getSourceType().name());
        }

        gen.writeEndObject(); // properties
        gen.writeEndObject(); // feature
    }
}
