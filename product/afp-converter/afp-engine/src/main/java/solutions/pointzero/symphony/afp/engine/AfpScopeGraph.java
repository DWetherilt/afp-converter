package solutions.pointzero.symphony.afp.engine;

import java.util.ArrayList;
import java.util.List;

final class AfpScopeGraph {
    private static final String SF_BMO = "D3A8CE";
    private static final String SF_EMO = "D3A9CE";
    private static final String SF_BRS = "D3A8D9";
    private static final String SF_ERS = "D3A9D9";
    private static final String SF_BPG = "D3A8AF";
    private static final String SF_EPG = "D3A9AF";

    private final int maxOverlayDepth;
    private final int maxResourceDepth;
    private final int maxPageDepth;
    private final int transitionCount;
    private final List<String> warnings;

    private AfpScopeGraph(int maxOverlayDepth,
                          int maxResourceDepth,
                          int maxPageDepth,
                          int transitionCount,
                          List<String> warnings) {
        this.maxOverlayDepth = maxOverlayDepth;
        this.maxResourceDepth = maxResourceDepth;
        this.maxPageDepth = maxPageDepth;
        this.transitionCount = transitionCount;
        this.warnings = List.copyOf(warnings);
    }

    static AfpScopeGraph analyze(List<AfpStructuredField> fields) {
        int overlayDepth = 0;
        int resourceDepth = 0;
        int pageDepth = 0;
        int maxOverlayDepth = 0;
        int maxResourceDepth = 0;
        int maxPageDepth = 0;
        int transitions = 0;
        List<String> warnings = new ArrayList<>();

        for (int i = 0; i < fields.size(); i++) {
            AfpStructuredField field = fields.get(i);
            String sfId = field.sfIdHex();
            int beforeOverlay = overlayDepth;
            int beforeResource = resourceDepth;
            int beforePage = pageDepth;

            switch (sfId) {
                case SF_BMO -> overlayDepth++;
                case SF_EMO -> {
                    if (overlayDepth == 0) {
                        warnings.add("scope-graph: overlay scope underflow at field index " + i + " (EMO)");
                    } else {
                        overlayDepth--;
                    }
                }
                case SF_BRS -> resourceDepth++;
                case SF_ERS -> {
                    if (resourceDepth == 0) {
                        warnings.add("scope-graph: resource scope underflow at field index " + i + " (ERS)");
                    } else {
                        resourceDepth--;
                    }
                }
                case SF_BPG -> pageDepth++;
                case SF_EPG -> {
                    if (pageDepth == 0) {
                        warnings.add("scope-graph: page scope underflow at field index " + i + " (EPG)");
                    } else {
                        pageDepth--;
                    }
                }
                default -> {
                }
            }

            if (overlayDepth != beforeOverlay || resourceDepth != beforeResource || pageDepth != beforePage) {
                transitions++;
            }
            maxOverlayDepth = Math.max(maxOverlayDepth, overlayDepth);
            maxResourceDepth = Math.max(maxResourceDepth, resourceDepth);
            maxPageDepth = Math.max(maxPageDepth, pageDepth);
        }

        if (overlayDepth > 0) {
            warnings.add("scope-graph: unclosed overlay scope depth=" + overlayDepth);
        }
        if (resourceDepth > 0) {
            warnings.add("scope-graph: unclosed resource scope depth=" + resourceDepth);
        }
        if (pageDepth > 0) {
            warnings.add("scope-graph: unclosed page scope depth=" + pageDepth);
        }

        return new AfpScopeGraph(
            maxOverlayDepth,
            maxResourceDepth,
            maxPageDepth,
            transitions,
            warnings
        );
    }

    List<String> warnings() {
        return warnings;
    }

    String summaryLine() {
        return "scope-graph: transitions="
            + transitionCount
            + ", maxOverlayDepth=" + maxOverlayDepth
            + ", maxResourceDepth=" + maxResourceDepth
            + ", maxPageDepth=" + maxPageDepth;
    }
}
