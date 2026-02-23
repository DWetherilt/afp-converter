package solutions.pointzero.symphony.afp.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AfpGroundTruthComparisonTest {
    @Test
    void sampleDataMatchesHtmlGroundTruthTokens() throws Exception {
        Path afpPath = Path.of("..", "..", "sampleData", "sample.afp").normalize();
        Path htmlPath = Path.of("..", "..", "sampleOutput", "sample.html").normalize();

        Assumptions.assumeTrue(Files.exists(afpPath), "Missing sample AFP at " + afpPath);
        Assumptions.assumeTrue(Files.exists(htmlPath), "Missing sample HTML at " + htmlPath);

        AfpInterpretation interpretation = new AfpInterpreter().interpret(afpPath, "sample.afp");
        String extracted = String.join(" ", interpretation.semantics().textFragments()).toLowerCase(Locale.ROOT);
        Set<String> extractedTokens = tokenize(extracted);

        String html = Files.readString(htmlPath, StandardCharsets.UTF_8);
        String htmlText = htmlToText(html).toLowerCase(Locale.ROOT);
        Set<String> expectedTokens = tokenize(htmlText);

        assertTrue(expectedTokens.size() > 100, "Ground truth token set unexpectedly small");
        assertTrue(extractedTokens.size() > 30, "Extracted token set unexpectedly small");

        long matched = expectedTokens.stream().filter(extractedTokens::contains).count();
        double overlap = matched / (double) expectedTokens.size();

        // PoC threshold: we should recover a meaningful subset of IBM converter output text.
        assertTrue(overlap >= 0.18, "Token overlap too low: " + overlap);

        // Critical address/header markers from sample output.
        assertTrue(extracted.contains("john"), "Expected token missing: john");
        assertTrue(extracted.contains("doe"), "Expected token missing: doe");
        assertTrue(extracted.contains("street"), "Expected token missing: street");
        assertTrue(extracted.contains("12345"), "Expected token missing: 12345");
        assertTrue(extracted.contains("continuing"), "Expected token missing: continuing");
    }

    private static String htmlToText(String html) {
        String noStyle = html.replaceAll("(?is)<style.*?>.*?</style>", " ");
        String noScript = noStyle.replaceAll("(?is)<script.*?>.*?</script>", " ");
        String noTags = noScript.replaceAll("(?is)<[^>]+>", " ");
        return decodeEntities(noTags).replaceAll("\\s+", " ").trim();
    }

    private static String decodeEntities(String text) {
        return text
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ");
    }

    private static Set<String> tokenize(String text) {
        return Arrays.stream(text.split("[^\\p{L}\\p{N}]+"))
            .map(token -> token.toLowerCase(Locale.ROOT))
            .filter(token -> token.length() >= 3)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
