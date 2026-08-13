package com.causa.mcp;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;

/**
 * Causa MCP Tools
 *
 * Exposes 3 MCP tools for any MCP-compatible IDE or agent to trigger and retrieve
 * root cause analysis from the Causa Engine.
 */
public class CausaTools {

    @Tool(description = "Initiate a root cause analysis for a failing Kubernetes application")
    public String initiate_rca(
            @ToolArg(description = "Kubernetes deployment name of the failing application") String app_name,
            @ToolArg(description = "Kubernetes namespace where the application is running") String namespace,
            @ToolArg(description = "Name of the failing pod") String pod_name) {
        // TODO: wire to Causa Engine in causa-api-client PR
        return "{\"diagnostic_id\": \"stub-001\", \"status\": \"PENDING\", \"message\": \"RCA initiated\"}";
    }

    @Tool(description = "Get the current status of a running RCA. Poll until status is COMPLETED or FAILED.")
    public String get_rca_status(
            @ToolArg(description = "The diagnostic_id returned by initiate_rca") String diagnostic_id) {
        // TODO: wire to Causa Engine in causa-api-client PR
        return "{\"diagnostic_id\": \"" + diagnostic_id + "\", \"status\": \"COMPLETED\"}";
    }

    @Tool(description = "Retrieve the full RCA result once status is COMPLETED")
    public String get_rca_result(
            @ToolArg(description = "The diagnostic_id returned by initiate_rca") String diagnostic_id) {
        // TODO: wire to Causa Engine in causa-api-client PR
        return "{\"diagnostic_id\": \"" + diagnostic_id + "\", \"status\": \"COMPLETED\", \"root_cause\": \"stub - real data coming in next PR\"}";
    }
}
