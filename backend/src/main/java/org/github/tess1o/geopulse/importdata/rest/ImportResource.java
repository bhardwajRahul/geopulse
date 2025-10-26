package org.github.tess1o.geopulse.importdata.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.github.tess1o.geopulse.auth.service.CurrentUserService;
import org.github.tess1o.geopulse.importdata.model.ImportJob;
import org.github.tess1o.geopulse.importdata.model.ImportJobResponse;
import org.github.tess1o.geopulse.importdata.model.ImportOptions;
import org.github.tess1o.geopulse.importdata.service.ImportService;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.jboss.resteasy.reactive.PartType;
import org.jboss.resteasy.reactive.RestForm;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Path("/api/import")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Slf4j
public class ImportResource {

    @Inject
    CurrentUserService currentUserService;

    @Inject
    ImportService importService;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    org.github.tess1o.geopulse.importdata.service.ImportTempFileService tempFileService;

    private static final int MAX_FILE_SIZE_BYTES = 1500 * 1024 * 1024;

    @POST
    @Path("/owntracks/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadOwnTracksImportFile(
            @RestForm("file") FileUpload file,
            @RestForm("options") @PartType(MediaType.TEXT_PLAIN) String options) {
        try {
            UUID userId = currentUserService.getCurrentUserId();
            log.info("Received OwnTracks import request for user: {}", userId);

            // Validate file
            if (file == null || file.size() == 0) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(createErrorResponse("INVALID_FILE", "No file provided"))
                        .build();
            }

            if (file.size() > MAX_FILE_SIZE_BYTES) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(createErrorResponse("FILE_TOO_LARGE",
                                String.format("File size (%d MB) exceeds %d MB limit",
                                        file.size() / (1024 * 1024),
                                        MAX_FILE_SIZE_BYTES / (1024 * 1024))))
                        .build();
            }

            // Validate file extension (should be .json)
            String fileName = file.fileName() != null ? file.fileName() : "owntracks-import.json";
            if (!fileName.toLowerCase(Locale.ENGLISH).endsWith(".json")) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(createErrorResponse("INVALID_FILE_TYPE", "Only JSON files are supported for OwnTracks import"))
                        .build();
            }

            // Parse options
            ImportOptions importOptions;
            try {
                importOptions = objectMapper.readValue(options, ImportOptions.class);
                // Force format to be owntracks
                importOptions.setImportFormat("owntracks");
            } catch (Exception e) {
                log.error("Failed to parse import options", e);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(createErrorResponse("INVALID_OPTIONS",
                                "Invalid import options format: " + e.getMessage()))
                        .build();
            }

            // Handle file based on size - large files use temp storage, small files use memory
            ImportJob job;

            // CRITICAL: Capture file size BEFORE moving the file!
            // After the file is moved, the original upload path no longer exists
            long fileSize = file.size();

            if (tempFileService.shouldUseTempFile(fileSize)) {
                // Large file: move to temp storage (no memory overhead)
                log.info("Large file detected ({} MB), using temp file storage",
                        fileSize / (1024 * 1024));
                try {
                    String tempFilePath = tempFileService.moveUploadedFileToTemp(
                            file.uploadedFile(), java.util.UUID.randomUUID(), fileName);

                    // Create job with temp file path (no data in memory!)
                    job = new ImportJob(userId, importOptions, fileName, new byte[0]);
                    job.setTempFilePath(tempFilePath);
                    job.setFileSizeBytes(fileSize);

                    importService.registerJob(job);

                    log.info("Created OwnTracks import job with temp file: file={}, size={} MB, path={}",
                            fileName, fileSize / (1024 * 1024), tempFilePath);
                } catch (IOException e) {
                    log.error("Failed to move uploaded file to temp storage", e);
                    return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                            .entity(createErrorResponse("FILE_MOVE_ERROR",
                                    "Failed to process uploaded file"))
                            .build();
                }
            } else {
                // Small file: keep in memory (fast path)
                log.info("Small file detected ({} MB), keeping in memory",
                        fileSize / (1024 * 1024));
                try {
                    byte[] fileContent = Files.readAllBytes(file.uploadedFile());
                    job = importService.createOwnTracksImportJob(userId, importOptions, fileName, fileContent);

                    log.info("Created OwnTracks import job in memory: file={}, size={} bytes",
                            fileName, fileContent.length);
                } catch (IOException e) {
                    log.error("Failed to read uploaded file", e);
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(createErrorResponse("FILE_READ_ERROR", "Failed to read uploaded file"))
                            .build();
                }
            }

            // Create response
            log.info("OwnTracks import job created successfully: jobId={}", job.getJobId());
            ImportJobResponse response = new ImportJobResponse();
            response.setSuccess(true);
            response.setImportJobId(job.getJobId());
            response.setStatus(job.getStatus().name().toLowerCase(Locale.ENGLISH));
            response.setUploadedFileName(job.getUploadedFileName());
            response.setFileSizeBytes(job.getFileSizeBytes());
            response.setDetectedDataTypes(job.getDetectedDataTypes());
            response.setEstimatedProcessingTime(job.getEstimatedProcessingTime());
            response.setMessage("OwnTracks import job created successfully");

            return Response.ok(response).build();

        } catch (IllegalStateException e) {
            return Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .entity(createErrorResponse("RATE_LIMIT_EXCEEDED", e.getMessage()))
                    .build();
        } catch (Exception e) {
            log.error("Failed to create OwnTracks import job", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(createErrorResponse("INTERNAL_ERROR", "Failed to create OwnTracks import job"))
                    .build();
        }
    }

    @POST
    @Path("/geopulse/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadGeoPulseImportFile(
            @RestForm("file") FileUpload file,
            @RestForm("options") @PartType(MediaType.TEXT_PLAIN) String options) {
        try {
            UUID userId = currentUserService.getCurrentUserId();
            log.info("Received GeoPulse import request for user: {}", userId);

            // Validate file
            if (file == null || file.size() == 0) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(createErrorResponse("INVALID_FILE", "No file provided"))
                        .build();
            }

            if (file.size() > MAX_FILE_SIZE_BYTES) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(createErrorResponse("FILE_TOO_LARGE", "File size exceeds 100MB limit"))
                        .build();
            }

            // Read file content
            byte[] fileContent;
            try {
                fileContent = Files.readAllBytes(file.uploadedFile());
            } catch (IOException e) {
                log.error("Failed to read uploaded file", e);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(createErrorResponse("FILE_READ_ERROR", "Failed to read uploaded file"))
                        .build();
            }

            // Parse options
            ImportOptions importOptions;
            try {
                importOptions = objectMapper.readValue(options, ImportOptions.class);
            } catch (Exception e) {
                log.error("Failed to parse import options", e);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(createErrorResponse("INVALID_OPTIONS", "Invalid import options format"))
                        .build();
            }

            // Validate options
            if (importOptions.getDataTypes() == null || importOptions.getDataTypes().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(createErrorResponse("INVALID_OPTIONS", "Data types are required"))
                        .build();
            }

            // Create GeoPulse import job
            String fileName = file.fileName() != null ? file.fileName() : "geopulse-import-" + System.currentTimeMillis() + ".zip";
            // Force format to be geopulse for this endpoint
            importOptions.setImportFormat("geopulse");
            ImportJob job = importService.createImportJob(userId, importOptions, fileName, fileContent);

            // Create response
            ImportJobResponse response = new ImportJobResponse();
            response.setSuccess(true);
            response.setImportJobId(job.getJobId());
            response.setStatus(job.getStatus().name().toLowerCase(Locale.ENGLISH));
            response.setUploadedFileName(job.getUploadedFileName());
            response.setFileSizeBytes(job.getFileSizeBytes());
            response.setDetectedDataTypes(job.getDetectedDataTypes());
            response.setEstimatedProcessingTime(job.getEstimatedProcessingTime());
            response.setMessage("GeoPulse import job created successfully");

            return Response.ok(response).build();

        } catch (IllegalStateException e) {
            return Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .entity(createErrorResponse("RATE_LIMIT_EXCEEDED", e.getMessage()))
                    .build();
        } catch (Exception e) {
            log.error("Failed to create GeoPulse import job", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(createErrorResponse("INTERNAL_ERROR", "Failed to create GeoPulse import job"))
                    .build();
        }
    }


    @POST
    @Path("/google-timeline/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadGoogleTimelineImportFile(
            @RestForm("file") FileUpload file,
            @RestForm("options") @PartType(MediaType.TEXT_PLAIN) String options) {
        try {
            UUID userId = currentUserService.getCurrentUserId();
            log.info("Received Google Timeline import request for user: {}", userId);

            // Validate file
            if (file == null || file.size() == 0) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(createErrorResponse("INVALID_FILE", "No file provided"))
                        .build();
            }

            // Validate file extension (should be .json)
            String fileName = file.fileName() != null ? file.fileName() : "google-timeline-import.json";
            if (!fileName.toLowerCase(Locale.ENGLISH).endsWith(".json")) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(createErrorResponse("INVALID_FILE_TYPE", "Only JSON files are supported for Google Timeline import"))
                        .build();
            }

            // Parse options
            ImportOptions importOptions;
            try {
                importOptions = objectMapper.readValue(options, ImportOptions.class);
                // Force format to be google-timeline
                importOptions.setImportFormat("google-timeline");
            } catch (Exception e) {
                log.error("Failed to parse import options", e);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(createErrorResponse("INVALID_OPTIONS",
                                "Invalid import options format: " + e.getMessage()))
                        .build();
            }

            // Handle file based on size - large files use temp storage, small files use memory
            ImportJob job;

            // CRITICAL: Capture file size BEFORE moving the file!
            // After the file is moved, the original upload path no longer exists
            long fileSize = file.size();

            if (tempFileService.shouldUseTempFile(fileSize)) {
                // Large file: move to temp storage (no memory overhead)
                log.info("Large file detected ({} MB), using temp file storage",
                        fileSize / (1024 * 1024));
                try {
                    String tempFilePath = tempFileService.moveUploadedFileToTemp(
                            file.uploadedFile(), java.util.UUID.randomUUID(), fileName);

                    // Create job with temp file path (no data in memory!)
                    job = new ImportJob(userId, importOptions, fileName, new byte[0]);
                    job.setTempFilePath(tempFilePath);
                    job.setFileSizeBytes(fileSize);

                    importService.registerJob(job);

                    log.info("Created Google Timeline import job with temp file: file={}, size={} MB, path={}",
                            fileName, fileSize / (1024 * 1024), tempFilePath);
                } catch (IOException e) {
                    log.error("Failed to move uploaded file to temp storage", e);
                    return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                            .entity(createErrorResponse("FILE_MOVE_ERROR",
                                    "Failed to process uploaded file"))
                            .build();
                }
            } else {
                // Small file: keep in memory (fast path)
                log.info("Small file detected ({} MB), keeping in memory",
                        fileSize / (1024 * 1024));
                try {
                    byte[] fileContent = Files.readAllBytes(file.uploadedFile());
                    job = importService.createGoogleTimelineImportJob(userId, importOptions, fileName, fileContent);
                } catch (IOException e) {
                    log.error("Failed to read uploaded file", e);
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(createErrorResponse("FILE_READ_ERROR", "Failed to read uploaded file"))
                            .build();
                }
            }

            // Create response
            ImportJobResponse response = new ImportJobResponse();
            response.setSuccess(true);
            response.setImportJobId(job.getJobId());
            response.setStatus(job.getStatus().name().toLowerCase(Locale.ENGLISH));
            response.setUploadedFileName(job.getUploadedFileName());
            response.setFileSizeBytes(job.getFileSizeBytes());
            response.setDetectedDataTypes(job.getDetectedDataTypes());
            response.setEstimatedProcessingTime(job.getEstimatedProcessingTime());
            response.setMessage("Google Timeline import job created successfully");

            return Response.ok(response).build();

        } catch (IllegalStateException e) {
            return Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .entity(createErrorResponse("RATE_LIMIT_EXCEEDED", e.getMessage()))
                    .build();
        } catch (Exception e) {
            log.error("Failed to create Google Timeline import job", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(createErrorResponse("INTERNAL_ERROR", "Failed to create Google Timeline import job"))
                    .build();
        }
    }

    @POST
    @Path("/gpx/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadGpxImportFile(
            @RestForm("file") FileUpload file,
            @RestForm("options") @PartType(MediaType.TEXT_PLAIN) String options) {
        try {
            UUID userId = currentUserService.getCurrentUserId();
            log.info("Received GPX import request for user: {}", userId);

            // Validate file
            if (file == null || file.size() == 0) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(createErrorResponse("INVALID_FILE", "No file provided"))
                        .build();
            }

            // Check file size (max 100MB)
            if (file.size() > MAX_FILE_SIZE_BYTES) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(createErrorResponse("FILE_TOO_LARGE", "File size exceeds 100MB limit"))
                        .build();
            }

            // Validate file extension (should be .gpx)
            String fileName = file.fileName() != null ? file.fileName() : "gpx-import.gpx";
            if (!fileName.toLowerCase(Locale.ENGLISH).endsWith(".gpx")) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(createErrorResponse("INVALID_FILE_TYPE", "Only GPX files are supported for GPX import"))
                        .build();
            }

            // Read file content
            byte[] fileContent;
            try {
                fileContent = Files.readAllBytes(file.uploadedFile());
            } catch (IOException e) {
                log.error("Failed to read uploaded file", e);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(createErrorResponse("FILE_READ_ERROR", "Failed to read uploaded file"))
                        .build();
            }

            // Parse options
            ImportOptions importOptions;
            try {
                importOptions = objectMapper.readValue(options, ImportOptions.class);
                // Force format to be gpx
                importOptions.setImportFormat("gpx");
            } catch (Exception e) {
                log.error("Failed to parse import options", e);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(createErrorResponse("INVALID_OPTIONS", "Invalid import options format"))
                        .build();
            }

            // Create GPX import job
            ImportJob job = importService.createGpxImportJob(userId, importOptions, fileName, fileContent);

            // Create response
            ImportJobResponse response = new ImportJobResponse();
            response.setSuccess(true);
            response.setImportJobId(job.getJobId());
            response.setStatus(job.getStatus().name().toLowerCase(Locale.ENGLISH));
            response.setUploadedFileName(job.getUploadedFileName());
            response.setFileSizeBytes(job.getFileSizeBytes());
            response.setDetectedDataTypes(job.getDetectedDataTypes());
            response.setEstimatedProcessingTime(job.getEstimatedProcessingTime());
            response.setMessage("GPX import job created successfully");

            return Response.ok(response).build();

        } catch (IllegalStateException e) {
            return Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .entity(createErrorResponse("RATE_LIMIT_EXCEEDED", e.getMessage()))
                    .build();
        } catch (Exception e) {
            log.error("Failed to create GPX import job", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(createErrorResponse("INTERNAL_ERROR", "Failed to create GPX import job"))
                    .build();
        }
    }

    @POST
    @Path("/geojson/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadGeoJsonImportFile(
            @RestForm("file") FileUpload file,
            @RestForm("options") @PartType(MediaType.TEXT_PLAIN) String options) {
        try {
            UUID userId = currentUserService.getCurrentUserId();
            log.info("Received GeoJSON import request for user: {}", userId);

            // Debug logging
            log.debug("File parameter: {}", file != null ? "present" : "null");
            log.debug("Options parameter: {}", options);
            if (file != null) {
                log.debug("File name: {}, size: {} bytes", file.fileName(), file.size());
            }

            // Validate file
            if (file == null || file.size() == 0) {
                log.warn("File validation failed: file is null or empty");
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(createErrorResponse("INVALID_FILE", "No file provided"))
                        .build();
            }

            // Check file size (max 1500MB)
            if (file.size() > MAX_FILE_SIZE_BYTES) {
                log.warn("File size validation failed: {} bytes exceeds limit of {} bytes",
                        file.size(), MAX_FILE_SIZE_BYTES);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(createErrorResponse("FILE_TOO_LARGE",
                                String.format("File size (%d MB) exceeds %d MB limit",
                                        file.size() / (1024 * 1024),
                                        MAX_FILE_SIZE_BYTES / (1024 * 1024))))
                        .build();
            }

            // Validate file extension (should be .json or .geojson)
            String fileName = file.fileName() != null ? file.fileName() : "geojson-import.geojson";
            String lowerFileName = fileName.toLowerCase(Locale.ENGLISH);
            log.debug("File extension check: {}", fileName);
            if (!lowerFileName.endsWith(".json") && !lowerFileName.endsWith(".geojson")) {
                log.warn("File extension validation failed: {}", fileName);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(createErrorResponse("INVALID_FILE_TYPE", "Only JSON and GeoJSON files are supported for GeoJSON import"))
                        .build();
            }

            // Parse options first
            ImportOptions importOptions;
            try {
                log.debug("Parsing options JSON: {}", options);
                importOptions = objectMapper.readValue(options, ImportOptions.class);
                // Force format to be geojson
                importOptions.setImportFormat("geojson");
                log.debug("Parsed import options successfully: clearData={}",
                        importOptions.isClearDataBeforeImport());
            } catch (Exception e) {
                log.error("Failed to parse import options: {}", options, e);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(createErrorResponse("INVALID_OPTIONS",
                                "Invalid import options format: " + e.getMessage()))
                        .build();
            }

            // Handle file based on size - large files use temp storage, small files use memory
            ImportJob job;

            // CRITICAL: Capture file size BEFORE moving the file!
            // After the file is moved, the original upload path no longer exists
            long fileSize = file.size();

            if (tempFileService.shouldUseTempFile(fileSize)) {
                // Large file: move to temp storage (no memory overhead)
                log.info("Large file detected ({} MB), using temp file storage",
                        fileSize / (1024 * 1024));
                try {
                    String tempFilePath = tempFileService.moveUploadedFileToTemp(
                            file.uploadedFile(), java.util.UUID.randomUUID(), fileName);

                    // Create job with temp file path (no data in memory!)
                    job = new ImportJob(userId, importOptions, fileName, new byte[0]);
                    job.setTempFilePath(tempFilePath);
                    job.setFileSizeBytes(fileSize);

                    importService.registerJob(job);

                    log.info("Created GeoJSON import job with temp file: file={}, size={} MB, path={}",
                            fileName, fileSize / (1024 * 1024), tempFilePath);
                } catch (IOException e) {
                    log.error("Failed to move uploaded file to temp storage", e);
                    return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                            .entity(createErrorResponse("FILE_MOVE_ERROR",
                                    "Failed to process uploaded file"))
                            .build();
                }
            } else {
                // Small file: keep in memory (fast path)
                log.info("Small file detected ({} MB), keeping in memory",
                        file.size() / (1024 * 1024));
                try {
                    byte[] fileContent = Files.readAllBytes(file.uploadedFile());
                    job = importService.createGeoJsonImportJob(userId, importOptions, fileName, fileContent);

                    log.info("Created GeoJSON import job in memory: file={}, size={} bytes",
                            fileName, fileContent.length);
                } catch (IOException e) {
                    log.error("Failed to read uploaded file", e);
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(createErrorResponse("FILE_READ_ERROR", "Failed to read uploaded file"))
                            .build();
                }
            }

            // Create response
            log.info("GeoJSON import job created successfully: jobId={}", job.getJobId());
            ImportJobResponse response = new ImportJobResponse();
            response.setSuccess(true);
            response.setImportJobId(job.getJobId());
            response.setStatus(job.getStatus().name().toLowerCase(Locale.ENGLISH));
            response.setUploadedFileName(job.getUploadedFileName());
            response.setFileSizeBytes(job.getFileSizeBytes());
            response.setDetectedDataTypes(job.getDetectedDataTypes());
            response.setEstimatedProcessingTime(job.getEstimatedProcessingTime());
            response.setMessage("GeoJSON import job created successfully");

            return Response.ok(response).build();

        } catch (IllegalStateException e) {
            return Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .entity(createErrorResponse("RATE_LIMIT_EXCEEDED", e.getMessage()))
                    .build();
        } catch (Exception e) {
            log.error("Failed to create GeoJSON import job", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(createErrorResponse("INTERNAL_ERROR", "Failed to create GeoJSON import job"))
                    .build();
        }
    }

    @GET
    @Path("/status/{importJobId}")
    public Response getImportStatus(@PathParam("importJobId") UUID importJobId) {
        try {
            UUID userId = currentUserService.getCurrentUserId();
            ImportJob job = importService.getImportJob(importJobId, userId);

            if (job == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(createErrorResponse("IMPORT_NOT_FOUND", "Import job not found"))
                        .build();
            }

            ImportJobResponse response = new ImportJobResponse();
            response.setSuccess(true);
            response.setImportJobId(job.getJobId());
            response.setStatus(job.getStatus().name().toLowerCase(Locale.ENGLISH));
            response.setUploadedFileName(job.getUploadedFileName());
            response.setFileSizeBytes(job.getFileSizeBytes());
            response.setDetectedDataTypes(job.getDetectedDataTypes());
            response.setProgress(job.getProgress());
            response.setCreatedAt(job.getCreatedAt());
            response.setCompletedAt(job.getCompletedAt());
            response.setError(job.getError());

            return Response.ok(response).build();

        } catch (Exception e) {
            log.error("Failed to get import status", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(createErrorResponse("INTERNAL_ERROR", "Failed to get import status"))
                    .build();
        }
    }

    @DELETE
    @Path("/jobs/{importJobId}")
    public Response deleteImportJob(@PathParam("importJobId") UUID importJobId) {
        try {
            UUID userId = currentUserService.getCurrentUserId();
            boolean deleted = importService.deleteImportJob(importJobId, userId);

            if (!deleted) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(createErrorResponse("IMPORT_NOT_FOUND", "Import job not found"))
                        .build();
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Import job deleted successfully");

            return Response.ok(response).build();

        } catch (Exception e) {
            log.error("Failed to delete import job", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(createErrorResponse("INTERNAL_ERROR", "Failed to delete import job"))
                    .build();
        }
    }

    // Helper method to create error responses
    private Map<String, Object> createErrorResponse(String code, String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("code", code);
        error.put("message", message);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("error", error);
        
        return response;
    }

}