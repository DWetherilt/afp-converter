package com.upland.connect.afp.engine;

import java.util.List;

record AfpDocumentLayout(String title,
                         String name,
                         String addressLine1,
                         String addressLine2,
                         String introParagraph,
                         String bridgeText,
                         List<AfpOptionRow> options,
                         String actionText) {
}
