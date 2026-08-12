package org.causa_mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import org.causa_mcp.CausaApiClient.*;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class CausaTools {

    private static final Logger log = LoggerFactory.getLogger(CausaTools.class);

    @Inject
    @RestClient
    CausaApiClient apiClient;

    @Inject
    ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // Tool 1: initiate_rca
    // Sends a synthetic Prometheus alert to Causa Engine to trigger an RCA.
    // Returns the diagnostic_id created by the engine.
    // -------------------------------------------------------------------------

    @Tool(description = "Initiate a root cause analysis for a failing Kubernetes application. Sends a synthetic alert to Causa Engine and returns a diagnostic_id to track progress.")
    @Blocking
    public String initiate_rca(
            @ToolArg(description = "Kubernetes deployment name of the failing application") String app_name,
            @ToolArg(description = "Kubernetes namespace where the application is running") String namespace,
            @ToolArg(description = "Name of the failing pod") String pod_name) {
        try {
            log.info("Initiating RCA for app: {}, namespace: {}, pod: {}", app_name, namespace, pod_name);

            // Build a synthetic Prometheus Alertmanager payload
            WebhookRequest request = new WebhookRequest(
                "4",
                "firing",
                "causa-mcp",
                List.of(new AlertItem(
                    "firing",
                    // labels
                    Map.of(
                        "alertname", "CausaMcpTriggered",
                        "severity",  "critical",
                        "namespace", namespace,
                        "pod",       pod_name,
                        "container", app_name
                    ),
                    // annotations
                    Map.of(
                        "workload_name", app_name,
                        "namespace",     namespace,
                        "pod_name",      pod_name,
                        "container_name", app_name,
                        "workload_type", "Deployment",
                        "cluster_name",  "kind"
                    ),
                    Instant.now().toString(),
                    "mcp-" + app_name + "-" + namespace + "-" + Instant.now().toEpochMilli()
                ))
            );

            WebhookResponse response = apiClient.triggerAlert(request);

            // Extract the diagnostic_id from the accepted map
            if (response.accepted() == null || response.accepted().isEmpty()) {
                return "{\"error\": \"Alert was rejected by Causa Engine. Check app_name and namespace.\", \"details\": " + objectMapper.writeValueAsString(response.rejected()) + "}";
            }

            // accepted map is: { alertId -> diagnosticId } — get the first entry
            String diagnosticId = response.accepted().values().iterator().next();

            return "{"
                + "\"diagnostic_id\": \"" + diagnosticId + "\","
                + "\"status\": \"PENDING\","
                + "\"workload_name\": \"" + app_name + "\","
                + "\"namespace\": \"" + namespace + "\","
                + "\"message\": \"RCA initiated. Use diagnostic_id to poll for status and result.\""
                + "}";

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize response", e);
            return "{\"error\": \"Failed to serialize response: " + e.getMessage() + "\"}";
        } catch (Exception e) {
            log.error("Failed to initiate RCA for app: {}", app_name, e);
            return "{\"error\": \"Failed to initiate RCA: " + e.getMessage() + "\"}";
        }
    }

    // -------------------------------------------------------------------------
    // Tool 2: get_rca_status
    // Polls the Causa Engine for the current status of a diagnostic.
    // -------------------------------------------------------------------------

    @Tool(description = "Get the current status of a running RCA. Poll this until status is COMPLETED or FAILED.")
    @Blocking
    public String get_rca_status(
            @ToolArg(description = "The diagnostic_id returned by initiate_rca") String diagnostic_id) {
        try {
            log.info("Fetching RCA status for diagnostic_id: {}", diagnostic_id);

            DiagnosticResponse response = apiClient.getDiagnostic(diagnostic_id);

            return "{"
                + "\"diagnostic_id\": \"" + response.id() + "\","
                + "\"status\": \"" + response.status() + "\""
                + "}";

        } catch (Exception e) {
            log.error("Failed to get RCA status for diagnostic_id: {}", diagnostic_id, e);
            return "{\"error\": \"Failed to get RCA status: " + e.getMessage() + "\"}";
        }
    }

    // -------------------------------------------------------------------------
    // Tool 3: get_rca_result
    // Fetches the full RCA result from Causa Engine once status is COMPLETED.
    // -------------------------------------------------------------------------

    @Tool(description = "Retrieve the full RCA result once status is COMPLETED. Returns root cause, evidence, and fix recommendations.")
    @Blocking
    public String get_rca_result(
            @ToolArg(description = "The diagnostic_id returned by initiate_rca") String diagnostic_id) {
        try {
            log.info("Fetching RCA result for diagnostic_id: {}", diagnostic_id);

            DiagnosticResponse response = apiClient.getDiagnostic(diagnostic_id);
            return objectMapper.writeValueAsString(response);

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize RCA result", e);
            return "{\"error\": \"Failed to serialize result: " + e.getMessage() + "\"}";
        } catch (Exception e) {
            log.error("Failed to get RCA result for diagnostic_id: {}", diagnostic_id, e);
            return "{\"error\": \"Failed to get RCA result: " + e.getMessage() + "\"}";
        }
    }
}
