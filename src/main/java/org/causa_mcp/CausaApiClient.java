package org.causa_mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;
import java.util.Map;

/**
 * REST client for the Causa Engine API.
 *
 * Base URL is configured via:
 *   quarkus.rest-client.causa-engine-api.url in application.properties
 *   or CAUSA_ENGINE_URL environment variable
 */
@RegisterRestClient(configKey = "causa-engine-api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface CausaApiClient {

    // -------------------------------------------------------------------------
    // POST /api/v1/webhooks/alerts
    // Sends a synthetic Prometheus alert to trigger an RCA
    // -------------------------------------------------------------------------

    @POST
    @Path("/api/v1/webhooks/alerts")
    WebhookResponse triggerAlert(WebhookRequest request);

    // -------------------------------------------------------------------------
    // GET /api/v1/diagnostics/{id}
    // Fetches full diagnostic detail including RCA result
    // -------------------------------------------------------------------------

    @GET
    @Path("/api/v1/diagnostics/{id}")
    DiagnosticResponse getDiagnostic(@PathParam("id") String diagnosticId);

    // =========================================================================
    // Request / Response records
    // =========================================================================

    // --- Webhook request (Prometheus Alertmanager format) --------------------

    record WebhookRequest(
        String version,
        String status,
        String receiver,
        List<AlertItem> alerts
    ) {}

    record AlertItem(
        String status,
        Map<String, String> labels,
        Map<String, String> annotations,
        String startsAt,
        String fingerprint
    ) {}

    // --- Webhook response ----------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WebhookResponse(
        @JsonProperty("accepted") Map<String, String> accepted,  // alertId -> diagnosticId
        @JsonProperty("rejected") Map<String, String> rejected,
        @JsonProperty("status")   String status
    ) {}

    // --- Diagnostic response (GET /api/v1/diagnostics/{id}) -----------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DiagnosticResponse(
        @JsonProperty("id")           String id,
        @JsonProperty("status")       String status,
        @JsonProperty("alert_name")   String alertName,
        @JsonProperty("severity")     String severity,
        @JsonProperty("workload_info") WorkloadInfo workloadInfo,
        @JsonProperty("diagnosis")    DiagnosisInfo diagnosis
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WorkloadInfo(
        @JsonProperty("workload_name") String workloadName,
        @JsonProperty("namespace")     String namespace,
        @JsonProperty("pod_name")      String podName,
        @JsonProperty("cluster_name")  String clusterName,
        @JsonProperty("workload_type") String workloadType
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DiagnosisInfo(
        @JsonProperty("issue_title")           String issueTitle,
        @JsonProperty("issue_summary")         String issueSummary,
        @JsonProperty("root_cause")            String rootCause,
        @JsonProperty("anomaly_type")          String anomalyType,
        @JsonProperty("evidences")             List<String> evidences,
        @JsonProperty("supporting_logs")       List<String> supportingLogs,
        @JsonProperty("rca_confidence_score")  Double rcaConfidenceScore,
        @JsonProperty("confidence_summary")    String confidenceSummary,
        @JsonProperty("recommendations")       List<RecommendationInfo> recommendations,
        @JsonProperty("llm_notes")             String llmNotes
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RecommendationInfo(
        @JsonProperty("solution_type")        String solutionType,
        @JsonProperty("solution_title")       String solutionTitle,
        @JsonProperty("solution_description") String solutionDescription,
        @JsonProperty("implementation_notes") String implementationNotes
    ) {}
}
