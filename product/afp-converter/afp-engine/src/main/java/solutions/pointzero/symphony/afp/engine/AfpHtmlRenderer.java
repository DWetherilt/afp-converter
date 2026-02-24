package solutions.pointzero.symphony.afp.engine;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * HTML renderer for interpreted AFP text fragments.
 */
public final class AfpHtmlRenderer {
    private static final boolean SEMANTIC_TEMPLATE_OPT_IN =
        Boolean.parseBoolean(System.getProperty("afp.render.html.semanticTemplate", "false"));

    public byte[] render(AfpInterpretation interpretation) {
        if (SEMANTIC_TEMPLATE_OPT_IN) {
            return renderSemanticTemplate(interpretation);
        }
        return renderRawText(interpretation);
    }

    private byte[] renderRawText(AfpInterpretation interpretation) {
        List<String> fragments = interpretation.semantics().textFragments();
        String text = normalizeJoinedFragments(fragments);

        StringBuilder html = new StringBuilder();
        html.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            .append("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><meta charset=\"UTF-8\" />")
            .append("<title>AFP Text Preview</title>")
            .append("<style>")
            .append("body{font-family:Menlo,Consolas,monospace;background:#f7f7f8;margin:0;padding:20px;color:#1f2328;}")
            .append(".page{background:#fff;max-width:1120px;margin:0 auto;padding:24px 28px;border:1px solid #d0d7de;}")
            .append("h1{margin:0 0 12px 0;font-size:20px;}")
            .append(".meta{font-size:12px;color:#57606a;margin:0 0 14px 0;}")
            .append("pre{margin:0;white-space:pre-wrap;word-break:break-word;line-height:1.4;font-size:12px;}")
            .append("</style></head><body><div class=\"page\">")
            .append("<h1>AFP Text Preview</h1>\n")
            .append("<div class=\"meta\">source=")
            .append(escape(interpretation.sourceLabel()))
            .append(" | fields=").append(interpretation.structuredFieldCount())
            .append(" | pages=").append(interpretation.pageCount())
            .append(" | fragments=").append(fragments.size())
            .append("</div>\n")
            .append("<pre>")
            .append(escape(text.isBlank() ? "(no text fragments extracted)" : text))
            .append("</pre>")
            .append("</div></body></html>\n");
        return html.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] renderSemanticTemplate(AfpInterpretation interpretation) {
        AfpDocumentLayout layout = AfpLayoutInterpreter.infer(interpretation);

        StringBuilder html = new StringBuilder();
        html.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            .append("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><meta charset=\"UTF-8\" />")
            .append("<title>").append(escape(layout.title())).append("</title>")
            .append("<style>")
            .append("body{font-family:Arial,sans-serif;background:#f0f2f4;margin:0;padding:24px;}")
            .append(".page{background:#fff;max-width:850px;margin:0 auto;padding:50px 60px;box-shadow:0 2px 14px rgba(0,0,0,.15);}")
            .append("h1{color:#0f7a95;margin:0 0 16px 0;font-size:34px;line-height:1.1;}")
            .append(".meta{color:#777;font-size:12px;margin:0 0 18px 0;}")
            .append(".address{font-weight:700;margin:6px 0 18px 0;line-height:1.35;}")
            .append(".intro{margin:0 0 16px 0;line-height:1.5;}")
            .append(".bridge{margin:0 0 14px 0;}")
            .append("table{width:100%;border-collapse:collapse;font-size:14px;}")
            .append("th{background:#1f86a5;color:#fff;text-align:left;padding:8px;}")
            .append("td{border:1px solid #d0d8de;vertical-align:top;padding:10px;line-height:1.45;}")
            .append(".subhead{color:#0f7a95;margin:16px 0 8px 0;font-weight:700;}")
            .append("</style></head><body><div class=\"page\">")
            .append("<h1>").append(escape(layout.title())).append("</h1>\n")
            .append("<div class=\"meta\">source=")
            .append(escape(interpretation.sourceLabel()))
            .append(" | fields=").append(interpretation.structuredFieldCount())
            .append(" | pages=").append(interpretation.pageCount())
            .append("</div>\n")
            .append("<div class=\"address\">")
            .append(escape(layout.name())).append("<br />")
            .append(escape(layout.addressLine1())).append("<br />")
            .append(escape(layout.addressLine2()))
            .append("</div>\n")
            .append("<p class=\"intro\">").append(escape(layout.introParagraph())).append("</p>\n")
            .append("<p class=\"bridge\">").append(escape(layout.bridgeText())).append("</p>\n")
            .append("<table><tr><th>Options</th><th>What does this option mean?</th><th>Key points</th></tr>\n");

        for (AfpOptionRow row : layout.options()) {
            html.append("<tr><td>").append(escape(row.option())).append("</td><td>")
                .append(escape(row.meaning()))
                .append("</td><td>").append(escape(row.keyPoints()))
                .append("</td></tr>\n");
        }

        html.append("</table>\n")
            .append("<div class=\"subhead\">Understand what's right for you</div>\n")
            .append("<div>").append(escape(layout.actionText())).append("</div>\n")
            .append("</div></body></html>\n");
        return html.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String normalizeJoinedFragments(List<String> fragments) {
        String joined = String.join(" ", fragments).replaceAll("\\s+", " ").trim();
        joined = joined.replaceAll(" (?=[,.;:!?])", "");
        joined = joined.replaceAll("([,.;:!?])(?=[^\\s])", "$1 ");
        return joined.trim();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }
}
