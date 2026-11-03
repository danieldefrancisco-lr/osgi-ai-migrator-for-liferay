package io.github.danieldefrancisco_lr.migrator.core.analyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.danieldefrancisco_lr.migrator.core.analyzer.CustomLogicExtractor.LogicUnit;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class CustomLogicExtractorTest {

    @Test
    void mapsListenerMethodsToTriggersAndSpotsLoggingAndHttpCalls() {
        String listener =
            "public class LoanModelListener extends BaseModelListener<Loan> {\n" +
            "\t@Override\n" +
            "\tpublic void onAfterUpdate(Loan originalLoan, Loan loan) throws ModelListenerException {\n" +
            "\t\tString json = \"{\\\"title\\\": }\"; // braces in strings and comments } are ignored\n" +
            "\t\tHttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());\n" +
            "\t}\n" +
            "\t@Override\n" +
            "\tpublic void onAfterCreate(Loan loan) {\n" +
            "\t\t_log.info(\"Loan created for \" + loan.getBorrowerEmail());\n" +
            "\t}\n" +
            "\tprivate static final Log _log = LogFactoryUtil.getLog(LoanModelListener.class);\n" +
            "}\n";

        assertEquals(List.of(
                "LoanModelListener.onAfterUpdate (model listener) -> onAfterUpdate, calls an external system",
                "LoanModelListener.onAfterCreate (model listener) -> onAfterAdd, logging only"),
            summaries("LoanModelListener.java", listener));
    }

    @Test
    void mapsLocalServiceMethodsByNameAndSkipsGeneratedAndConstructors() {
        String impl =
            "public class LoanLocalServiceImpl extends LoanLocalServiceBaseImpl {\n" +
            "\tpublic LoanLocalServiceImpl() {\n\t\tsuper();\n\t}\n" +
            "\tpublic Loan addLoan(String title) throws Exception {\n\t\tif (x) { y(); }\n\t\treturn z;\n\t}\n" +
            "\tpublic Loan returnLoan(long loanId) {\n\t\treturn update(loanId);\n\t}\n" +
            "\tpublic List<Loan> getOverdueLoans(Date date) {\n\t\treturn loanFinder.find(date);\n\t}\n" +
            "}\n";

        assertEquals(List.of(
                "LoanLocalServiceImpl.addLoan (local service impl) -> onAfterAdd",
                "LoanLocalServiceImpl.returnLoan (local service impl) -> standalone",
                "LoanLocalServiceImpl.getOverdueLoans (local service impl) -> query"),
            summaries("LoanLocalServiceImpl.java", impl));
        assertTrue(CustomLogicExtractor.extract("LoanLocalServiceBaseImpl.java", impl).isEmpty());
        assertTrue(CustomLogicExtractor.extract("BaseLoanLocalServiceImpl.java", impl).isEmpty());
        assertTrue(CustomLogicExtractor.extract("LoanPortlet.java", impl).isEmpty());
    }

    private static List<String> summaries(String fileName, String content) {
        return CustomLogicExtractor.extract(fileName, content).stream().map(LogicUnit::summary).collect(Collectors.toList());
    }

}
