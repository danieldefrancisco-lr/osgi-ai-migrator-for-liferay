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
    void flagsReservedFieldsActionNamesAndIncompleteRepeats() {
        MigrationPlan plan = file("client-extensions/l-object/batch/01-02-loan-definition.batch-engine-data.json",
            "{\"configuration\": {\"className\": \"com.liferay.object.admin.rest.dto.v1_0.ObjectDefinition\"},\n" +
            " \"items\": [{\"externalReferenceCode\": \"C_LOAN\", \"name\": \"Loan\", \"objectFields\": [\n" +
            "  {\"externalReferenceCode\": \"FIELD_LOAN_DUEDATE\", \"label\": {\"en_US\": \"Due\"}, \"name\": \"dueDate\"},\n" +
            "  {\"externalReferenceCode\": \"FIELD_LOAN_STATUS\", \"label\": {\"en_US\": \"Status\"}, \"name\": \"status\"}]}]}");
        plan.generatedFiles.put("client-extensions/l-object/batch/01-05-loan-actions.batch-engine-data.json",
            "{\"configuration\": {\"className\": \"com.liferay.object.admin.rest.dto.v1_0.ObjectDefinition\"},\n" +
            " \"items\": [{\"externalReferenceCode\": \"C_LOAN\", \"name\": \"Loan\", \"objectActions\": [\n" +
            "  {\"externalReferenceCode\": \"C_LOAN_ACTION_NOTIFY\", \"label\": {\"en_US\": \"Notify\"}, \"name\": \"Notify User on Loan Creation\",\n" +
            "   \"objectActionExecutorKey\": \"notification\", \"objectActionTriggerKey\": \"onAfterAdd\", \"parameters\": {}}]}]}");

        String report = report(plan);

        assertTrue(report.contains("object field named 'status', a reserved name"), report);
        assertFalse(report.contains("businessType"), report);
        assertTrue(report.contains("action 'Notify User on Loan Creation' must have a camelCase name"), report);
        assertTrue(report.contains("repeats object C_LOAN with 0 of its 2 fields"), report);
    }

    @Test
    void flagsNotificationTermsThatAreNotFieldsOfTheObject() {
        MigrationPlan plan = file("client-extensions/l-object/batch/01-02-loan-definition.batch-engine-data.json",
            "{\"configuration\": {\"className\": \"com.liferay.object.admin.rest.dto.v1_0.ObjectDefinition\"},\n" +
            " \"items\": [{\"externalReferenceCode\": \"C_LOAN\", \"name\": \"Loan\", \"objectFields\": [\n" +
            "  {\"externalReferenceCode\": \"FIELD_LOAN_DUEDATE\", \"name\": \"dueDate\"},\n" +
            "  {\"externalReferenceCode\": \"FIELD_LOAN_BORROWEREMAIL\", \"name\": \"borrowerEmail\"},\n" +
            "  {\"businessType\": \"User\", \"externalReferenceCode\": \"FIELD_LOAN_BORROWERUSER\", \"name\": \"borrowerUser\"}]}]}");
        plan.generatedFiles.put("client-extensions/l-object/batch/01-03-loan-notification-template.batch-engine-data.json",
            "{\"configuration\": {\"className\": \"com.liferay.notification.rest.dto.v1_0.NotificationTemplate\"},\n" +
            " \"items\": [{\"externalReferenceCode\": \"NT_LOAN\", \"objectDefinitionExternalReferenceCode\": \"C_LOAN\",\n" +
            "  \"subject\": {\"en_US\": \"Due [%LOAN_DUEDATE%] for [%CLOAN_BOOK_TITLE%]\"},\n" +
            "  \"recipients\": [{\"to\": {\"en_US\": \"[%LOAN_BORROWEREMAIL%]\"}}],\n" +
            "  \"body\": {\"en_US\": \"Due on [%C_LOAN_DUE_DATE%]\"}}]}");

        String report = report(plan);

        assertTrue(report.contains("uses terms that are not fields of object Loan: [%CLOAN_BOOK_TITLE%], [%C_LOAN_DUE_DATE%]"), report);
        assertTrue(report.contains("Valid terms: [%LOAN_BORROWEREMAIL%], [%LOAN_BORROWERUSER%], [%LOAN_DUEDATE%]"), report);
        assertTrue(report.contains("field 'borrowerUser' has businessType 'User', which Liferay Objects does not have"), report);
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
