package com.upland.connect.afp.engine;

import java.nio.charset.StandardCharsets;

/**
 * Minimal HTML renderer based on interpreted AFP text fragments.
 */
public final class AfpHtmlRenderer {
    public byte[] render(AfpInterpretation interpretation) {
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

    private static String escape(String value) {
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }
}
