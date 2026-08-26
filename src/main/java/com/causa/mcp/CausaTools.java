package com.causa.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.causa.mcp.CausaApiClient.AlertItem;
import com.causa.mcp.CausaApiClient.DiagnosticListItem;
import com.causa.mcp.CausaApiClient.DiagnosticResponse;
import com.causa.mcp.CausaApiClient.WebhookRequest;
import com.causa.mcp.CausaApiClient.WebhookResponse;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Causa MCP Tools
 *
 * Exposes 4 MCP tools for any MCP-compatible IDE or agent to trigger and retrieve
 * root cause analysis from the Causa Engine.
 */
public class CausaTools {

    private static final Logger log = LoggerFactory.getLogger(CausaTools.class);

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "causa.cluster.name", defaultValue = "kind")
    String clusterName;

    @Inject
    @RestClient
    CausaApiClient apiClient;

    @Inject
    ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // Tool 1: initiate_rca
    // Sends a synthetic Prometheus alert to the Causa Engine to trigger an RCA.
    // Returns the diagnostic_id created by the engine.
    // -------------------------------------------------------------------------

    @Tool(description = "Initiate a root cause analysis for a failing Kubernetes application. "
        + "Sends a synthetic alert to Causa Engine and returns a diagnostic_id to track progress.")
    @Blocking
    public String initiate_rca(
            @ToolArg(description = "Kubernetes deployment name of the failing application") String app_name,
            @ToolArg(description = "Kubernetes namespace where the application is running") String namespace,
            @ToolArg(description = "Name of the failing pod") String pod_name) {
        try {
            log.info("Initiating RCA for app={}, namespace={}, pod={}", app_name, namespace, pod_name);

            WebhookRequest request = new WebhookRequest(
                "4",
                "firing",
                "causa-mcp",
                List.of(new AlertItem(
                    "firing",
                    Map.of(
                        "alertname", "CausaMcpTriggered",
                        "severity",  "critical",
                        "namespace", namespace,
                        "pod",       pod_name,
                        "container", app_name
                    ),
                    Map.of(
                        "workload_name",  app_name,
                        "namespace",      namespace,
                        "pod_name",       pod_name,
                        "container_name", app_name,
                        "workload_type",  "Deployment",
                        "cluster_name",   clusterName
                    ),
                    Instant.now().toString(),
                    "mcp-" + app_name + "-" + namespace + "-" + Instant.now().toEpochMilli()
                ))
            );

            WebhookResponse response = apiClient.triggerAlert(request);

            if (response.accepted() == null || response.accepted().isEmpty()) {
                return objectMapper.writeValueAsString(Map.of(
                    "error",   "Alert rejected by Causa Engine",
                    "message", response.message() != null ? response.message() : "",
                    "details", response.rejected() != null ? response.rejected() : Map.of()
                ));
            }

            String diagnosticId = response.accepted().values().iterator().next();

            return objectMapper.writeValueAsString(Map.of(
                "diagnostic_id", diagnosticId,
                "status",        "PENDING",
                "workload_name", app_name,
                "namespace",     namespace,
                "message",       "RCA initiated. Poll get_rca_status with diagnostic_id."
            ));

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize response", e);
            return "{\"error\": \"serialization_failed\"}";
        } catch (Exception e) {
            log.error("Failed to initiate RCA for app={}", app_name, e);
            return "{\"error\": \"engine_unavailable\"}";
        }
    }

    // -------------------------------------------------------------------------
    // Tool 2: get_rca_status
    // Polls the Causa Engine for the current status of a diagnostic.
    // -------------------------------------------------------------------------

    @Tool(description = "Get the current status of a running RCA. "
        + "Poll this until status is COMPLETED or FAILED.")
    @Blocking
    public String get_rca_status(
            @ToolArg(description = "The diagnostic_id returned by initiate_rca") String diagnostic_id) {
        try {
            log.info("Fetching RCA status for diagnostic_id={}", diagnostic_id);

            DiagnosticResponse response = apiClient.getDiagnostic(diagnostic_id);

            return objectMapper.writeValueAsString(Map.of(
                "diagnostic_id", response.id(),
                "status",        response.status()
            ));

        } catch (Exception e) {
            log.error("Failed to get RCA status for diagnostic_id={}", diagnostic_id, e);
            return "{\"error\": \"engine_unavailable\"}";
        }
    }

    // -------------------------------------------------------------------------
    // Tool 3: get_rca_result
    // Fetches the full RCA result from Causa Engine once status is COMPLETED.
    // -------------------------------------------------------------------------

    @Tool(description = "Retrieve the full RCA result once status is COMPLETED. "
        + "Returns root cause, evidence, and fix recommendations.")
    @Blocking
    public String get_rca_result(
            @ToolArg(description = "The diagnostic_id returned by initiate_rca") String diagnostic_id) {
        try {
            log.info("Fetching RCA result for diagnostic_id={}", diagnostic_id);

            DiagnosticResponse response = apiClient.getDiagnostic(diagnostic_id);
            return objectMapper.writeValueAsString(response);

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize RCA result", e);
            return "{\"error\": \"serialization_failed\"}";
        } catch (Exception e) {
            log.error("Failed to get RCA result for diagnostic_id={}", diagnostic_id, e);
            return "{\"error\": \"engine_unavailable\"}";
        }
    }

    // -------------------------------------------------------------------------
    // Tool 4: list_rca
    // Lists all RCAs from Causa Engine, filtered by container name and namespace.
    // -------------------------------------------------------------------------

    @Tool(description = "List all RCA diagnostics for a given container and namespace. "
        + "Returns a summary of each diagnostic including id, status, issue, and workload info.")
    @Blocking
    public String list_rca(
            @ToolArg(description = "Kubernetes container (workload) name to filter by") String container_name,
            @ToolArg(description = "Kubernetes namespace to filter by") String namespace) {
        try {
            log.info("Listing RCAs for container={}, namespace={}", container_name, namespace);

            List<DiagnosticListItem> filtered = apiClient.listDiagnostics(container_name, namespace);

            return objectMapper.writeValueAsString(Map.of(
                "container_name", container_name,
                "namespace",      namespace,
                "count",          filtered.size(),
                "diagnostics",    filtered
            ));

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize list_rca response", e);
            return "{\"error\": \"serialization_failed\"}";
        } catch (Exception e) {
            log.error("Failed to list RCAs for container={}, namespace={}", container_name, namespace, e);
            return "{\"error\": \"engine_unavailable\"}";
        }
    }
}
