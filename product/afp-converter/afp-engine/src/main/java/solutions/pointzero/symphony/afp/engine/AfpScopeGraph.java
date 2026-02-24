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
    private final int overlayBeginTransitions;
    private final int overlayEndTransitions;
    private final int overlayUnderflows;
    private final int resourceBeginTransitions;
    private final int resourceEndTransitions;
    private final int resourceUnderflows;
    private final int pageBeginTransitions;
    private final int pageEndTransitions;
    private final int pageUnderflows;
    private final int finalOverlayDepth;
    private final int finalResourceDepth;
    private final int finalPageDepth;
    private final List<String> warnings;

    private AfpScopeGraph(int maxOverlayDepth,
                          int maxResourceDepth,
                          int maxPageDepth,
                          int transitionCount,
                          int overlayBeginTransitions,
                          int overlayEndTransitions,
                          int overlayUnderflows,
                          int resourceBeginTransitions,
                          int resourceEndTransitions,
                          int resourceUnderflows,
                          int pageBeginTransitions,
                          int pageEndTransitions,
                          int pageUnderflows,
                          int finalOverlayDepth,
                          int finalResourceDepth,
                          int finalPageDepth,
                          List<String> warnings) {
        this.maxOverlayDepth = maxOverlayDepth;
        this.maxResourceDepth = maxResourceDepth;
        this.maxPageDepth = maxPageDepth;
        this.transitionCount = transitionCount;
        this.overlayBeginTransitions = overlayBeginTransitions;
        this.overlayEndTransitions = overlayEndTransitions;
        this.overlayUnderflows = overlayUnderflows;
        this.resourceBeginTransitions = resourceBeginTransitions;
        this.resourceEndTransitions = resourceEndTransitions;
        this.resourceUnderflows = resourceUnderflows;
        this.pageBeginTransitions = pageBeginTransitions;
        this.pageEndTransitions = pageEndTransitions;
        this.pageUnderflows = pageUnderflows;
        this.finalOverlayDepth = finalOverlayDepth;
        this.finalResourceDepth = finalResourceDepth;
        this.finalPageDepth = finalPageDepth;
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
        int overlayBeginTransitions = 0;
        int overlayEndTransitions = 0;
        int overlayUnderflows = 0;
        int resourceBeginTransitions = 0;
        int resourceEndTransitions = 0;
        int resourceUnderflows = 0;
        int pageBeginTransitions = 0;
        int pageEndTransitions = 0;
        int pageUnderflows = 0;
        List<String> warnings = new ArrayList<>();

        for (int i = 0; i < fields.size(); i++) {
            AfpStructuredField field = fields.get(i);
            String sfId = field.sfIdHex();
            int beforeOverlay = overlayDepth;
            int beforeResource = resourceDepth;
            int beforePage = pageDepth;

            switch (sfId) {
                case SF_BMO -> {
                    overlayDepth++;
                    overlayBeginTransitions++;
                }
                case SF_EMO -> {
                    if (overlayDepth == 0) {
                        overlayUnderflows++;
                        warnings.add("scope-graph: overlay scope underflow at field index " + i + " (EMO)");
                    } else {
                        overlayDepth--;
                        overlayEndTransitions++;
                    }
                }
                case SF_BRS -> {
                    resourceDepth++;
                    resourceBeginTransitions++;
                }
                case SF_ERS -> {
                    if (resourceDepth == 0) {
                        resourceUnderflows++;
                        warnings.add("scope-graph: resource scope underflow at field index " + i + " (ERS)");
                    } else {
                        resourceDepth--;
                        resourceEndTransitions++;
                    }
                }
                case SF_BPG -> {
                    pageDepth++;
                    pageBeginTransitions++;
                }
                case SF_EPG -> {
                    if (pageDepth == 0) {
                        pageUnderflows++;
                        warnings.add("scope-graph: page scope underflow at field index " + i + " (EPG)");
                    } else {
                        pageDepth--;
                        pageEndTransitions++;
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
            overlayBeginTransitions,
            overlayEndTransitions,
            overlayUnderflows,
            resourceBeginTransitions,
            resourceEndTransitions,
            resourceUnderflows,
            pageBeginTransitions,
            pageEndTransitions,
            pageUnderflows,
            overlayDepth,
            resourceDepth,
            pageDepth,
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

    String transitionBreakdownLine() {
        return "scope-graph: transition-breakdown overlay(+"
            + overlayBeginTransitions
            + "/-"
            + overlayEndTransitions
            + ",underflow="
            + overlayUnderflows
            + ",final="
            + finalOverlayDepth
            + "), resource(+"
            + resourceBeginTransitions
            + "/-"
            + resourceEndTransitions
            + ",underflow="
            + resourceUnderflows
            + ",final="
            + finalResourceDepth
            + "), page(+"
            + pageBeginTransitions
            + "/-"
            + pageEndTransitions
            + ",underflow="
            + pageUnderflows
            + ",final="
            + finalPageDepth
            + ")";
    }
}
