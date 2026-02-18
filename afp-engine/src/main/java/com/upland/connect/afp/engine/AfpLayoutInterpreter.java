package com.upland.connect.afp.engine;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AfpLayoutInterpreter {
    private AfpLayoutInterpreter() {
    }

    static AfpDocumentLayout infer(AfpInterpretation interpretation) {
        String normalized = normalizeText(String.join(" ", interpretation.semantics().textFragments()));
        if (normalized.isBlank()) {
            return fallbackLayout();
        }

        String title = matchOrDefault(normalized, "(?i)(continuing health coverage)", "AFP Converted Document");
        String name = matchOrDefault(normalized, "\\b([A-Z][a-z]+\\s+[A-Z][a-z]+)\\b", "Recipient");
        String address1 = matchOrDefault(normalized, "\\b(\\d{3,}\\s+[A-Za-z0-9 ]+?Street)\\b", "Address line 1");
        String address2 = matchOrDefault(normalized, "\\b([A-Za-z ]+,\\s*[A-Z]{2}\\s+\\d{5})\\b", "Address line 2");

        String intro = spanOrDefault(normalized,
            "(?i)By now you should have received information",
            "(?i)premium\\.",
            "By now you should have received information regarding continuation coverage.");

        String bridge = spanOrDefault(normalized,
            "(?i)But before you make that decision",
            "(?i)following:",
            "You may want to consider other available health coverage options.");

        List<AfpOptionRow> rows = List.of(
            optionRow(normalized,
                "Health Insurance Marketplace",
                "(?i)Health Insurance Marketplace",
                "(?i)Individual Health Insurance"),
            optionRow(normalized,
                "Individual Health Insurance",
                "(?i)Individual Health Insurance",
                "(?i)Medicare"),
            optionRow(normalized,
                "Medicare",
                "(?i)Medicare",
                "(?i)Understand what")
        );

        String action = spanOrDefault(normalized,
            "(?i)Evaluate your options",
            "(?i)$",
            "Evaluate your options and contact support for coverage guidance.");

        return new AfpDocumentLayout(title, name, address1, address2, intro, bridge, rows, action);
    }

    private static AfpOptionRow optionRow(String all, String title, String startRegex, String endRegex) {
        String block = spanOrDefault(all, startRegex, endRegex, title);
        String meaning = firstSentenceAfter(block, title);
        String key = tailSentences(block, meaning);
        if (key.isBlank()) {
            key = "Refer to official enrollment resources for details.";
        }
        return new AfpOptionRow(title, meaning, key);
    }

    private static String firstSentenceAfter(String block, String title) {
        String rest = block.replaceFirst("(?i)" + Pattern.quote(title), "").trim();
        int dot = rest.indexOf('.');
        return dot > 0 ? rest.substring(0, dot + 1).trim() : rest;
    }

    private static String tailSentences(String block, String firstSentence) {
        String trimmed = block.trim();
        if (firstSentence.isBlank() || !trimmed.contains(firstSentence)) {
            return trimmed;
        }
        return trimmed.substring(trimmed.indexOf(firstSentence) + firstSentence.length()).trim();
    }

    private static String spanOrDefault(String text, String startRegex, String endRegex, String fallback) {
        Pattern start = Pattern.compile(startRegex, Pattern.CASE_INSENSITIVE);
        Matcher mStart = start.matcher(text);
        if (!mStart.find()) {
            return fallback;
        }
        int from = mStart.start();
        Pattern end = Pattern.compile(endRegex, Pattern.CASE_INSENSITIVE);
        Matcher mEnd = end.matcher(text);
        if (mEnd.find(mStart.end())) {
            int to = mEnd.end();
            return text.substring(from, Math.min(to, text.length())).trim();
        }
        return text.substring(from).trim();
    }

    private static String matchOrDefault(String text, String regex, String fallback) {
        Matcher m = Pattern.compile(regex).matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        return fallback;
    }

    private static String normalizeText(String value) {
        String out = value.replaceAll("\\s+", " ").trim();
        out = out.replaceAll("(?<=\\p{L}|\\p{N})\\s+(?=[,.;:!?])", "");
        out = out.replaceAll("(?<=[,.;:!?])(?=\\p{L}|\\p{N})", " ");
        out = collapseSpacedWords(out);
        out = out.replaceAll("\\s+", " ").trim();
        return out;
    }

    private static String collapseSpacedWords(String text) {
        Pattern p = Pattern.compile("(?i)(\\b\\p{L}(?:\\s+\\p{L}){3,}\\b)");
        Matcher m = p.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String collapsed = m.group(1).replaceAll("\\s+", "");
            m.appendReplacement(sb, Matcher.quoteReplacement(collapsed));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static AfpDocumentLayout fallbackLayout() {
        return new AfpDocumentLayout(
            "AFP Converted Document",
            "Recipient",
            "Address line 1",
            "Address line 2",
            "No textual fragments extracted.",
            "No bridge text extracted.",
            List.of(new AfpOptionRow("Option", "No details available.", "No key points available.")),
            "No action text available."
        );
    }
}
