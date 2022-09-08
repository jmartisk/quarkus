package io.quarkus.it.keycloak;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

import io.quarkus.security.Authenticated;

@Path("/tenant-autorefresh")
public class TenantAutoRefresh {
    @Authenticated
    @GET
    public String getTenantLogout() {
        return "Tenant AutoRefresh";
    }
}
