package org.github.tess1o.geopulse.gps.integrations.homeassistant.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.github.tess1o.geopulse.gps.integrations.homeassistant.model.HomeAssistantGpsData;
import org.github.tess1o.geopulse.gps.service.GpsPointService;
import org.github.tess1o.geopulse.gps.service.auth.GpsIntegrationAuthenticatorRegistry;
import org.github.tess1o.geopulse.shared.gps.GpsSourceType;

import java.util.Optional;
import java.util.UUID;

@Path("/")
@ApplicationScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Slf4j
public class HomeAssistantResource {
    private final GpsPointService gpsPointService;
    private final GpsIntegrationAuthenticatorRegistry authRegistry;

    public HomeAssistantResource(GpsPointService gpsPointService, GpsIntegrationAuthenticatorRegistry authRegistry) {
        this.gpsPointService = gpsPointService;
        this.authRegistry = authRegistry;
    }

    @POST
    @Path("/api/homeassistant")
    public Response handleHA(HomeAssistantGpsData data, @HeaderParam("Authorization") String authToken) {
        log.info("Received payload for home assistant: {}", data);

        var authResult = authRegistry.authenticate(GpsSourceType.HOME_ASSISTANT, authToken);
        if (authResult.isEmpty()) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        UUID userId = authResult.get().getUserId();
        var config = authResult.get().getConfig();
        gpsPointService.saveHomeAssitantGpsPoint(data, userId, GpsSourceType.HOME_ASSISTANT, config);
        return Response.ok().build();
    }
}
