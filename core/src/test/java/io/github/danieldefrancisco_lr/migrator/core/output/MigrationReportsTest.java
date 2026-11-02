package io.github.danieldefrancisco_lr.migrator.core.output;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.danieldefrancisco_lr.migrator.core.agent.AgentOrchestrator.MigrationPlan;
import io.github.danieldefrancisco_lr.migrator.core.analyzer.LegacyModuleAnalyzer.ModuleAnalysisResult;

import org.junit.jupiter.api.Test;

/**
 * Raphael's checks for Object Actions, microservices and themes, with the formats validated on DXP 2026.q3.
 */
class MigrationReportsTest {

    private static final String DEFINITION_HEAD =
        "{\"configuration\": {\"className\": \"com.liferay.object.admin.rest.dto.v1_0.ObjectDefinition\"},\n" +
        " \"items\": [{\"externalReferenceCode\": \"TICKET\", \"name\": \"Ticket\", \"status\": {\"code\": 0},\n";

    @Test
    void validDefinitionWithActionsHasNoActionWarnings() {
        String report = report(file("client-extensions/t-object/batch/01-02-ticket-definition.batch-engine-data.json",
            DEFINITION_HEAD +
            "  \"objectFields\": [{\"DBType\": \"String\", \"externalReferenceCode\": \"TICKET_TITLE\", \"label\": {\"en_US\": \"Title\"}, \"name\": \"title\"}],\n" +
            "  \"objectActions\": [{\"externalReferenceCode\": \"TICKET_ACTION_MARK\", \"label\": {\"en_US\": \"Mark\"}, \"name\": \"mark\",\n" +
            "    \"objectActionExecutorKey\": \"update-object-entry\", \"objectActionTriggerKey\": \"onAfterAdd\",\n" +
            "    \"parameters\": {\"objectDefinitionExternalReferenceCode\": \"TICKET\", \"predefinedValues\": [{\"name\": \"title\", \"value\": \"x\"}]}}]}]}"));

        assertFalse(report.contains("01-02-ticket-definition"), report);
    }

    @Test
    void flagsMissingCodesParametersAndTriggers() {
        String report = report(file("client-extensions/t-object/batch/01-02-ticket-definition.batch-engine-data.json",
            DEFINITION_HEAD +
            "  \"objectFields\": [{\"DBType\": \"String\", \"label\": {\"en_US\": \"Title\"}, \"name\": \"title\"}],\n" +
            "  \"objectActions\": [{\"label\": {\"en_US\": \"Mark\"}, \"name\": \"mark\",\n" +
            "    \"objectActionExecutorKey\": \"update-object-entry\", \"objectActionTriggerKey\": \"onBeforeAdd\",\n" +
            "    \"parameters\": {\"predefinedValues\": [{\"name\": \"title\", \"value\": \"x\"}]}},\n" +
            "    {\"externalReferenceCode\": \"TICKET_ACTION_SYNC\", \"name\": \"sync\", \"objectActionExecutorKey\": \"function#ticket-sync-object-action\",\n" +
            "    \"objectActionTriggerKey\": \"onAfterUpdate\", \"parameters\": {}}]}]}"));

        assertTrue(report.contains("object field without externalReferenceCode ('title')"), report);
        assertTrue(report.contains("object action without externalReferenceCode ('mark')"), report);
        assertTrue(report.contains("action 'mark' needs 'objectDefinitionExternalReferenceCode'"), report);
        assertTrue(report.contains("action 'mark' uses an onBefore* trigger"), report);
        assertTrue(report.contains("calls function#ticket-sync-object-action, but no generated client-extension.yaml defines"), report);
    }

    @Test
    void flagsStandaloneObjectActionFiles() {
        String report = report(file("client-extensions/t-object/batch/01-03-ticket-actions.batch-engine-data.json",
            "{\"configuration\": {\"className\": \"com.liferay.object.admin.rest.dto.v1_0.ObjectAction\"}, \"items\": []}"));

        assertTrue(report.contains("is a standalone ObjectAction batch file"), report);
    }

    @Test
    void checksMicroserviceFilesAndAddresses() {
        String yaml = "assemble:\n    - fromTask: bootJar\n\n" +
            "ticket-sync-oauth-application-user-agent:\n    .serviceAddress: host.docker.internal:58081\n    .serviceScheme: http\n" +
            "    type: oAuthApplicationUserAgent\n\n" +
            "ticket-sync-object-action:\n    resourcePath: /object/action/ticket-sync\n    type: objectAction\n";
        MigrationPlan plan = file("client-extensions/ticket-sync-object-action/client-extension.yaml", yaml);
        plan.generatedFiles.put("client-extensions/ticket-sync-object-action/Dockerfile", "FROM liferay/jar-runner:latest\n");
        plan.generatedFiles.put("client-extensions/t-object/batch/01-02-ticket-definition.batch-engine-data.json",
            DEFINITION_HEAD + "  \"objectActions\": [{\"externalReferenceCode\": \"TICKET_ACTION_SYNC\", \"name\": \"sync\"," +
            " \"objectActionExecutorKey\": \"function#ticket-sync-object-action\", \"objectActionTriggerKey\": \"onAfterUpdate\"}]}]}");

        String report = report(plan);

        assertTrue(report.contains("without client-extensions/ticket-sync-object-action/LCP.json"), report);
        assertFalse(report.contains("without client-extensions/ticket-sync-object-action/Dockerfile"), report);
        assertTrue(report.contains("hard-codes host.docker.internal"), report);
        assertFalse(report.contains("no generated client-extension.yaml defines"), report);
        assertTrue(report.contains(":bootRun"), report);
    }

    @Test
    void flagsUnscopedThemeStyles() {
        String report = report(file("client-extensions/acme-theme/src/css/_custom.scss",
            "body {\n\tbackground: #fff;\n}\n#wrapper .banner {\n\tcolor: red;\n}\n"));

        assertTrue(report.contains("styles body/html at the top level"), report);
    }

    private static MigrationPlan file(String path, String content) {
        MigrationPlan plan = new MigrationPlan();
        plan.generatedFiles.put(path, content);
        return plan;
    }

    private static String report(MigrationPlan plan) {
        return MigrationReports.buildRaphaelVerificationReport(new ModuleAnalysisResult(), plan);
    }

}
