package io.github.danieldefrancisco_lr.migrator.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.danieldefrancisco_lr.migrator.core.analyzer.LegacyModuleAnalyzer.ModuleAnalysisResult;

import org.junit.jupiter.api.Test;

class AgentOrchestratorTest {

    @Test
    void collapsesRunawayDashesInTableSeparatorsAndRules() {
        String dashes = "-".repeat(500);
        String blueprint =
            "## API CONTRACT\n" +
            "| Object | Endpoint |\n" +
            "|" + dashes + "|:" + dashes + ":|\n" +
            "| Book | /o/c/books |\n" +
            dashes + "\n" +
            "  " + dashes + "  \n";

        assertEquals(
            "## API CONTRACT\n" +
            "| Object | Endpoint |\n" +
            "|---|:---:|\n" +
            "| Book | /o/c/books |\n" +
            "---\n" +
            "  ---  \n",
            AgentOrchestrator.collapseDashRuns(blueprint));
    }

    @Test
    void collapsesAlignedSeparatorRows() {
        // The row Gemini looped on: '| :---...' with a space before each colon
        String row = "| :--------- | :" + "-".repeat(1000) + "\n";

        assertEquals("| :--- | :---\n", AgentOrchestrator.collapseDashRuns(row));
    }

    @Test
    void listsReservedServiceBuilderColumnsWithTheirNewNames() {
        ModuleAnalysisResult analysis = new ModuleAnalysisResult();
        analysis.rawSourceExtracts.put("service.xml",
            "<service-builder>\n" +
            "  <entity name=\"Book\" local-service=\"true\">\n" +
            "    <column name=\"bookId\" primary=\"true\" type=\"long\" />\n" +
            "    <column name=\"companyId\" type=\"long\" />\n" +
            "    <column name=\"title\" type=\"String\" />\n" +
            "    <column name=\"status\" type=\"int\" />\n" +
            "  </entity>\n" +
            "  <entity name=\"Loan\" local-service=\"true\">\n" +
            "    <column name=\"status\" type=\"String\" />\n" +
            "  </entity>\n" +
            "</service-builder>\n");

        assertEquals("- Book.status -> bookStatus\n- Loan.status -> loanStatus\n", AgentOrchestrator.reservedColumnRenames(analysis));
    }

    @Test
    void keepsDashesInOrdinaryText() {
        String text = "---\nfront: matter\n---\n- item with a ------ dash run\n| a | b |\n|---|---|\n";

        assertEquals(text, AgentOrchestrator.collapseDashRuns(text));
    }

}
