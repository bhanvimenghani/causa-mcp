package com.causa.mcp;

import com.causa.mcp.CausaApiClient.DiagnosisInfo;
import com.causa.mcp.CausaApiClient.DiagnosticResponse;
import com.causa.mcp.CausaApiClient.RecommendationInfo;
import com.causa.mcp.CausaApiClient.WebhookResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@QuarkusTest
class CausaToolsTest {

    @Inject
    CausaTools causaTools;

    @Inject
    ObjectMapper objectMapper;

    @InjectMock
    @RestClient
    CausaApiClient apiClient;

    @Test
    void initiateRcaReturnsPendingWhenAlertAccepted() throws Exception {
        when(apiClient.triggerAlert(any())).thenReturn(new WebhookResponse(
            "success",
            "accepted",
            1,
            1,
            0,
            Map.of("alert-1", "diag-123"),
            Map.of()
        ));

        String result = causaTools.initiate_rca("catalog", "demo", "catalog-123");
        JsonNode json = objectMapper.readTree(result);

        assertEquals("diag-123", json.get("diagnostic_id").asText());
        assertEquals("PENDING", json.get("status").asText());
        assertEquals("catalog", json.get("workload_name").asText());
        assertEquals("demo", json.get("namespace").asText());
    }

    @Test
    void initiateRcaReturnsErrorWhenAlertRejected() throws Exception {
        when(apiClient.triggerAlert(any())).thenReturn(new WebhookResponse(
            "error",
            "rejected",
            1,
            0,
            1,
            Map.of(),
            Map.of("alert-1", "invalid payload")
        ));

        String result = causaTools.initiate_rca("catalog", "demo", "catalog-123");
        JsonNode json = objectMapper.readTree(result);

        assertEquals("Alert rejected by Causa Engine", json.get("error").asText());
        assertEquals("rejected", json.get("message").asText());
        assertEquals("invalid payload", json.get("details").get("alert-1").asText());
    }

    @Test
    void initiateRcaReturnsEngineUnavailableOnBackendFailure() {
        when(apiClient.triggerAlert(any())).thenThrow(new RuntimeException("backend unavailable"));

        String result = causaTools.initiate_rca("catalog", "demo", "catalog-123");

        assertEquals("{\"error\": \"engine_unavailable\"}", result);
    }

    @Test
    void getRcaStatusReturnsBackendStatus() throws Exception {
        when(apiClient.getDiagnostic("diag-123")).thenReturn(new DiagnosticResponse(
            "diag-123",
            "RUNNING",
            null,
            null,
            null,
            null
        ));

        String result = causaTools.get_rca_status("diag-123");
        JsonNode json = objectMapper.readTree(result);

        assertEquals("diag-123", json.get("diagnostic_id").asText());
        assertEquals("RUNNING", json.get("status").asText());
    }

    @Test
    void getRcaResultReturnsSerializedDiagnostic() throws Exception {
        DiagnosticResponse response = new DiagnosticResponse(
            "diag-123",
            "COMPLETED",
            "CausaMcpTriggered",
            "critical",
            null,
            new DiagnosisInfo(
                "CrashLoopBackOff in catalog",
                "Catalog is failing",
                "Pod repeatedly restarts",
                "Container exits due to missing config",
                "CrashLoopBackOff",
                "Missing environment variable",
                List.of("Pod restart count increased"),
                List.of("java.lang.IllegalStateException"),
                0.92,
                "High confidence",
                List.of(new RecommendationInfo(
                    "config",
                    "Add missing environment variable",
                    "Set the required variable on the deployment",
                    "Update deployment manifest",
                    0.9,
                    List.of("Restart deployment after update")
                )),
                "Looks like configuration drift"
            )
        );
        when(apiClient.getDiagnostic("diag-123")).thenReturn(response);

        String result = causaTools.get_rca_result("diag-123");
        JsonNode json = objectMapper.readTree(result);

        assertEquals("diag-123", json.get("id").asText());
        assertEquals("COMPLETED", json.get("status").asText());
        assertEquals("Missing environment variable", json.get("diagnosis").get("root_cause").asText());
        assertTrue(json.get("diagnosis").get("recommendations").isArray());
    }
}
