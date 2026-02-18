package com.upland.connect.afp.engine;

import com.upland.connect.afp.api.ResourceContext;
import org.afplib.afplib.CFI;
import org.afplib.afplib.CFIRG;
import org.afplib.afplib.IDD;
import org.afplib.afplib.IID;
import org.afplib.afplib.IOB;
import org.afplib.afplib.OBP;
import org.afplib.afplib.PGD;
import org.afplib.afplib.AMB;
import org.afplib.afplib.AMI;
import org.afplib.afplib.RMB;
import org.afplib.afplib.RMI;
import org.afplib.afplib.SCFL;
import org.afplib.afplib.SBI;
import org.afplib.afplib.SVI;
import org.afplib.afplib.TRN;
import org.afplib.base.Triplet;
import org.afplib.io.AfpInputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.imageio.ImageIO;

final class AfpNativePdfRenderer {
    private static final ImageResolutionService IMAGE_RESOLUTION = new ImageResolutionService();
    private static final boolean SEMANTIC_LAYOUT_OPT_IN =
        Boolean.parseBoolean(System.getProperty("afp.render.semanticLayout", "false"));
    private static final int PTOCA_CS_INTRODUCER = 0x2B;
    private static final int PTOCA_FN_SCFL = 0xC5;
    private static final int PTOCA_FN_AMI = 0xC6;
    private static final int PTOCA_FN_RMI = 0xC8;
    private static final int PTOCA_FN_AMB = 0xD2;
    private static final int PTOCA_FN_RMB = 0xD4;
    private static final int PTOCA_FN_SBI = 0xD9;
    private static final int PTOCA_FN_SVI = 0xDD;
    private static final int PTOCA_FN_TRN = 0xDA;
    private static final int PTOCA_FN_TRN_ALT = 0xDB;
    private static final Map<String, String> SF_NAMES = Map.ofEntries(
        Map.entry("D3A8A8", "BDT"),
        Map.entry("D3A9A8", "EDT"),
        Map.entry("D3A8AF", "BPG"),
        Map.entry("D3A9AF", "EPG"),
        Map.entry("D3A8C9", "BIM"),
        Map.entry("D3A9C9", "EIM"),
        Map.entry("D3A8C6", "BGR"),
        Map.entry("D3A9C6", "EGR"),
        Map.entry("D3A8CE", "BMO"),
        Map.entry("D3A9CE", "EMO"),
        Map.entry("D3A8A5", "BRS"),
        Map.entry("D3A9A5", "ERS"),
        Map.entry("D3A892", "BOC"),
        Map.entry("D3A992", "EOC"),
        Map.entry("D3EE92", "OBD"),
        Map.entry("D3EE9B", "PTX"),
        Map.entry("D3A09B", "TRN"),
        Map.entry("D3A090", "TRN")
    );

    void render(Path outputPath, AfpInterpretation interpretation) throws IOException {
        render(outputPath, interpretation, null);
    }

    void render(Path outputPath, AfpInterpretation interpretation, ResourceContext resourceContext) throws IOException {
        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        AfpCodePageProfile profile = new AfpCodePageProfile(
            resolveCharset(interpretation.semantics().resolvedSbcsCharset(), StandardCharsets.ISO_8859_1),
            resolveCharset(interpretation.semantics().resolvedDbcsCharset(), null),
            "native-pdf"
        );
        List<List<ImageObjectEvidence>> pageImageEvidence = collectPageImageEvidence(interpretation.fields());
        List<List<BufferedImage>> pageDecodedImages = decodePageImages(pageImageEvidence);
        List<List<byte[]>> pageRawImagePayloads = extractPageImagePayloads(pageImageEvidence);
        List<List<String>> pageImageResourceHints = extractPageImageResourceHints(pageImageEvidence);
        List<List<BufferedImage>> pageEmbeddedResourceImages = resolvePageEmbeddedResourceImages(interpretation.rawAfpBytes(), pageImageEvidence);
        List<List<BufferedImage>> pageResolvedResourceImages = resolvePageResourceImages(pageImageResourceHints, resourceContext);
        List<List<BufferedImage>> pageResolvedImages = mergeResolvedResourceImages(pageEmbeddedResourceImages, pageResolvedResourceImages);

        try (PDDocument document = new PDDocument()) {
            if (SEMANTIC_LAYOUT_OPT_IN && shouldPreferSemanticLayout(interpretation) && renderSemanticLayout(document, interpretation)) {
                document.save(outputPath.toFile());
                return;
            }
            PtocaRenderResult renderResult = renderWithPtoca(
                document,
                interpretation,
                profile,
                pageDecodedImages,
                pageRawImagePayloads,
                pageResolvedImages
            );
            if (SEMANTIC_LAYOUT_OPT_IN && shouldUseStyledSemanticFallback(interpretation, renderResult)) {
                clearPages(document);
                boolean semanticRendered = renderSemanticLayout(document, interpretation);
                if (!semanticRendered) {
                    // keep any previously rendered native pages if semantic layer cannot be produced
                    clearPages(document);
                    renderResult = renderWithPtoca(
                        document,
                        interpretation,
                        profile,
                        pageDecodedImages,
                        pageRawImagePayloads,
                        pageResolvedImages
                    );
                }
            }
            if (!renderResult.rendered) {
                List<List<String>> pages = extractPageLines(interpretation, profile);
                List<Integer> pageImageCounts = extractPageImageCounts(interpretation.fields());
                if (pages.isEmpty()) {
                    pages = List.of(List.of("AFP conversion produced no text payload."));
                }
                for (int i = 0; i < pages.size(); i++) {
                    int imageCount = i < pageImageCounts.size() ? pageImageCounts.get(i) : 0;
                    List<BufferedImage> decodedImages = i < pageDecodedImages.size() ? pageDecodedImages.get(i) : List.of();
                    List<byte[]> rawPayloads = i < pageRawImagePayloads.size() ? pageRawImagePayloads.get(i) : List.of();
                    List<BufferedImage> resolvedResourceImages = i < pageResolvedImages.size() ? pageResolvedImages.get(i) : List.of();
                    addFallbackPage(document, pages.get(i), imageCount, decodedImages, rawPayloads, resolvedResourceImages);
                }
            }
            document.save(outputPath.toFile());
        }
    }

    private static boolean shouldPreferSemanticLayout(AfpInterpretation interpretation) {
        if (interpretation.pageCount() > 1) {
            return false;
        }
        int semanticWords = interpretation.semantics().textFragments().stream()
            .mapToInt(AfpNativePdfRenderer::wordCount)
            .sum();
        return semanticWords >= 80;
    }

    private static boolean shouldUseStyledSemanticFallback(AfpInterpretation interpretation, PtocaRenderResult renderResult) {
        if (!renderResult.rendered) {
            return false;
        }
        if (interpretation.pageCount() > 1 || renderResult.renderedPages > 1) {
            return false;
        }
        int semanticWords = interpretation.semantics().textFragments().stream()
            .mapToInt(AfpNativePdfRenderer::wordCount)
            .sum();
        boolean largeDocument = semanticWords >= 80;
        return largeDocument;
    }

    private static void clearPages(PDDocument document) {
        while (document.getNumberOfPages() > 0) {
            document.removePage(0);
        }
    }

    private static PtocaRenderResult renderWithPtoca(PDDocument document,
                                                     AfpInterpretation interpretation,
                                                     AfpCodePageProfile profile,
                                                     List<List<BufferedImage>> pageDecodedImages,
                                                     List<List<byte[]>> pageRawImagePayloads,
                                                     List<List<BufferedImage>> pageResolvedResourceImages) {
        byte[] afp = interpretation.rawAfpBytes();
        if (afp.length == 0) {
            return PtocaRenderResult.empty();
        }
        try (AfpInputStream in = new AfpInputStream(new ByteArrayInputStream(afp))) {
            List<TextOp> currentOps = null;
            PtocaState state = new PtocaState();
            int currentImageCount = 0;
            List<ImageOp> currentImageOps = new ArrayList<>();
            ImageState currentImageState = null;
            int currentGraphicCount = 0;
            List<GraphicOp> currentGraphicOps = new ArrayList<>();
            GraphicState currentGraphicState = null;
            int renderedTextOps = 0;
            int renderedPages = 0;
            int imageObjects = 0;
            Set<Integer> usedLocalFontIds = new HashSet<>();
            Map<Integer, PDType1Font> localFontMap = new HashMap<>();
            Map<Integer, Float> localFontSizeMap = new HashMap<>();
            ScopedFontResolver scopedFontResolver = new ScopedFontResolver(localFontMap, localFontSizeMap);
            List<byte[]> ptxPayloads = collectFieldPayloads(interpretation.fields(), interpretation.rawAfpBytes(), "D3EE9B");
            int ptxPayloadCursor = 0;
            int pageCursor = -1;
            SemanticFallbackState semanticFallback = initSemanticFallback(interpretation, profile);
            PageGeometryState activeGeometry = null;
            PageGeometryState currentPageGeometry = null;
            int overlayDepth = 0;
            int resourceDepth = 0;
            int[] paintSequence = new int[] {0};

            while (true) {
                Object sf = in.readStructuredField();
                if (sf == null) {
                    break;
                }
                String name = sf.getClass().getSimpleName();
                try {
                    switch (name) {
                        case "BPG" -> {
                            pageCursor++;
                            currentOps = new ArrayList<>();
                            state = new PtocaState();
                            state.overlay = overlayDepth > 0;
                            state.resourceDepth = resourceDepth;
                            state.pageIndex = Math.max(0, pageCursor + 1);
                            state.sequence = paintSequence[0];
                            currentImageCount = 0;
                            currentImageOps = new ArrayList<>();
                            currentImageState = null;
                            currentGraphicCount = 0;
                            currentGraphicOps = new ArrayList<>();
                            currentGraphicState = null;
                            currentPageGeometry = activeGeometry;
                        }
                        case "EPG" -> {
                            if (currentOps != null) {
                                if (currentImageState != null) {
                                    currentImageOps.add(currentImageState.toImageOp());
                                    currentImageState = null;
                                }
                                if (currentGraphicState != null) {
                                    currentGraphicOps.add(currentGraphicState.toGraphicOp());
                                    currentGraphicState = null;
                                }
                                List<BufferedImage> decodedImages = pageCursor >= 0 && pageCursor < pageDecodedImages.size()
                                    ? pageDecodedImages.get(pageCursor)
                                    : List.of();
                                List<byte[]> rawPayloads = pageCursor >= 0 && pageCursor < pageRawImagePayloads.size()
                                    ? pageRawImagePayloads.get(pageCursor)
                                    : List.of();
                                List<BufferedImage> resolvedResourceImages = pageCursor >= 0 && pageCursor < pageResolvedResourceImages.size()
                                    ? pageResolvedResourceImages.get(pageCursor)
                                    : List.of();
                                renderedTextOps += addPtocaPage(
                                    document,
                                    currentOps,
                                    currentImageCount,
                                    currentImageOps,
                                    currentGraphicCount,
                                    currentGraphicOps,
                                    decodedImages,
                                    rawPayloads,
                                    resolvedResourceImages,
                                    localFontMap,
                                    currentPageGeometry
                                );
                                renderedPages++;
                                currentOps = null;
                            }
                        }
                        case "PGD" -> {
                            if (sf instanceof PGD pgd) {
                                activeGeometry = PageGeometryState.from(pgd);
                                if (currentOps != null && currentPageGeometry == null) {
                                    currentPageGeometry = activeGeometry;
                                }
                            }
                        }
                        case "CFI" -> collectCodedFonts(sf, localFontMap, localFontSizeMap, scopedFontResolver, Math.max(0, pageCursor + 1), resourceDepth);
                        case "PTX" -> {
                            if (currentOps == null) {
                                currentOps = new ArrayList<>();
                                state = new PtocaState();
                            }
                            state.overlay = overlayDepth > 0;
                            state.resourceDepth = resourceDepth;
                            state.pageIndex = Math.max(0, pageCursor + 1);
                            int opCountBefore = currentOps.size();
                            Object csListObj = sf.getClass().getMethod("getCS").invoke(sf);
                            if (csListObj instanceof List<?> csList) {
                                for (Object cs : csList) {
                                    applyTriplet(cs, state, currentOps, profile, scopedFontResolver, usedLocalFontIds, semanticFallback, paintSequence);
                                }
                            }
                            if (currentOps.size() == opCountBefore) {
                                byte[] rawPayload = ptxPayloadCursor < ptxPayloads.size() ? ptxPayloads.get(ptxPayloadCursor) : null;
                                if (rawPayload == null || rawPayload.length == 0) {
                                    rawPayload = invokeByteArrayGetter(sf, "getPayload");
                                }
                                if (rawPayload == null || rawPayload.length == 0) {
                                    rawPayload = invokeByteArrayGetter(sf, "getPAYLOAD");
                                }
                                if (rawPayload != null && rawPayload.length > 0) {
                                    applyRawPtxPayload(rawPayload, state, currentOps, profile, scopedFontResolver, usedLocalFontIds, semanticFallback, paintSequence);
                                }
                            }
                            ptxPayloadCursor++;
                        }
                        case "TRN" -> {
                            if (currentOps == null) {
                                currentOps = new ArrayList<>();
                                state = new PtocaState();
                            }
                            state.overlay = overlayDepth > 0;
                            state.resourceDepth = resourceDepth;
                            state.pageIndex = Math.max(0, pageCursor + 1);
                            if (sf instanceof TRN trn && trn.getTRNDATA() != null && trn.getTRNDATA().length > 0) {
                                List<String> runs = AfpTextDecoders.decodeTextRuns(trn.getTRNDATA(), profile);
                                if (runs.isEmpty()) {
                                    List<String> fragments = AfpTextDecoders.decodeTextBytes(trn.getTRNDATA(), profile);
                                    addDecodedText(state, currentOps, fragments, usedLocalFontIds, semanticFallback, ++paintSequence[0]);
                                } else {
                                    for (String run : runs) {
                                        addDecodedText(state, currentOps, List.of(run), usedLocalFontIds, semanticFallback, ++paintSequence[0]);
                                    }
                                }
                            }
                        }
                        case "BIM" -> {
                            currentImageCount++;
                            imageObjects++;
                            currentImageState = new ImageState();
                            currentImageState.overlay = overlayDepth > 0;
                            currentImageState.resourceDepth = resourceDepth;
                            currentImageState.sequence = ++paintSequence[0];
                            currentImageState.imageIndex = currentImageOps.size();
                        }
                        case "EIM" -> {
                            if (currentImageState != null) {
                                currentImageOps.add(currentImageState.toImageOp());
                                currentImageState = null;
                            }
                        }
                        case "BOC", "EOC", "OBD" -> {
                        }
                        case "BGR" -> {
                            currentGraphicCount++;
                            currentGraphicState = new GraphicState();
                            currentGraphicState.overlay = overlayDepth > 0;
                            currentGraphicState.resourceDepth = resourceDepth;
                            currentGraphicState.sequence = ++paintSequence[0];
                        }
                        case "EGR" -> {
                            currentGraphicCount++;
                            if (currentGraphicState != null) {
                                currentGraphicOps.add(currentGraphicState.toGraphicOp());
                                currentGraphicState = null;
                            }
                        }
                        case "IOB" -> {
                            if (sf instanceof IOB iob) {
                                if (currentImageState == null) {
                                    if (currentGraphicState == null) {
                                        currentImageState = new ImageState();
                                        currentImageState.overlay = overlayDepth > 0;
                                        currentImageState.resourceDepth = resourceDepth;
                                        currentImageState.sequence = ++paintSequence[0];
                                        currentImageState.imageIndex = currentImageOps.size();
                                    }
                                }
                                if (currentImageState != null) {
                                    applyObjectPlacement(currentImageState, iob.getXoaOset(), iob.getYoaOset(), iob.getXoaOrent(), iob.getYoaOrent());
                                } else if (currentGraphicState != null) {
                                    applyGraphicPlacement(currentGraphicState, iob.getXoaOset(), iob.getYoaOset(), iob.getXoaOrent(), iob.getYoaOrent());
                                }
                            }
                        }
                        case "OBP" -> {
                            if (sf instanceof OBP obp) {
                                if (currentImageState == null) {
                                    if (currentGraphicState == null) {
                                        currentImageState = new ImageState();
                                        currentImageState.overlay = overlayDepth > 0;
                                        currentImageState.resourceDepth = resourceDepth;
                                        currentImageState.sequence = ++paintSequence[0];
                                        currentImageState.imageIndex = currentImageOps.size();
                                    }
                                }
                                if (currentImageState != null) {
                                    applyObjectPlacement(currentImageState, obp.getXoaOset(), obp.getYoaOset(), obp.getXoaOrent(), obp.getYoaOrent());
                                } else if (currentGraphicState != null) {
                                    applyGraphicPlacement(currentGraphicState, obp.getXoaOset(), obp.getYoaOset(), obp.getXoaOrent(), obp.getYoaOrent());
                                }
                            }
                        }
                        case "IDD" -> {
                            if (sf instanceof IDD idd) {
                                if (currentImageState == null) {
                                    if (currentGraphicState == null) {
                                        currentImageState = new ImageState();
                                        currentImageState.overlay = overlayDepth > 0;
                                        currentImageState.resourceDepth = resourceDepth;
                                        currentImageState.sequence = ++paintSequence[0];
                                        currentImageState.imageIndex = currentImageOps.size();
                                    }
                                }
                                if (currentImageState != null) {
                                    applyObjectSize(currentImageState, idd.getXSIZE(), idd.getYSIZE());
                                } else if (currentGraphicState != null) {
                                    applyGraphicSize(currentGraphicState, idd.getXSIZE(), idd.getYSIZE());
                                }
                            }
                        }
                        case "IID" -> {
                            if (sf instanceof IID iid) {
                                if (currentImageState == null) {
                                    if (currentGraphicState == null) {
                                        currentImageState = new ImageState();
                                        currentImageState.overlay = overlayDepth > 0;
                                        currentImageState.resourceDepth = resourceDepth;
                                        currentImageState.sequence = ++paintSequence[0];
                                        currentImageState.imageIndex = currentImageOps.size();
                                    }
                                }
                                Integer width = iid.getXSize();
                                Integer height = iid.getYSize();
                                if (width == null || width <= 0) {
                                    width = iid.getXCSizeD();
                                }
                                if (height == null || height <= 0) {
                                    height = iid.getYCSizeD();
                                }
                                if (currentImageState != null) {
                                    applyObjectSize(currentImageState, width, height);
                                } else if (currentGraphicState != null) {
                                    applyGraphicSize(currentGraphicState, width, height);
                                }
                            }
                        }
                        case "BMO" -> overlayDepth++;
                        case "EMO" -> overlayDepth = Math.max(0, overlayDepth - 1);
                        case "BRS" -> resourceDepth++;
                        case "ERS" -> resourceDepth = Math.max(0, resourceDepth - 1);
                        default -> {
                        }
                    }
                } catch (Exception ignored) {
                    // Tolerate malformed/partial object fields and continue rendering.
                }
            }

            if (currentOps != null) {
                if (currentImageState != null) {
                    currentImageOps.add(currentImageState.toImageOp());
                }
                if (currentGraphicState != null) {
                    currentGraphicOps.add(currentGraphicState.toGraphicOp());
                }
                List<BufferedImage> decodedImages = pageCursor >= 0 && pageCursor < pageDecodedImages.size()
                    ? pageDecodedImages.get(pageCursor)
                    : List.of();
                List<byte[]> rawPayloads = pageCursor >= 0 && pageCursor < pageRawImagePayloads.size()
                    ? pageRawImagePayloads.get(pageCursor)
                    : List.of();
                List<BufferedImage> resolvedResourceImages = pageCursor >= 0 && pageCursor < pageResolvedResourceImages.size()
                    ? pageResolvedResourceImages.get(pageCursor)
                    : List.of();
                renderedTextOps += addPtocaPage(
                    document,
                    currentOps,
                    currentImageCount,
                    currentImageOps,
                    currentGraphicCount,
                    currentGraphicOps,
                    decodedImages,
                    rawPayloads,
                    resolvedResourceImages,
                    localFontMap,
                    currentPageGeometry
                );
                renderedPages++;
            }
            return new PtocaRenderResult(renderedPages > 0, renderedTextOps, renderedPages, imageObjects, usedLocalFontIds.size());
        } catch (Exception ignored) {
            return PtocaRenderResult.empty();
        }
    }

    private static void applyTriplet(Object cs,
                                     PtocaState state,
                                     List<TextOp> ops,
                                     AfpCodePageProfile profile,
                                     ScopedFontResolver scopedFontResolver,
                                     Set<Integer> usedLocalFontIds,
                                     SemanticFallbackState semanticFallback,
                                     int[] paintSequence) {
        if (!(cs instanceof Triplet)) {
            return;
        }
        if (cs instanceof AMI ami && ami.getDSPLCMNT() != null) {
            state.inline = ami.getDSPLCMNT();
            return;
        }
        if (cs instanceof RMI rmi && rmi.getINCRMENT() != null) {
            state.inline += rmi.getINCRMENT();
            return;
        }
        if (cs instanceof AMB amb && amb.getDSPLCMNT() != null) {
            state.baseline = amb.getDSPLCMNT();
            return;
        }
        if (cs instanceof RMB rmb && rmb.getINCRMENT() != null) {
            state.baseline += rmb.getINCRMENT();
            return;
        }
        if (cs instanceof SBI sbi && sbi.getINCRMENT() != null && sbi.getINCRMENT() != 0) {
            state.baselineIncrement = sbi.getINCRMENT();
            return;
        }
        if (cs instanceof SVI svi && svi.getINCRMENT() != null && svi.getINCRMENT() != 0) {
            state.inlineIncrement = svi.getINCRMENT();
            return;
        }
        if (cs instanceof SCFL scfl && scfl.getLID() != null) {
            state.localFontId = scfl.getLID();
            ScopedFontResolution resolution = scopedFontResolver.resolve(state.localFontId, state.pageIndex, state.resourceDepth);
            state.resolvedFont = resolution.font;
            state.explicitFontSize = resolution.fontSize;
            return;
        }
        if (cs instanceof TRN trn && trn.getTRNDATA() != null && trn.getTRNDATA().length > 0) {
            List<String> runs = AfpTextDecoders.decodeTextRuns(trn.getTRNDATA(), profile);
            if (runs.isEmpty()) {
                List<String> fragments = AfpTextDecoders.decodeTextBytes(trn.getTRNDATA(), profile);
                addDecodedText(state, ops, fragments, usedLocalFontIds, semanticFallback, ++paintSequence[0]);
            } else {
                for (String run : runs) {
                    addDecodedText(state, ops, List.of(run), usedLocalFontIds, semanticFallback, ++paintSequence[0]);
                }
            }
            return;
        }

        Integer reflectiveLid = invokeIntegerGetter(cs, "getLID");
        if (reflectiveLid != null) {
            state.localFontId = reflectiveLid;
            ScopedFontResolution resolution = scopedFontResolver.resolve(state.localFontId, state.pageIndex, state.resourceDepth);
            state.resolvedFont = resolution.font;
            state.explicitFontSize = resolution.fontSize;
        }
        byte[] reflectiveTextData = invokeByteArrayGetter(cs, "getTRNDATA");
        if (reflectiveTextData != null && reflectiveTextData.length > 0) {
            List<String> runs = AfpTextDecoders.decodeTextRuns(reflectiveTextData, profile);
            if (runs.isEmpty()) {
                List<String> fragments = AfpTextDecoders.decodeTextBytes(reflectiveTextData, profile);
                addDecodedText(state, ops, fragments, usedLocalFontIds, semanticFallback, ++paintSequence[0]);
            } else {
                for (String run : runs) {
                    addDecodedText(state, ops, List.of(run), usedLocalFontIds, semanticFallback, ++paintSequence[0]);
                }
            }
        }
    }

    private static Integer invokeIntegerGetter(Object target, String methodName) {
        if (target == null || methodName == null || methodName.isBlank()) {
            return null;
        }
        try {
            Object value = target.getClass().getMethod(methodName).invoke(target);
            if (value instanceof Integer i) {
                return i;
            }
        } catch (Exception ignored) {
            // best-effort reflective support for vendor-specific PTOCA triplets
        }
        return null;
    }

    private static byte[] invokeByteArrayGetter(Object target, String methodName) {
        if (target == null || methodName == null || methodName.isBlank()) {
            return null;
        }
        try {
            Object value = target.getClass().getMethod(methodName).invoke(target);
            if (value instanceof byte[] bytes) {
                return bytes;
            }
        } catch (Exception ignored) {
            // best-effort reflective support for vendor-specific PTOCA triplets
        }
        return null;
    }

    private static void applyRawPtxPayload(byte[] payload,
                                           PtocaState state,
                                           List<TextOp> ops,
                                           AfpCodePageProfile profile,
                                           ScopedFontResolver scopedFontResolver,
                                           Set<Integer> usedLocalFontIds,
                                           SemanticFallbackState semanticFallback,
                                           int[] paintSequence) {
        if (payload == null || payload.length == 0) {
            return;
        }
        int i = 0;
        while (i < payload.length) {
            PtxControlSequence cs = readPtxControlSequence(payload, i);
            if (cs == null) {
                i++;
                continue;
            }
            int fn = normalizePtxFunction(cs.functionType);
            byte[] data = new byte[Math.max(0, cs.dataLength)];
            if (cs.dataLength > 0) {
                System.arraycopy(payload, cs.dataStart, data, 0, cs.dataLength);
            }
            switch (fn) {
                case PTOCA_FN_AMI -> {
                    Integer v = decodeSigned16(data);
                    if (v != null) {
                        state.inline = v;
                    }
                }
                case PTOCA_FN_RMI -> {
                    Integer v = decodeSigned16(data);
                    if (v != null) {
                        state.inline += v;
                    }
                }
                case PTOCA_FN_AMB -> {
                    Integer v = decodeSigned16(data);
                    if (v != null) {
                        state.baseline = v;
                    }
                }
                case PTOCA_FN_RMB -> {
                    Integer v = decodeSigned16(data);
                    if (v != null) {
                        state.baseline += v;
                    }
                }
                case PTOCA_FN_SBI -> {
                    Integer v = decodeSigned16(data);
                    if (v != null && v != 0) {
                        state.baselineIncrement = v;
                    }
                }
                case PTOCA_FN_SVI -> {
                    Integer v = decodeSigned16(data);
                    if (v != null && v != 0) {
                        state.inlineIncrement = v;
                    }
                }
                case PTOCA_FN_SCFL -> {
                    Integer lid = decodeUnsigned8(data);
                    if (lid != null) {
                        state.localFontId = lid;
                        ScopedFontResolution resolution = scopedFontResolver.resolve(state.localFontId, state.pageIndex, state.resourceDepth);
                        state.resolvedFont = resolution.font;
                        state.explicitFontSize = resolution.fontSize;
                    }
                }
                case PTOCA_FN_TRN, PTOCA_FN_TRN_ALT -> {
                    if (data.length > 0) {
                        List<String> runs = AfpTextDecoders.decodeTextRuns(data, profile);
                        if (runs.isEmpty()) {
                            List<String> fragments = AfpTextDecoders.decodeTextBytes(data, profile);
                            addDecodedText(state, ops, fragments, usedLocalFontIds, semanticFallback, ++paintSequence[0]);
                        } else {
                            for (String run : runs) {
                                addDecodedText(state, ops, List.of(run), usedLocalFontIds, semanticFallback, ++paintSequence[0]);
                            }
                        }
                    }
                }
                default -> {
                    // Ignore unrecognized control functions in strict native mode.
                }
            }
            i += cs.length;
        }
    }

    private static int normalizePtxFunction(int functionType) {
        int normalized = functionType & 0xFF;
        // Observed sample evidence shows short-form PTOCA functions in low range mapping to canonical C0-FF range.
        if (normalized <= 0x3F) {
            normalized |= 0xC0;
        }
        if (normalized == PTOCA_FN_TRN_ALT) {
            return PTOCA_FN_TRN;
        }
        return normalized;
    }

    private static PtxControlSequence readPtxControlSequence(byte[] payload, int offset) {
        if (payload == null || offset < 0 || offset >= payload.length) {
            return null;
        }
        if ((payload[offset] & 0xFF) != PTOCA_CS_INTRODUCER || offset + 2 >= payload.length) {
            return null;
        }
        int len1 = payload[offset + 1] & 0xFF;
        int len2 = ((payload[offset + 1] & 0xFF) << 8) | (payload[offset + 2] & 0xFF);
        boolean oneByteValid = len1 >= 3 && (offset + len1) <= payload.length;
        boolean twoByteValid = len2 >= 4 && (offset + len2) <= payload.length;
        boolean preferTwoByte = payload[offset + 1] == 0 || (!oneByteValid && twoByteValid);

        if (preferTwoByte && twoByteValid) {
            return new PtxControlSequence(len2, payload[offset + 3] & 0xFF, offset + 4, len2 - 4);
        }
        if (oneByteValid) {
            return new PtxControlSequence(len1, payload[offset + 2] & 0xFF, offset + 3, len1 - 3);
        }
        if (twoByteValid) {
            return new PtxControlSequence(len2, payload[offset + 3] & 0xFF, offset + 4, len2 - 4);
        }
        return null;
    }

    private static Integer decodeSigned16(byte[] data) {
        if (data == null || data.length < 2) {
            return null;
        }
        int raw = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        if ((raw & 0x8000) != 0) {
            raw -= 0x10000;
        }
        return raw;
    }

    private static Integer decodeUnsigned8(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        return data[0] & 0xFF;
    }

    private static void addDecodedText(PtocaState state,
                                       List<TextOp> ops,
                                       List<String> fragments,
                                       Set<Integer> usedLocalFontIds,
                                       SemanticFallbackState semanticFallback,
                                       int sequence) {
        List<String> effective = fragments == null ? List.of() : fragments;
        if (effective.isEmpty() || isFallbackCandidateText(effective)) {
            List<String> fallback = semanticFallback.takeNext();
            if (!fallback.isEmpty()) {
                effective = fallback;
            }
        }
        if (effective.isEmpty()) {
            return;
        }
        String joined = String.join(" ", effective).replaceAll("\\s+", " ").trim();
        if (joined.isEmpty()) {
            return;
        }
        float fontSize = estimateFontSize(state);
        List<String> wrapped = wrapTextForPlacement(joined, 96);
        if (wrapped.size() <= 1) {
            ops.add(new TextOp(state.inline, state.baseline, joined, state.localFontId, state.resolvedFont, fontSize, state.overlay, state.resourceDepth, sequence));
        } else {
            int lineBaseline = state.baseline;
            for (int i = 0; i < wrapped.size(); i++) {
                String line = wrapped.get(i);
                ops.add(new TextOp(state.inline, lineBaseline, line, state.localFontId, state.resolvedFont, fontSize, state.overlay, state.resourceDepth, sequence + i));
                lineBaseline += Math.max(1, state.baselineIncrement);
            }
            state.baseline = lineBaseline - Math.max(1, state.baselineIncrement);
            joined = wrapped.get(wrapped.size() - 1);
        }
        usedLocalFontIds.add(state.localFontId);
        int glyphCount = Math.max(1, joined.length());
        int baseAdvance = Math.max(1, glyphCount * state.inlineIncrement);
        int coupledAdvance = Math.max(1, Math.round(glyphCount * Math.max(6f, fontSize * 2.2f)));
        state.inline += Math.min(baseAdvance, coupledAdvance);
    }

    private static List<String> wrapTextForPlacement(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (text.length() <= Math.max(16, maxChars)) {
            return List.of(text);
        }
        List<String> lines = new ArrayList<>();
        String[] words = text.trim().split("\\s+");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            if (line.length() == 0) {
                line.append(word);
                continue;
            }
            if (line.length() + 1 + word.length() <= maxChars) {
                line.append(' ').append(word);
            } else {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines.isEmpty() ? List.of(text) : lines;
    }

    private static int addPtocaPage(PDDocument document,
                                    List<TextOp> ops,
                                    int imageObjectCount,
                                    List<ImageOp> imageOps,
                                    int graphicObjectCount,
                                    List<GraphicOp> graphicOps,
                                    List<BufferedImage> decodedImages,
                                    List<byte[]> rawImagePayloads,
                                    List<BufferedImage> resolvedResourceImages,
                                    Map<Integer, PDType1Font> localFontMap,
                                    PageGeometryState geometry) throws IOException {
        PDPage page = new PDPage(PDRectangle.LETTER);
        document.addPage(page);
        float pageWidth = page.getMediaBox().getWidth();
        float pageHeight = page.getMediaBox().getHeight();
        float marginX = 36f;
        float marginTop = 36f;
        float availableWidth = pageWidth - (marginX * 2);
        float availableHeight = pageHeight - (marginTop * 2);

        int minInline = Integer.MAX_VALUE;
        int maxInline = Integer.MIN_VALUE;
        int minBaseline = Integer.MAX_VALUE;
        int maxBaseline = Integer.MIN_VALUE;
        List<Integer> textInlines = new ArrayList<>();
        List<Integer> textBaselines = new ArrayList<>();
        for (TextOp op : ops) {
            minInline = Math.min(minInline, op.inline);
            maxInline = Math.max(maxInline, op.inline);
            minBaseline = Math.min(minBaseline, op.baseline);
            maxBaseline = Math.max(maxBaseline, op.baseline);
            textInlines.add(op.inline);
            textBaselines.add(op.baseline);
        }
        for (ImageOp imageOp : imageOps) {
            minInline = Math.min(minInline, imageOp.x);
            maxInline = Math.max(maxInline, imageOp.x + imageOp.width);
            minBaseline = Math.min(minBaseline, imageOp.y);
            maxBaseline = Math.max(maxBaseline, imageOp.y + imageOp.height);
        }
        for (GraphicOp graphicOp : graphicOps) {
            minInline = Math.min(minInline, graphicOp.x);
            maxInline = Math.max(maxInline, graphicOp.x + graphicOp.width);
            minBaseline = Math.min(minBaseline, graphicOp.y);
            maxBaseline = Math.max(maxBaseline, graphicOp.y + graphicOp.height);
        }
        if (geometry != null && geometry.valid()) {
            minInline = 0;
            minBaseline = 0;
            maxInline = Math.max(maxInline, geometry.xSize);
            maxBaseline = Math.max(maxBaseline, geometry.ySize);
        }
        if (minInline == Integer.MAX_VALUE) {
            minInline = 0;
            maxInline = 1;
            minBaseline = 0;
            maxBaseline = 1;
        }
        int observedMinInline = minInline;
        int observedMaxInline = maxInline;
        int observedMinBaseline = minBaseline;
        int observedMaxBaseline = maxBaseline;
        if (textInlines.size() >= 20 && textBaselines.size() >= 20) {
            int trimmedMinInline = percentile(textInlines, 0.02);
            int trimmedMaxInline = percentile(textInlines, 0.98);
            int trimmedMinBaseline = percentile(textBaselines, 0.02);
            int trimmedMaxBaseline = percentile(textBaselines, 0.98);
            if (trimmedMaxInline > trimmedMinInline && trimmedMaxBaseline > trimmedMinBaseline) {
                observedMinInline = trimmedMinInline;
                observedMaxInline = trimmedMaxInline;
                observedMinBaseline = trimmedMinBaseline;
                observedMaxBaseline = trimmedMaxBaseline;
            }
        }
        float observedInlineRange = Math.max(1f, observedMaxInline - observedMinInline);
        float observedBaselineRange = Math.max(1f, observedMaxBaseline - observedMinBaseline);
        float observedScaleX = availableWidth / observedInlineRange;
        float observedScaleY = availableHeight / observedBaselineRange;

        float scaleX = observedScaleX;
        float scaleY = observedScaleY;
        if (geometry != null && geometry.valid()) {
            float geoInlineRange = Math.max(1f, geometry.xSize);
            float geoBaselineRange = Math.max(1f, geometry.ySize);
            float geoScaleX = availableWidth / geoInlineRange;
            float geoScaleY = availableHeight / geoBaselineRange;
            if (geometry.xUnits > 0 && geometry.yUnits > 0) {
                float unitRatio = geometry.xUnits / (float) geometry.yUnits;
                float syFromWidth = geoScaleX * unitRatio;
                if ((geoBaselineRange * syFromWidth) <= availableHeight) {
                    geoScaleY = syFromWidth;
                } else {
                    geoScaleY = availableHeight / geoBaselineRange;
                    geoScaleX = geoScaleY / unitRatio;
                }
            }
            float inlineCoverage = clamp(observedInlineRange / geoInlineRange, 0f, 1f);
            float baselineCoverage = clamp(observedBaselineRange / geoBaselineRange, 0f, 1f);
            float observedWeight = clamp((inlineCoverage + baselineCoverage) * 0.5f, 0.15f, 0.9f);
            scaleX = (observedScaleX * observedWeight) + (geoScaleX * (1f - observedWeight));
            scaleY = (observedScaleY * observedWeight) + (geoScaleY * (1f - observedWeight));
            minInline = 0;
            minBaseline = 0;
        }
        // Keep scaling in reasonable band to avoid overfitting noisy coordinates.
        scaleX = clamp(scaleX, 0.015f, 1.4f);
        scaleY = clamp(scaleY, 0.015f, 1.4f);
        int rendered = 0;

        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            List<PagePaintOp> paintOrder = buildPaintOrder(ops, imageOps, graphicOps);
            int drawnImages = 0;

            for (boolean overlayLayer : new boolean[] {false, true}) {
                for (PagePaintOp paintOp : paintOrder) {
                    if (paintOp.overlay != overlayLayer) {
                        continue;
                    }
                    switch (paintOp.kind) {
                        case TEXT -> rendered += drawTextOp(
                            content,
                            pageWidth,
                            pageHeight,
                            paintOp.textOp,
                            localFontMap,
                            minInline,
                            minBaseline,
                            scaleX,
                            scaleY,
                            marginX,
                            marginTop
                        );
                        case IMAGE -> {
                            boolean imageDrawn = drawDecodedImage(
                                document,
                                content,
                                pageWidth,
                                pageHeight,
                                paintOp.imageOp,
                                decodedImages,
                                rawImagePayloads,
                                resolvedResourceImages,
                                minInline,
                                minBaseline,
                                scaleX,
                                scaleY,
                                marginX,
                                marginTop
                            );
                            if (imageDrawn) {
                                drawnImages++;
                            } else {
                                int idx = paintOp.imageOp == null ? -1 : Math.max(0, paintOp.imageOp.imageIndex);
                                int decodedCount = decodedImages == null ? 0 : decodedImages.size();
                                int rawCount = rawImagePayloads == null ? 0 : rawImagePayloads.size();
                                boolean hasImageEvidence = idx >= 0 && IMAGE_RESOLUTION.hasEvidenceAt(
                                    decodedImages,
                                    resolvedResourceImages,
                                    rawImagePayloads,
                                    idx
                                );
                                if (hasImageEvidence) {
                                    drawImageObjectPlaceholders(
                                        content,
                                        pageWidth,
                                        pageHeight,
                                        1,
                                        List.of(paintOp.imageOp),
                                        minInline,
                                        minBaseline,
                                        scaleX,
                                        scaleY,
                                        marginX,
                                        marginTop
                                    );
                                }
                            }
                        }
                        case GRAPHIC -> drawGraphicPrimitive(
                            content,
                            pageWidth,
                            pageHeight,
                            paintOp.graphicOp,
                            minInline,
                            minBaseline,
                            scaleX,
                            scaleY,
                            marginX,
                            marginTop
                        );
                    }
                }
            }
            boolean hasPageImageEvidence = IMAGE_RESOLUTION.hasAnyDecodedImages(decodedImages)
                || IMAGE_RESOLUTION.hasAnyRawPayloads(rawImagePayloads)
                || IMAGE_RESOLUTION.hasAnyResolvedImages(resolvedResourceImages);
            if (drawnImages == 0 && imageObjectCount > 0 && (imageOps == null || imageOps.isEmpty()) && hasPageImageEvidence) {
                drawImageObjectPlaceholders(
                    content,
                    pageWidth,
                    pageHeight,
                    imageObjectCount,
                    List.of(),
                    minInline,
                    minBaseline,
                    scaleX,
                    scaleY,
                    marginX,
                    marginTop
                );
                drawnImages = Math.max(1, drawnImages);
            }
            if (graphicObjectCount > 0 && (graphicOps == null || graphicOps.isEmpty())) {
                drawGraphicObjectPlaceholders(
                    content,
                    pageWidth,
                    pageHeight,
                    graphicObjectCount,
                    List.of(),
                    minInline,
                    minBaseline,
                    scaleX,
                    scaleY,
                    marginX,
                    marginTop
                );
            }
        }
        return rendered;
    }

    private static List<List<String>> extractPageLines(AfpInterpretation interpretation, AfpCodePageProfile profile) {
        List<List<String>> pages = new ArrayList<>();
        List<String> currentLines = null;
        List<String> semantic = interpretation.semantics().textFragments();
        int semanticCursor = 0;
        int remainingLowSignalFields = 0;
        Map<Long, List<String>> decodedByOffset = new HashMap<>();

        for (AfpStructuredField field : interpretation.fields()) {
            String kind = SF_NAMES.getOrDefault(field.sfIdHex(), "UNKNOWN");
            if ("PTX".equals(kind) || "TRN".equals(kind)) {
                List<String> decoded = AfpTextDecoders.decodeTextFragments(field, profile).fragments();
                decodedByOffset.put(field.offset(), decoded);
                if (isFallbackCandidateText(decoded)) {
                    remainingLowSignalFields++;
                }
            }
        }

        for (AfpStructuredField field : interpretation.fields()) {
            String kind = SF_NAMES.getOrDefault(field.sfIdHex(), "UNKNOWN");
            switch (kind) {
                case "BPG" -> {
                    currentLines = new ArrayList<>();
                    pages.add(currentLines);
                }
                case "EPG" -> currentLines = null;
                case "PTX", "TRN" -> {
                    if (currentLines == null) {
                        currentLines = new ArrayList<>();
                        pages.add(currentLines);
                    }
                    List<String> fragments = decodedByOffset.getOrDefault(field.offset(), List.of());
                    if (isFallbackCandidateText(fragments)) {
                        int remainingSemantic = Math.max(0, semantic.size() - semanticCursor);
                        int take = remainingLowSignalFields <= 1
                            ? remainingSemantic
                            : Math.max(1, (int) Math.ceil((double) remainingSemantic / remainingLowSignalFields));
                        if (take > 0 && semanticCursor < semantic.size() && semanticCursor + take <= semantic.size()) {
                            fragments = List.copyOf(semantic.subList(semanticCursor, semanticCursor + take));
                            semanticCursor += take;
                        }
                        remainingLowSignalFields = Math.max(0, remainingLowSignalFields - 1);
                    }
                    appendFragmentsAsLines(currentLines, fragments);
                }
                default -> {
                }
            }
        }

        int lineCount = 0;
        for (List<String> page : pages) {
            lineCount += page.size();
        }
        if (lineCount == 0 && !semantic.isEmpty()) {
            List<String> target = pages.isEmpty() ? new ArrayList<>() : pages.get(0);
            if (pages.isEmpty()) {
                pages.add(target);
            }
            appendFragmentsAsLines(target, semantic);
        }
        return pages;
    }

    private static void appendFragmentsAsLines(List<String> target, List<String> fragments) {
        if (fragments == null || fragments.isEmpty()) {
            return;
        }
        String joined = String.join(" ", fragments).replaceAll("\\s+", " ").trim();
        if (joined.isEmpty()) {
            return;
        }
        int maxChars = 95;
        for (String paragraph : joined.split("(?<=[.!?])\\s+")) {
            if (paragraph.isBlank()) {
                continue;
            }
            String p = paragraph.trim();
            while (p.length() > maxChars) {
                int breakAt = p.lastIndexOf(' ', maxChars);
                if (breakAt < 20) {
                    breakAt = maxChars;
                }
                target.add(p.substring(0, breakAt).trim());
                p = p.substring(breakAt).trim();
            }
            if (!p.isEmpty()) {
                target.add(p);
            }
        }
    }

    private static int wordCount(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        String[] parts = value.trim().split("[^\\p{L}\\p{N}]+");
        int count = 0;
        for (String part : parts) {
            if (!part.isBlank()) {
                count++;
            }
        }
        return count;
    }

    private static List<Integer> extractPageImageCounts(List<AfpStructuredField> fields) {
        List<Integer> counts = new ArrayList<>();
        int current = -1;
        for (AfpStructuredField field : fields) {
            String kind = SF_NAMES.getOrDefault(field.sfIdHex(), "UNKNOWN");
            if ("BPG".equals(kind)) {
                counts.add(0);
                current = counts.size() - 1;
                continue;
            }
            if ("EPG".equals(kind)) {
                current = -1;
                continue;
            }
            if (current >= 0 && isImageKind(kind)) {
                counts.set(current, counts.get(current) + 1);
            }
        }
        return counts;
    }

    private static boolean isImageKind(String kind) {
        return "BIM".equals(kind);
    }

    private static boolean renderSemanticLayout(PDDocument document, AfpInterpretation interpretation) {
        try {
            AfpDocumentLayout layout = AfpLayoutInterpreter.infer(interpretation);
            if (layout == null) {
                return false;
            }

            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            float pageWidth = page.getMediaBox().getWidth();
            float pageHeight = page.getMediaBox().getHeight();

            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                PDType1Font titleFont = PDType1Font.HELVETICA_BOLD;
                PDType1Font bodyFont = PDType1Font.HELVETICA;
                PDType1Font boldFont = PDType1Font.HELVETICA_BOLD;

                float marginX = 50f;
                float y = pageHeight - 58f;

                content.setNonStrokingColor(new Color(15, 122, 149));
                content.beginText();
                content.setFont(titleFont, 24f);
                content.newLineAtOffset(marginX, y);
                content.showText(sanitizeForFont(titleFont, layout.title()));
                content.endText();
                y -= 26f;

                content.setNonStrokingColor(Color.DARK_GRAY);
                y = drawWrappedText(content, bodyFont, 10f, marginX, y,
                    "source=" + interpretation.sourceLabel() + " | fields="
                        + interpretation.structuredFieldCount() + " | pages=" + interpretation.pageCount(),
                    pageWidth - (marginX * 2), 12f);
                y -= 10f;

                y = drawWrappedText(content, boldFont, 11f, marginX, y, layout.name(), pageWidth - (marginX * 2), 13f);
                y = drawWrappedText(content, bodyFont, 10f, marginX, y, layout.addressLine1(), pageWidth - (marginX * 2), 12f);
                y = drawWrappedText(content, bodyFont, 10f, marginX, y, layout.addressLine2(), pageWidth - (marginX * 2), 12f);
                y -= 10f;

                y = drawWrappedText(content, bodyFont, 10f, marginX, y, layout.introParagraph(), pageWidth - (marginX * 2), 13f);
                y -= 6f;
                y = drawWrappedText(content, bodyFont, 10f, marginX, y, layout.bridgeText(), pageWidth - (marginX * 2), 13f);
                y -= 10f;

                float[] cols = new float[] {marginX, marginX + 165f, marginX + 390f};
                float[] widths = new float[] {160f, 220f, pageWidth - marginX - (marginX + 390f)};
                float tableTop = y;

                content.setNonStrokingColor(new Color(31, 134, 165));
                content.addRect(marginX, tableTop - 20f, pageWidth - (marginX * 2), 20f);
                content.fill();
                content.setNonStrokingColor(Color.WHITE);
                drawTextAt(content, boldFont, 10f, cols[0] + 4f, tableTop - 14f, "Options");
                drawTextAt(content, boldFont, 10f, cols[1] + 4f, tableTop - 14f, "What does this option mean?");
                drawTextAt(content, boldFont, 10f, cols[2] + 4f, tableTop - 14f, "Key points");

                float rowY = tableTop - 20f;
                content.setStrokingColor(new Color(208, 216, 222));
                content.setNonStrokingColor(Color.DARK_GRAY);

                for (AfpOptionRow row : layout.options()) {
                    float h1 = estimateHeight(row.option(), widths[0] - 8f, 9f, bodyFont, 12f);
                    float h2 = estimateHeight(row.meaning(), widths[1] - 8f, 9f, bodyFont, 12f);
                    float h3 = estimateHeight(row.keyPoints(), widths[2] - 8f, 9f, bodyFont, 12f);
                    float rowH = Math.max(26f, Math.max(h1, Math.max(h2, h3)) + 8f);

                    content.addRect(marginX, rowY - rowH, pageWidth - (marginX * 2), rowH);
                    content.stroke();
                    content.moveTo(cols[1], rowY);
                    content.lineTo(cols[1], rowY - rowH);
                    content.stroke();
                    content.moveTo(cols[2], rowY);
                    content.lineTo(cols[2], rowY - rowH);
                    content.stroke();

                    drawWrappedText(content, bodyFont, 9f, cols[0] + 4f, rowY - 12f, row.option(), widths[0] - 8f, 12f);
                    drawWrappedText(content, bodyFont, 9f, cols[1] + 4f, rowY - 12f, row.meaning(), widths[1] - 8f, 12f);
                    drawWrappedText(content, bodyFont, 9f, cols[2] + 4f, rowY - 12f, row.keyPoints(), widths[2] - 8f, 12f);
                    rowY -= rowH;
                }

                y = rowY - 16f;
                content.setNonStrokingColor(new Color(15, 122, 149));
                y = drawWrappedText(content, boldFont, 11f, marginX, y, "Understand what's right for you", pageWidth - (marginX * 2), 13f);
                content.setNonStrokingColor(Color.DARK_GRAY);
                drawWrappedText(content, bodyFont, 10f, marginX, y, layout.actionText(), pageWidth - (marginX * 2), 13f);
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void drawTextAt(PDPageContentStream content, PDType1Font font, float fontSize, float x, float y, String text) throws IOException {
        content.beginText();
        content.setFont(font, fontSize);
        content.newLineAtOffset(x, y);
        content.showText(sanitizeForFont(font, text));
        content.endText();
    }

    private static float drawWrappedText(PDPageContentStream content,
                                         PDType1Font font,
                                         float fontSize,
                                         float x,
                                         float y,
                                         String text,
                                         float maxWidth,
                                         float leading) throws IOException {
        if (text == null || text.isBlank()) {
            return y;
        }
        for (String line : wrapText(text, maxWidth, font, fontSize)) {
            drawTextAt(content, font, fontSize, x, y, line);
            y -= leading;
        }
        return y;
    }

    private static float estimateHeight(String text,
                                        float maxWidth,
                                        float fontSize,
                                        PDType1Font font,
                                        float leading) throws IOException {
        int lineCount = wrapText(text == null ? "" : text, maxWidth, font, fontSize).size();
        return lineCount * leading;
    }

    private static List<String> wrapText(String text, float maxWidth, PDType1Font font, float fontSize) throws IOException {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return lines;
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        StringBuilder line = new StringBuilder();
        for (String word : normalized.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            float width = font.getStringWidth(sanitizeForFont(font, candidate)) / 1000f * fontSize;
            if (width <= maxWidth || line.isEmpty()) {
                line.setLength(0);
                line.append(candidate);
            } else {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static void addFallbackPage(PDDocument document,
                                        List<String> lines,
                                        int imageObjectCount,
                                        List<BufferedImage> decodedImages,
                                        List<byte[]> rawImagePayloads,
                                        List<BufferedImage> resolvedResourceImages) throws IOException {
        PDPage page = new PDPage(PDRectangle.LETTER);
        document.addPage(page);

        PDType1Font font = PDType1Font.HELVETICA;
        float fontSize = 10f;
        float leading = 12f;
        float marginLeft = 48f;
        float marginTop = 48f;
        float y = page.getMediaBox().getHeight() - marginTop;

        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            int drawnFallbackImages = drawFallbackDecodedImages(
                document,
                content,
                page.getMediaBox().getWidth(),
                page.getMediaBox().getHeight(),
                decodedImages,
                rawImagePayloads,
                resolvedResourceImages
            );
            if (drawnFallbackImages == 0 && imageObjectCount > 0) {
                drawImageObjectPlaceholders(
                    content,
                    page.getMediaBox().getWidth(),
                    page.getMediaBox().getHeight(),
                    imageObjectCount,
                    List.of(),
                    0,
                    0,
                    0f,
                    0f,
                    0f,
                    0f
                );
            }
            content.beginText();
            content.setFont(font, fontSize);
            content.newLineAtOffset(marginLeft, y);

            boolean first = true;
            for (String line : lines) {
                if (!first) {
                    content.newLineAtOffset(0, -leading);
                }
                content.showText(sanitizeForFont(font, line));
                first = false;
            }
            content.endText();
        }
    }

    private static int drawFallbackDecodedImages(PDDocument document,
                                                 PDPageContentStream content,
                                                 float pageWidth,
                                                 float pageHeight,
                                                 List<BufferedImage> decodedImages,
                                                 List<byte[]> rawImagePayloads,
                                                 List<BufferedImage> resolvedResourceImages) throws IOException {
        boolean hasDecoded = IMAGE_RESOLUTION.hasAnyDecodedImages(decodedImages);
        boolean hasRaw = IMAGE_RESOLUTION.hasAnyRawPayloads(rawImagePayloads);
        if (!hasDecoded && !hasRaw && !IMAGE_RESOLUTION.hasAnyResolvedImages(resolvedResourceImages)) {
            return 0;
        }
        float x = pageWidth - 160f;
        float y = pageHeight - 140f;
        int count = Math.max(
            decodedImages == null ? 0 : decodedImages.size(),
            Math.max(rawImagePayloads == null ? 0 : rawImagePayloads.size(), resolvedResourceImages == null ? 0 : resolvedResourceImages.size())
        );
        int drawn = 0;
        for (int i = 0; i < count && i < 3; i++) {
            ImageResolutionService.SelectedImage selected = IMAGE_RESOLUTION.selectForFallbackTile(
                decodedImages,
                resolvedResourceImages,
                rawImagePayloads,
                i
            );
            BufferedImage image = selected.image();
            if (image == null) {
                continue;
            }
            PDImageXObject imageObject = LosslessFactory.createFromImage(document, image);
            content.drawImage(imageObject, x, y - (i * 84f), 120f, 64f);
            drawn++;
        }
        return drawn;
    }

    private static List<PagePaintOp> buildPaintOrder(List<TextOp> textOps, List<ImageOp> imageOps, List<GraphicOp> graphicOps) {
        List<PagePaintOp> ordered = new ArrayList<>();
        if (textOps != null) {
            for (TextOp op : textOps) {
                ordered.add(PagePaintOp.ofText(op));
            }
        }
        if (imageOps != null) {
            for (ImageOp op : imageOps) {
                ordered.add(PagePaintOp.ofImage(op));
            }
        }
        if (graphicOps != null) {
            for (GraphicOp op : graphicOps) {
                ordered.add(PagePaintOp.ofGraphic(op));
            }
        }
        ordered.sort(
            Comparator.comparingInt((PagePaintOp op) -> op.resourceDepth)
                .thenComparingInt(op -> op.sequence)
                .thenComparingInt(op -> op.kindOrder)
        );
        return ordered;
    }

    private static boolean drawDecodedImage(PDDocument document,
                                            PDPageContentStream content,
                                            float pageWidth,
                                            float pageHeight,
                                            ImageOp op,
                                            List<BufferedImage> decodedImages,
                                            List<byte[]> rawImagePayloads,
                                            List<BufferedImage> resolvedResourceImages,
                                            int minInline,
                                            int minBaseline,
                                            float scaleX,
                                            float scaleY,
                                            float marginX,
                                            float marginTop) throws IOException {
        if (op == null) {
            return false;
        }
        int idx = Math.max(0, op.imageIndex);
        ImageResolutionService.SelectedImage selected = IMAGE_RESOLUTION.selectForImageOp(
            decodedImages,
            resolvedResourceImages,
            rawImagePayloads,
            idx,
            Math.max(1, op.width),
            Math.max(1, op.height)
        );
        BufferedImage image = selected.image();
        if (image == null) {
            return false;
        }
        ImagePlacement placement = computeImagePlacement(pageWidth, pageHeight, op, minInline, minBaseline, scaleX, scaleY, marginX, marginTop);
        PDImageXObject imageObject = LosslessFactory.createFromImage(document, image);
        drawImageWithOrientation(content, imageObject, placement, op.orientation);
        return true;
    }


    private static int drawTextOp(PDPageContentStream content,
                                  float pageWidth,
                                  float pageHeight,
                                  TextOp op,
                                  Map<Integer, PDType1Font> localFontMap,
                                  int minInline,
                                  int minBaseline,
                                  float scaleX,
                                  float scaleY,
                                  float marginX,
                                  float marginTop) throws IOException {
        if (op == null) {
            return 0;
        }
        PDType1Font baseFont = op.resolvedFont != null ? op.resolvedFont : fontForLocalId(op.localFontId, localFontMap);
        baseFont = chooseBestEncodableFont(baseFont, op.text);
        RunStyle style = styleForRun(op.text, baseFont, op.fontSize);
        PDType1Font font = style.font;
        String text = sanitizeForFont(font, op.text);
        if (text.isBlank()) {
            return 0;
        }
        float x = marginX + ((op.inline - minInline) * scaleX);
        float y = pageHeight - marginTop - ((op.baseline - minBaseline) * scaleY);
        if (y < 24f) {
            y = 24f;
        } else if (y > pageHeight - 24f) {
            y = pageHeight - 24f;
        }
        if (x < 24f) {
            x = 24f;
        }
        content.setNonStrokingColor(style.color);
        content.beginText();
        content.setFont(font, style.fontSize);
        content.setCharacterSpacing(style.characterSpacing);
        content.newLineAtOffset(x, y);
        content.showText(text);
        content.endText();
        return 1;
    }

    private static void drawGraphicPrimitive(PDPageContentStream content,
                                             float pageWidth,
                                             float pageHeight,
                                             GraphicOp op,
                                             int minInline,
                                             int minBaseline,
                                             float scaleX,
                                             float scaleY,
                                             float marginX,
                                             float marginTop) throws IOException {
        if (op == null) {
            return;
        }
        float sourceWidth = isQuarterTurn(op.orientation) ? op.height : op.width;
        float sourceHeight = isQuarterTurn(op.orientation) ? op.width : op.height;
        float x = marginX + ((op.x - minInline) * scaleX);
        float yTop = pageHeight - marginTop - ((op.y - minBaseline) * scaleY);
        float width = Math.max(4f, sourceWidth * scaleX);
        float height = Math.max(2f, sourceHeight * scaleY);
        if (x < 20f) {
            x = 20f;
        }
        if (x + width > pageWidth - 20f) {
            width = Math.max(2f, pageWidth - x - 20f);
        }
        float yBottom = yTop - height;
        if (yBottom < 20f) {
            yBottom = 20f;
        }
        if (yBottom + height > pageHeight - 20f) {
            yBottom = pageHeight - 20f - height;
        }

        content.setStrokingColor(new Color(84, 92, 101));
        content.setNonStrokingColor(new Color(220, 227, 234));
        if (height <= 3.5f || width >= (height * 12f)) {
            float y = yBottom + (height / 2f);
            content.moveTo(x, y);
            content.lineTo(x + width, y);
            content.stroke();
            return;
        }
        content.addRect(x, yBottom, width, height);
        content.fillAndStroke();
    }

    private static int drawDecodedImages(PDDocument document,
                                         PDPageContentStream content,
                                         float pageWidth,
                                         float pageHeight,
                                         List<ImageOp> imageOps,
                                         List<BufferedImage> decodedImages,
                                         List<byte[]> rawImagePayloads,
                                         int minInline,
                                         int minBaseline,
                                         float scaleX,
                                         float scaleY,
                                         float marginX,
                                         float marginTop,
                                         boolean overlayOnly) throws IOException {
        if (imageOps == null || imageOps.isEmpty()) {
            return 0;
        }
        int decodedCount = decodedImages == null ? 0 : decodedImages.size();
        int rawCount = rawImagePayloads == null ? 0 : rawImagePayloads.size();
        int count = Math.min(imageOps.size(), Math.max(decodedCount, rawCount));
        int drawn = 0;
        for (int i = 0; i < count; i++) {
            ImageOp op = imageOps.get(i);
            if (op.overlay != overlayOnly) {
                continue;
            }
            ImageResolutionService.SelectedImage selected = IMAGE_RESOLUTION.selectForImageOp(
                decodedImages,
                List.of(),
                rawImagePayloads,
                i,
                Math.max(1, op.width),
                Math.max(1, op.height)
            );
            BufferedImage image = selected.image();
            if (image == null) {
                continue;
            }
            ImagePlacement placement = computeImagePlacement(pageWidth, pageHeight, op, minInline, minBaseline, scaleX, scaleY, marginX, marginTop);
            PDImageXObject imageObject = LosslessFactory.createFromImage(document, image);
            drawImageWithOrientation(content, imageObject, placement, op.orientation);
            drawn++;
        }
        return drawn;
    }

    private static ImagePlacement computeImagePlacement(float pageWidth,
                                                        float pageHeight,
                                                        ImageOp op,
                                                        int minInline,
                                                        int minBaseline,
                                                        float scaleX,
                                                        float scaleY,
                                                        float marginX,
                                                        float marginTop) {
        float x = marginX + ((op.x - minInline) * scaleX);
        float yTop = pageHeight - marginTop - ((op.y - minBaseline) * scaleY);
        float sourceWidth = isQuarterTurn(op.orientation) ? op.height : op.width;
        float sourceHeight = isQuarterTurn(op.orientation) ? op.width : op.height;
        float width = Math.max(18f, sourceWidth * scaleX);
        float height = Math.max(14f, sourceHeight * scaleY);
        if (x < 20f) {
            x = 20f;
        }
        if (x + width > pageWidth - 20f) {
            width = Math.max(10f, pageWidth - x - 20f);
        }
        float yBottom = yTop - height;
        if (yBottom < 20f) {
            yBottom = 20f;
        }
        if (yBottom + height > pageHeight - 20f) {
            yBottom = pageHeight - 20f - height;
        }
        return new ImagePlacement(x, yBottom, width, height);
    }

    private static void drawImageWithOrientation(PDPageContentStream content,
                                                 PDImageXObject imageObject,
                                                 ImagePlacement placement,
                                                 int orientation) throws IOException {
        int normalized = Math.floorMod(orientation, 360);
        if (normalized == 0) {
            content.drawImage(imageObject, placement.x, placement.yBottom, placement.width, placement.height);
            return;
        }
        Matrix matrix = switch (normalized) {
            case 90 -> new Matrix(0, placement.height, -placement.width, 0, placement.x + placement.width, placement.yBottom);
            case 180 -> new Matrix(-placement.width, 0, 0, -placement.height, placement.x + placement.width, placement.yBottom + placement.height);
            case 270 -> new Matrix(0, -placement.height, placement.width, 0, placement.x, placement.yBottom + placement.height);
            default -> new Matrix(placement.width, 0, 0, placement.height, placement.x, placement.yBottom);
        };
        content.saveGraphicsState();
        content.addRect(placement.x, placement.yBottom, placement.width, placement.height);
        content.clip();
        content.drawImage(imageObject, matrix);
        content.restoreGraphicsState();
    }

    private static int drawTextOps(PDPageContentStream content,
                                   float pageWidth,
                                   float pageHeight,
                                   List<TextOp> sortedOps,
                                   Map<Integer, PDType1Font> localFontMap,
                                   int minInline,
                                   int minBaseline,
                                   float scaleX,
                                   float scaleY,
                                   float marginX,
                                   float marginTop,
                                   boolean overlayOnly) throws IOException {
        content.setNonStrokingColor(Color.DARK_GRAY);
        int rendered = 0;
        for (TextOp op : sortedOps) {
            if (op.overlay != overlayOnly) {
                continue;
            }
            PDType1Font baseFont = op.resolvedFont != null ? op.resolvedFont : fontForLocalId(op.localFontId, localFontMap);
            baseFont = chooseBestEncodableFont(baseFont, op.text);
            RunStyle style = styleForRun(op.text, baseFont, op.fontSize);
            PDType1Font font = style.font;
            String text = sanitizeForFont(font, op.text);
            if (text.isBlank()) {
                continue;
            }
            float x = marginX + ((op.inline - minInline) * scaleX);
            float y = pageHeight - marginTop - ((op.baseline - minBaseline) * scaleY);
            if (y < 24f) {
                y = 24f;
            } else if (y > pageHeight - 24f) {
                y = pageHeight - 24f;
            }
            if (x < 24f) {
                x = 24f;
            }
            content.setNonStrokingColor(style.color);
            content.beginText();
            content.setFont(font, style.fontSize);
            content.setCharacterSpacing(style.characterSpacing);
            content.newLineAtOffset(x, y);
            content.showText(text);
            content.endText();
            rendered++;
        }
        return rendered;
    }

    private static RunStyle styleForRun(String rawText, PDType1Font baseFont, float baseSize) {
        String text = rawText == null ? "" : rawText.trim();
        if (text.isEmpty()) {
            return metricTunedStyle(baseFont, baseSize, text, Color.BLACK);
        }
        if (!Boolean.getBoolean("afp.render.inferStyles")) {
            return metricTunedStyle(baseFont, baseSize, text, Color.BLACK);
        }
        String lower = text.toLowerCase();
        int letters = 0;
        int uppercase = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) {
                    uppercase++;
                }
            }
        }
        float upperRatio = letters == 0 ? 0f : (uppercase / (float) letters);
        int words = text.split("\\s+").length;
        boolean headingLike = words <= 10 && text.length() <= 80 && (upperRatio >= 0.55f || text.endsWith(":"));
        boolean tableLabel = lower.startsWith("options")
            || lower.startsWith("what does this option mean")
            || lower.startsWith("key points")
            || lower.startsWith("understand what's right for you");
        boolean linkLike = lower.contains("www.") || lower.contains("http://") || lower.contains("https://");

        if (tableLabel) {
            return metricTunedStyle(PDType1Font.HELVETICA_BOLD, Math.max(baseSize, 11f), text, new Color(15, 122, 149));
        }
        if (headingLike) {
            return metricTunedStyle(PDType1Font.HELVETICA_BOLD, Math.max(baseSize + 1f, 11f), text, new Color(31, 134, 165));
        }
        if (linkLike) {
            return metricTunedStyle(baseFont, Math.max(baseSize, 9f), text, new Color(42, 96, 166));
        }
        return metricTunedStyle(baseFont, baseSize, text, Color.BLACK);
    }

    private static RunStyle metricTunedStyle(PDType1Font font, float baseSize, String text, Color color) {
        float scaledSize = clamp(baseSize * metricSizeScale(font), 6f, 18f);
        float spacing = metricCharacterSpacing(font);
        if (text != null && !text.isBlank()) {
            float upperRatio = uppercaseRatio(text);
            if (upperRatio >= 0.75f) {
                spacing += 0.03f;
            } else if (upperRatio <= 0.08f) {
                spacing -= 0.01f;
            }
        }
        spacing = clamp(spacing, -0.25f, 0.25f);
        return new RunStyle(font, scaledSize, color, spacing);
    }

    private static float metricSizeScale(PDType1Font font) {
        if (font == PDType1Font.COURIER || font == PDType1Font.COURIER_BOLD
            || font == PDType1Font.COURIER_OBLIQUE || font == PDType1Font.COURIER_BOLD_OBLIQUE) {
            return 1.0f;
        }
        if (font == PDType1Font.TIMES_ROMAN || font == PDType1Font.TIMES_BOLD
            || font == PDType1Font.TIMES_ITALIC || font == PDType1Font.TIMES_BOLD_ITALIC) {
            return 1.02f;
        }
        return 0.97f;
    }

    private static float metricCharacterSpacing(PDType1Font font) {
        if (font == PDType1Font.COURIER || font == PDType1Font.COURIER_BOLD
            || font == PDType1Font.COURIER_OBLIQUE || font == PDType1Font.COURIER_BOLD_OBLIQUE) {
            return 0f;
        }
        if (font == PDType1Font.TIMES_ROMAN || font == PDType1Font.TIMES_BOLD
            || font == PDType1Font.TIMES_ITALIC || font == PDType1Font.TIMES_BOLD_ITALIC) {
            return -0.03f;
        }
        return -0.06f;
    }

    private static float uppercaseRatio(String text) {
        if (text == null || text.isBlank()) {
            return 0f;
        }
        int letters = 0;
        int uppercase = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) {
                    uppercase++;
                }
            }
        }
        return letters == 0 ? 0f : (uppercase / (float) letters);
    }

    private static List<ImageOp> filterImageOps(List<ImageOp> imageOps, boolean overlayOnly) {
        List<ImageOp> filtered = new ArrayList<>();
        if (imageOps == null) {
            return filtered;
        }
        for (ImageOp op : imageOps) {
            if (op.overlay == overlayOnly) {
                filtered.add(op);
            }
        }
        return filtered;
    }

    private static List<GraphicOp> filterGraphicOps(List<GraphicOp> graphicOps, boolean overlayOnly) {
        List<GraphicOp> filtered = new ArrayList<>();
        if (graphicOps == null) {
            return filtered;
        }
        for (GraphicOp op : graphicOps) {
            if (op.overlay == overlayOnly) {
                filtered.add(op);
            }
        }
        return filtered;
    }

    private static void drawImageObjectPlaceholders(PDPageContentStream content,
                                                    float pageWidth,
                                                    float pageHeight,
                                                    int imageObjectCount,
                                                    List<ImageOp> imageOps,
                                                    int minInline,
                                                    int minBaseline,
                                                    float scaleX,
                                                    float scaleY,
                                                    float marginX,
                                                    float marginTop) throws IOException {
        if (imageOps != null && !imageOps.isEmpty()) {
            content.setStrokingColor(new Color(232, 236, 240));
            int drawn = 0;
            for (ImageOp op : imageOps) {
                if (drawn >= 8) {
                    break;
                }
                float x = marginX + ((op.x - minInline) * scaleX);
                float yTop = pageHeight - marginTop - ((op.y - minBaseline) * scaleY);
                float sourceWidth = isQuarterTurn(op.orientation) ? op.height : op.width;
                float sourceHeight = isQuarterTurn(op.orientation) ? op.width : op.height;
                float width = Math.max(18f, sourceWidth * scaleX);
                float height = Math.max(14f, sourceHeight * scaleY);
                if (x < 20f) {
                    x = 20f;
                }
                if (x + width > pageWidth - 20f) {
                    width = Math.max(10f, pageWidth - x - 20f);
                }
                float yBottom = yTop - height;
                if (yBottom < 20f) {
                    yBottom = 20f;
                }
                if (yBottom + height > pageHeight - 20f) {
                    yBottom = pageHeight - 20f - height;
                }
                content.addRect(x, yBottom, width, height);
                content.stroke();
                drawn++;
            }
            if (drawn > 0) {
                return;
            }
        }
        if (imageObjectCount <= 0) {
            return;
        }
        int boxes = Math.min(2, Math.max(1, imageObjectCount / 4));
        float boxWidth = 96f;
        float boxHeight = 42f;
        float x = pageWidth - boxWidth - 40f;
        float y = pageHeight - 120f;
        content.setStrokingColor(new Color(245, 247, 250));
        for (int i = 0; i < boxes; i++) {
            float by = y - (i * (boxHeight + 16f));
            if (by < 60f) {
                break;
            }
            content.addRect(x, by, boxWidth, boxHeight);
            content.stroke();
        }
    }

    private static void drawGraphicObjectPlaceholders(PDPageContentStream content,
                                                      float pageWidth,
                                                      float pageHeight,
                                                      int graphicObjectCount,
                                                      List<GraphicOp> graphicOps,
                                                      int minInline,
                                                      int minBaseline,
                                                      float scaleX,
                                                      float scaleY,
                                                      float marginX,
                                                      float marginTop) throws IOException {
        if (graphicOps == null || graphicOps.isEmpty()) {
            return;
        }
        if (graphicOps != null && !graphicOps.isEmpty()) {
            content.setStrokingColor(new Color(214, 222, 230));
            int drawn = 0;
            for (GraphicOp op : graphicOps) {
                if (drawn >= 8) {
                    break;
                }
                float sourceWidth = isQuarterTurn(op.orientation) ? op.height : op.width;
                float sourceHeight = isQuarterTurn(op.orientation) ? op.width : op.height;
                float x = marginX + ((op.x - minInline) * scaleX);
                float yTop = pageHeight - marginTop - ((op.y - minBaseline) * scaleY);
                float width = Math.max(24f, sourceWidth * scaleX);
                float height = Math.max(12f, sourceHeight * scaleY);
                if (x < 20f) {
                    x = 20f;
                }
                if (x + width > pageWidth - 20f) {
                    width = Math.max(12f, pageWidth - x - 20f);
                }
                float yBottom = yTop - height;
                if (yBottom < 20f) {
                    yBottom = 20f;
                }
                if (yBottom + height > pageHeight - 20f) {
                    yBottom = pageHeight - 20f - height;
                }
                content.addRect(x, yBottom, width, height);
                content.stroke();
                content.moveTo(x, yBottom + (height / 2f));
                content.lineTo(x + width, yBottom + (height / 2f));
                content.stroke();
                drawn++;
            }
            if (drawn > 0) {
                return;
            }
        }
        if (graphicObjectCount <= 0) {
            return;
        }
        content.setStrokingColor(new Color(232, 236, 240));
        float x = 42f;
        float y = 56f;
        float width = 120f;
        int lines = Math.min(3, graphicObjectCount);
        for (int i = 0; i < lines; i++) {
            float by = y + (i * 14f);
            content.moveTo(x, by);
            content.lineTo(x + width, by + 6f);
            content.stroke();
        }
    }

    private static List<List<ImageObjectEvidence>> collectPageImageEvidence(List<AfpStructuredField> fields) {
        List<List<ImageObjectEvidence>> byPage = new ArrayList<>();
        List<ImageObjectEvidence> currentPage = null;
        boolean inImage = false;
        ByteArrayOutputStream imageBytes = null;
        for (AfpStructuredField field : fields) {
            String kind = SF_NAMES.getOrDefault(field.sfIdHex(), "UNKNOWN");
            switch (kind) {
                case "BPG" -> {
                    currentPage = new ArrayList<>();
                    byPage.add(currentPage);
                }
                case "EPG" -> currentPage = null;
                case "BIM" -> {
                    if (currentPage == null) {
                        currentPage = new ArrayList<>();
                        byPage.add(currentPage);
                    }
                    inImage = true;
                    imageBytes = new ByteArrayOutputStream();
                }
                case "EIM" -> {
                    if (inImage && currentPage != null) {
                        byte[] payload = imageBytes == null ? new byte[0] : imageBytes.toByteArray();
                        BufferedImage decoded = decodeImageCandidate(payload);
                        List<String> hints = extractUtf16ResourceHints(payload, 12);
                        boolean hasResourceHints = !hints.isEmpty();
                        boolean hasRasterEvidence = decoded != null || hasKnownImageSignature(payload) || isLikelyRasterPayload(payload, hasResourceHints);
                        byte[] rawPayload = hasRasterEvidence ? payload : null;
                        currentPage.add(new ImageObjectEvidence(decoded, rawPayload, hints, hasRasterEvidence));
                    }
                    inImage = false;
                    imageBytes = null;
                }
                default -> {
                    if (inImage && imageBytes != null) {
                        byte[] payload = field.payload();
                        if (payload.length > 0) {
                            imageBytes.write(payload, 0, payload.length);
                        }
                    }
                }
            }
        }
        return byPage;
    }

    private static List<List<BufferedImage>> decodePageImages(List<List<ImageObjectEvidence>> pageEvidence) {
        List<List<BufferedImage>> byPage = new ArrayList<>();
        if (pageEvidence == null) {
            return byPage;
        }
        for (List<ImageObjectEvidence> page : pageEvidence) {
            List<BufferedImage> decoded = new ArrayList<>();
            if (page != null) {
                for (ImageObjectEvidence evidence : page) {
                    decoded.add(evidence == null ? null : evidence.decoded);
                }
            }
            byPage.add(decoded);
        }
        return byPage;
    }

    private static List<List<byte[]>> extractPageImagePayloads(List<List<ImageObjectEvidence>> pageEvidence) {
        List<List<byte[]>> byPage = new ArrayList<>();
        if (pageEvidence == null) {
            return byPage;
        }
        for (List<ImageObjectEvidence> page : pageEvidence) {
            List<byte[]> raws = new ArrayList<>();
            if (page != null) {
                for (ImageObjectEvidence evidence : page) {
                    raws.add(evidence == null ? null : evidence.rawRasterPayload);
                }
            }
            byPage.add(raws);
        }
        return byPage;
    }

    private static List<List<String>> extractPageImageResourceHints(List<List<ImageObjectEvidence>> pageEvidence) {
        List<List<String>> byPage = new ArrayList<>();
        if (pageEvidence == null) {
            return byPage;
        }
        for (List<ImageObjectEvidence> page : pageEvidence) {
            List<String> hints = new ArrayList<>();
            if (page != null) {
                for (ImageObjectEvidence evidence : page) {
                    if (evidence == null || evidence.resourceHints == null) {
                        continue;
                    }
                    hints.addAll(evidence.resourceHints);
                }
            }
            byPage.add(hints);
        }
        return byPage;
    }

    private static List<List<BufferedImage>> resolvePageResourceImages(List<List<String>> pageImageResourceHints,
                                                                       ResourceContext resourceContext) {
        if (pageImageResourceHints == null || pageImageResourceHints.isEmpty()) {
            return List.of();
        }
        List<List<BufferedImage>> resolved = new ArrayList<>(pageImageResourceHints.size());
        for (List<String> hints : pageImageResourceHints) {
            List<BufferedImage> pageResolved = new ArrayList<>(1);
            pageResolved.add(resolveImageByHints(hints, resourceContext).orElse(null));
            resolved.add(pageResolved);
        }
        return resolved;
    }

    private static List<List<BufferedImage>> mergeResolvedResourceImages(List<List<BufferedImage>> primary,
                                                                         List<List<BufferedImage>> fallback) {
        int size = Math.max(primary == null ? 0 : primary.size(), fallback == null ? 0 : fallback.size());
        if (size == 0) {
            return List.of();
        }
        List<List<BufferedImage>> merged = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            List<BufferedImage> first = primary != null && i < primary.size() ? primary.get(i) : List.of();
            List<BufferedImage> second = fallback != null && i < fallback.size() ? fallback.get(i) : List.of();
            int maxInner = Math.max(first == null ? 0 : first.size(), second == null ? 0 : second.size());
            if (maxInner == 0) {
                merged.add(List.of());
                continue;
            }
            List<BufferedImage> pageMerged = new ArrayList<>(maxInner);
            for (int j = 0; j < maxInner; j++) {
                BufferedImage primaryImage = first != null && j < first.size() ? first.get(j) : null;
                BufferedImage fallbackImage = second != null && j < second.size() ? second.get(j) : null;
                pageMerged.add(primaryImage != null ? primaryImage : fallbackImage);
            }
            merged.add(pageMerged);
        }
        return merged;
    }

    private static List<List<BufferedImage>> resolvePageEmbeddedResourceImages(byte[] afpBytes,
                                                                                List<List<ImageObjectEvidence>> pageImageEvidence) {
        List<PageImageBindingState> states = collectPageImageBindingState(afpBytes, pageImageEvidence);
        if (states.isEmpty()) {
            return List.of();
        }
        List<List<BufferedImage>> resolved = new ArrayList<>(states.size());
        for (PageImageBindingState state : states) {
            List<BufferedImage> pageResolved = new ArrayList<>(state.imageReferences.size());
            for (ImageReferenceState imageReference : state.imageReferences) {
                pageResolved.add(matchEmbeddedResourceImage(imageReference, state.candidates, state.imageReferences.size()).orElse(null));
            }
            resolved.add(pageResolved);
        }
        return resolved;
    }

    private static List<PageImageBindingState> collectPageImageBindingState(byte[] afpBytes,
                                                                             List<List<ImageObjectEvidence>> pageImageEvidence) {
        if (pageImageEvidence == null || pageImageEvidence.isEmpty()) {
            return List.of();
        }
        List<PageImageBindingState> pages = initPageImageBindingStates(pageImageEvidence);
        if (afpBytes == null || afpBytes.length == 0) {
            return pages;
        }
        int pageIndex = -1;
        int imageIndex = -1;
        boolean inContainer = false;
        ByteArrayOutputStream containerBytes = null;
        Set<String> containerTokens = new HashSet<>();

        try (AfpInputStream in = new AfpInputStream(new ByteArrayInputStream(afpBytes))) {
            while (true) {
                Object sf = in.readStructuredField();
                if (sf == null) {
                    break;
                }
                String name = sf.getClass().getSimpleName();
                switch (name) {
                    case "BPG" -> {
                        pageIndex++;
                        imageIndex = -1;
                    }
                    case "BIM" -> {
                        imageIndex++;
                        PageImageBindingState state = ensurePageBindingState(pages, pageIndex);
                        ImageReferenceState imageState = ensureImageReferenceState(state, imageIndex);
                        imageState.referenceTokens.addAll(extractStructuredFieldReferenceTokens(sf));
                    }
                    case "BOC" -> {
                        inContainer = true;
                        containerBytes = new ByteArrayOutputStream();
                        containerTokens = new HashSet<>(extractStructuredFieldReferenceTokens(sf));
                    }
                    case "OBD" -> {
                        if (inContainer && containerBytes != null) {
                            byte[] payload = invokeByteArrayGetter(sf, "getOBDData");
                            if (payload == null) {
                                payload = invokeByteArrayGetter(sf, "getOBDDATA");
                            }
                            if (payload != null && payload.length > 0) {
                                containerBytes.write(payload, 0, payload.length);
                            }
                            containerTokens.addAll(extractStructuredFieldReferenceTokens(sf));
                        }
                    }
                    case "EOC" -> {
                        if (inContainer && containerBytes != null) {
                            byte[] payload = containerBytes.toByteArray();
                            BufferedImage decoded = decodeImageCandidate(payload);
                            if (decoded != null) {
                                containerTokens.addAll(extractStructuredFieldReferenceTokens(sf));
                                PageImageBindingState state = ensurePageBindingState(pages, pageIndex);
                                state.candidates.add(new EmbeddedImageResourceCandidate(
                                    decoded,
                                    containerTokens,
                                    state.candidates.size()
                                ));
                            }
                        }
                        inContainer = false;
                        containerBytes = null;
                        containerTokens = new HashSet<>();
                    }
                    default -> {
                    }
                }
            }
        } catch (Exception ignored) {
            return pages;
        }
        return pages;
    }

    private static List<PageImageBindingState> initPageImageBindingStates(List<List<ImageObjectEvidence>> pageImageEvidence) {
        List<PageImageBindingState> pages = new ArrayList<>(pageImageEvidence.size());
        for (List<ImageObjectEvidence> pageEvidence : pageImageEvidence) {
            int count = pageEvidence == null ? 0 : pageEvidence.size();
            PageImageBindingState state = new PageImageBindingState();
            for (int i = 0; i < count; i++) {
                ImageObjectEvidence evidence = pageEvidence == null || i >= pageEvidence.size() ? null : pageEvidence.get(i);
                int expectedWidth = evidence != null && evidence.decoded != null ? evidence.decoded.getWidth() : 0;
                int expectedHeight = evidence != null && evidence.decoded != null ? evidence.decoded.getHeight() : 0;
                state.imageReferences.add(new ImageReferenceState(i, expectedWidth, expectedHeight));
            }
            pages.add(state);
        }
        return pages;
    }

    private static PageImageBindingState ensurePageBindingState(List<PageImageBindingState> pages, int pageIndex) {
        int idx = Math.max(0, pageIndex);
        while (pages.size() <= idx) {
            pages.add(new PageImageBindingState());
        }
        return pages.get(idx);
    }

    private static ImageReferenceState ensureImageReferenceState(PageImageBindingState page, int imageIndex) {
        int idx = Math.max(0, imageIndex);
        while (page.imageReferences.size() <= idx) {
            page.imageReferences.add(new ImageReferenceState(page.imageReferences.size(), 0, 0));
        }
        return page.imageReferences.get(idx);
    }

    private static Optional<BufferedImage> matchEmbeddedResourceImage(ImageReferenceState imageReference,
                                                                      List<EmbeddedImageResourceCandidate> candidates,
                                                                      int pageImageCount) {
        if (imageReference == null || candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        EmbeddedImageResourceCandidate best = null;
        double bestScore = 0.0d;
        Set<String> bimReferenceTokens = imageReference.referenceTokens;
        int tokenCount = bimReferenceTokens == null ? 0 : bimReferenceTokens.size();
        for (EmbeddedImageResourceCandidate candidate : candidates) {
            int overlap = 0;
            for (String token : candidate.referenceTokens) {
                if (bimReferenceTokens != null && bimReferenceTokens.contains(token)) {
                    overlap++;
                }
            }
            double tokenScore = tokenCount <= 0 ? 0.0d : ((double) overlap / (double) tokenCount);
            double sequenceScore = 1.0d / (1.0d + Math.abs(candidate.candidateIndex - imageReference.imageIndex));
            double sizeScore = sizeSimilarityScore(
                imageReference.expectedWidth,
                imageReference.expectedHeight,
                candidate.image.getWidth(),
                candidate.image.getHeight()
            );
            double score = (tokenScore * 0.75d) + (sizeScore * 0.15d) + (sequenceScore * 0.10d);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        boolean hasTokenEvidence = tokenCount > 0;
        if (!hasTokenEvidence) {
            // Without direct token evidence, only accept deterministic single-object page matches.
            if (candidates.size() != 1 || pageImageCount != 1) {
                return Optional.empty();
            }
            return Optional.ofNullable(best.image);
        }
        if (bestScore < 0.25d) {
            return Optional.empty();
        }
        return Optional.ofNullable(best.image);
    }

    private static double sizeSimilarityScore(int expectedWidth, int expectedHeight, int actualWidth, int actualHeight) {
        if (expectedWidth <= 0 || expectedHeight <= 0 || actualWidth <= 0 || actualHeight <= 0) {
            return 0.0d;
        }
        double expectedRatio = (double) expectedWidth / (double) expectedHeight;
        double actualRatio = (double) actualWidth / (double) actualHeight;
        double ratioDelta = Math.abs(expectedRatio - actualRatio);
        double ratioScore = Math.max(0.0d, 1.0d - Math.min(1.0d, ratioDelta));
        double expectedArea = (double) expectedWidth * (double) expectedHeight;
        double actualArea = (double) actualWidth * (double) actualHeight;
        double areaScore = Math.min(expectedArea, actualArea) / Math.max(expectedArea, actualArea);
        return (ratioScore * 0.65d) + (areaScore * 0.35d);
    }

    private static BufferedImage decodeImageCandidate(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return null;
        }
        try {
            BufferedImage direct = ImageIO.read(new ByteArrayInputStream(bytes));
            if (direct != null) {
                return direct;
            }
        } catch (Exception ignored) {
            // Try signature offsets below.
        }
        int[] starts = new int[] {
            indexOf(bytes, new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47}),
            indexOf(bytes, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
            indexOf(bytes, new byte[] {0x47, 0x49, 0x46, 0x38}),
            indexOf(bytes, new byte[] {0x42, 0x4D}),
            indexOf(bytes, new byte[] {0x49, 0x49, 0x2A, 0x00}),
            indexOf(bytes, new byte[] {0x4D, 0x4D, 0x00, 0x2A})
        };
        for (int start : starts) {
            if (start < 0 || start >= bytes.length - 8) {
                continue;
            }
            try {
                BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes, start, bytes.length - start));
                if (decoded != null) {
                    return decoded;
                }
            } catch (Exception ignored) {
                // Try next signature.
            }
        }
        return null;
    }

    private static boolean hasKnownImageSignature(byte[] payload) {
        if (payload == null || payload.length < 4) {
            return false;
        }
        return indexOf(payload, new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47}) >= 0
            || indexOf(payload, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}) >= 0
            || indexOf(payload, new byte[] {0x47, 0x49, 0x46, 0x38}) >= 0
            || indexOf(payload, new byte[] {0x42, 0x4D}) >= 0
            || indexOf(payload, new byte[] {0x49, 0x49, 0x2A, 0x00}) >= 0
            || indexOf(payload, new byte[] {0x4D, 0x4D, 0x00, 0x2A}) >= 0;
    }

    private static boolean isLikelyRasterPayload(byte[] payload, boolean hasResourceHints) {
        if (payload == null || payload.length < 64) {
            return false;
        }
        if (hasResourceHints) {
            return false;
        }
        int zeros = 0;
        int printable = 0;
        for (byte b : payload) {
            int v = b & 0xFF;
            if (v == 0) {
                zeros++;
            }
            if (v >= 0x20 && v <= 0x7E) {
                printable++;
            }
        }
        // Reject likely UTF-16/structured text payloads masquerading as image bytes.
        if (zeros >= (payload.length / 5) && printable >= (payload.length / 6)) {
            return false;
        }
        // Reject high-printable payloads that look like structured control streams.
        if (printable >= (payload.length / 3)) {
            return false;
        }
        return true;
    }

    private static List<String> extractUtf16ResourceHints(byte[] payload, int limit) {
        if (payload == null || payload.length < 8 || limit <= 0) {
            return List.of();
        }
        Set<String> hints = new HashSet<>();
        StringBuilder sb = new StringBuilder();
        int max = Math.max(1, limit);
        for (int i = 0; i + 1 < payload.length; i += 2) {
            int hi = payload[i] & 0xFF;
            int lo = payload[i + 1] & 0xFF;
            if (hi == 0 && lo >= 0x20 && lo <= 0x7E) {
                sb.append((char) lo);
                continue;
            }
            if (sb.length() >= 4) {
                String hint = sb.toString().trim().replaceAll("\\s+", " ");
                if (!hint.isEmpty()) {
                    hints.add(hint);
                    if (hints.size() >= max) {
                        break;
                    }
                }
            }
            sb.setLength(0);
        }
        if (hints.size() < max && sb.length() >= 4) {
            String hint = sb.toString().trim().replaceAll("\\s+", " ");
            if (!hint.isEmpty()) {
                hints.add(hint);
            }
        }
        return new ArrayList<>(hints);
    }

    private static Optional<BufferedImage> resolveImageByHints(List<String> hints, ResourceContext resourceContext) {
        if (resourceContext == null || hints == null || hints.isEmpty()) {
            return Optional.empty();
        }
        if (containsLikelyFontHints(hints)) {
            return Optional.empty();
        }
        List<Path> roots = new ArrayList<>();
        resourceContext.jobResourceRoot().ifPresent(roots::add);
        roots.addAll(resourceContext.searchPaths());
        if (roots.isEmpty()) {
            return Optional.empty();
        }
        List<String> tokens = new ArrayList<>();
        for (String hint : hints) {
            String normalized = normalizeHintToken(hint);
            if (!normalized.isBlank()) {
                tokens.add(normalized);
            }
        }
        if (tokens.isEmpty()) {
            return Optional.empty();
        }
        for (Path root : roots) {
            if (root == null || !Files.exists(root)) {
                continue;
            }
            try {
                if (Files.isRegularFile(root) && isImageFile(root) && matchesHints(root, tokens)) {
                    BufferedImage decoded = ImageIO.read(root.toFile());
                    if (decoded != null) {
                        return Optional.of(decoded);
                    }
                }
                if (!Files.isDirectory(root)) {
                    continue;
                }
                int scanned = 0;
                try (var stream = Files.walk(root, 4)) {
                    for (Path candidate : (Iterable<Path>) stream::iterator) {
                        if (scanned >= 2000) {
                            break;
                        }
                        if (!Files.isRegularFile(candidate) || !isImageFile(candidate)) {
                            continue;
                        }
                        scanned++;
                        if (!matchesHints(candidate, tokens)) {
                            continue;
                        }
                        BufferedImage decoded = ImageIO.read(candidate.toFile());
                        if (decoded != null) {
                            return Optional.of(decoded);
                        }
                    }
                }
            } catch (Exception ignored) {
                // best-effort lookup only
            }
        }
        return Optional.empty();
    }

    private static boolean isImageFile(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".png")
            || name.endsWith(".jpg")
            || name.endsWith(".jpeg")
            || name.endsWith(".gif")
            || name.endsWith(".bmp")
            || name.endsWith(".tif")
            || name.endsWith(".tiff");
    }

    private static boolean matchesHints(Path candidate, List<String> tokens) {
        String fileName = candidate.getFileName() == null ? "" : candidate.getFileName().toString();
        String normalized = normalizeHintToken(fileName);
        if (normalized.isBlank()) {
            return false;
        }
        for (String token : tokens) {
            if (!token.isBlank() && normalized.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeHintToken(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private static boolean containsLikelyFontHints(List<String> hints) {
        if (hints == null || hints.isEmpty()) {
            return false;
        }
        for (String hint : hints) {
            String token = normalizeHintToken(hint);
            if (token.isEmpty()) {
                continue;
            }
            if (token.contains("arial")
                || token.contains("segoe")
                || token.contains("timesnewroman")
                || token.contains("courier")
                || token.contains("helvetica")
                || token.contains("bold")
                || token.contains("italic")
                || token.contains("regular")
                || token.contains("font")) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> extractStructuredFieldReferenceTokens(Object sf) {
        if (sf == null) {
            return Set.of();
        }
        Set<String> tokens = new HashSet<>();
        for (Method method : sf.getClass().getMethods()) {
            if (method.getParameterCount() != 0) {
                continue;
            }
            String name = method.getName();
            if (!name.startsWith("get") || "getClass".equals(name)) {
                continue;
            }
            String suffix = name.substring(3);
            if (!isLikelyReferenceGetter(suffix)) {
                continue;
            }
            try {
                Object value = method.invoke(sf);
                addReferenceTokens(tokens, value);
            } catch (Exception ignored) {
                // best-effort getter probing only
            }
        }
        return tokens;
    }

    private static boolean isLikelyReferenceGetter(String suffix) {
        if (suffix == null || suffix.isBlank()) {
            return false;
        }
        return suffix.contains("Name")
            || suffix.endsWith("ID")
            || suffix.endsWith("Id")
            || suffix.contains("RID")
            || suffix.contains("Ref")
            || suffix.contains("Resource")
            || suffix.contains("Obj")
            || suffix.contains("OID");
    }

    private static void addReferenceTokens(Set<String> tokens, Object value) {
        if (tokens == null || value == null) {
            return;
        }
        if (value instanceof String s) {
            for (String part : s.split("[\\s,;:/\\\\|]+")) {
                String token = normalizeHintToken(part);
                if (isReferenceToken(token)) {
                    tokens.add(token);
                }
            }
            return;
        }
        if (value instanceof Number n) {
            String token = normalizeHintToken(String.valueOf(n.longValue()));
            if (isReferenceToken(token)) {
                tokens.add(token);
            }
            return;
        }
        if (value instanceof byte[] bytes) {
            String ascii = new String(bytes, StandardCharsets.ISO_8859_1);
            addReferenceTokens(tokens, ascii);
            for (String hint : extractUtf16ResourceHints(bytes, 12)) {
                addReferenceTokens(tokens, hint);
            }
            return;
        }
        if (value instanceof Enum<?> e) {
            String token = normalizeHintToken(e.name());
            if (isReferenceToken(token)) {
                tokens.add(token);
            }
        }
    }

    private static boolean isReferenceToken(String token) {
        if (token == null || token.length() < 3) {
            return false;
        }
        if (containsLikelyFontHints(List.of(token))) {
            return false;
        }
        return true;
    }

    private static final class EmbeddedImageResourceCandidate {
        private final BufferedImage image;
        private final Set<String> referenceTokens;
        private final int candidateIndex;

        private EmbeddedImageResourceCandidate(BufferedImage image, Set<String> referenceTokens, int candidateIndex) {
            this.image = image;
            this.referenceTokens = referenceTokens == null ? Set.of() : Set.copyOf(referenceTokens);
            this.candidateIndex = Math.max(0, candidateIndex);
        }
    }

    private static final class PageImageBindingState {
        private final List<ImageReferenceState> imageReferences = new ArrayList<>();
        private final List<EmbeddedImageResourceCandidate> candidates = new ArrayList<>();
    }

    private static final class ImageReferenceState {
        private final int imageIndex;
        private final int expectedWidth;
        private final int expectedHeight;
        private final Set<String> referenceTokens = new HashSet<>();

        private ImageReferenceState(int imageIndex, int expectedWidth, int expectedHeight) {
            this.imageIndex = Math.max(0, imageIndex);
            this.expectedWidth = Math.max(0, expectedWidth);
            this.expectedHeight = Math.max(0, expectedHeight);
        }
    }

    private static final class ImageObjectEvidence {
        private final BufferedImage decoded;
        private final byte[] rawRasterPayload;
        private final List<String> resourceHints;
        private final boolean hasRasterEvidence;

        private ImageObjectEvidence(BufferedImage decoded,
                                    byte[] rawRasterPayload,
                                    List<String> resourceHints,
                                    boolean hasRasterEvidence) {
            this.decoded = decoded;
            this.rawRasterPayload = rawRasterPayload == null ? null : rawRasterPayload.clone();
            this.resourceHints = resourceHints == null ? List.of() : List.copyOf(resourceHints);
            this.hasRasterEvidence = hasRasterEvidence;
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        if (haystack == null || needle == null || needle.length == 0 || haystack.length < needle.length) {
            return -1;
        }
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static void applyObjectPlacement(ImageState state, Integer x, Integer y, Integer xOrientation, Integer yOrientation) {
        if (x != null && x >= 0) {
            state.x = x;
        }
        if (y != null && y >= 0) {
            state.y = y;
        }
        if (xOrientation != null) {
            state.orientation = normalizeOrientation(xOrientation);
        } else if (yOrientation != null) {
            state.orientation = normalizeOrientation(yOrientation);
        }
    }

    private static void applyObjectSize(ImageState state, Integer width, Integer height) {
        if (width != null && width > 0) {
            state.width = width;
        }
        if (height != null && height > 0) {
            state.height = height;
        }
    }

    private static void applyGraphicPlacement(GraphicState state, Integer x, Integer y, Integer xOrientation, Integer yOrientation) {
        if (x != null && x >= 0) {
            state.x = x;
        }
        if (y != null && y >= 0) {
            state.y = y;
        }
        if (xOrientation != null) {
            state.orientation = normalizeOrientation(xOrientation);
        } else if (yOrientation != null) {
            state.orientation = normalizeOrientation(yOrientation);
        }
    }

    private static void applyGraphicSize(GraphicState state, Integer width, Integer height) {
        if (width != null && width > 0) {
            state.width = width;
        }
        if (height != null && height > 0) {
            state.height = height;
        }
    }

    private static int normalizeOrientation(int raw) {
        if (raw == 0 || raw == 90 || raw == 180 || raw == 270) {
            return raw;
        }
        if (raw == 0x2D00) {
            return 90;
        }
        if (raw == 0x5A00) {
            return 180;
        }
        if (raw == 0x8700) {
            return 270;
        }
        if (Math.abs(raw) <= 3600 && raw % 10 == 0) {
            int v = Math.floorMod(raw / 10, 360);
            if (v == 0 || v == 90 || v == 180 || v == 270) {
                return v;
            }
        }
        return 0;
    }

    private static boolean isQuarterTurn(int orientation) {
        int o = Math.floorMod(orientation, 360);
        return o == 90 || o == 270;
    }

    private static SemanticFallbackState initSemanticFallback(AfpInterpretation interpretation, AfpCodePageProfile profile) {
        int fallbackRunBudget = 0;
        for (AfpStructuredField field : interpretation.fields()) {
            String kind = SF_NAMES.getOrDefault(field.sfIdHex(), "UNKNOWN");
            if (!"PTX".equals(kind) && !"TRN".equals(kind)) {
                continue;
            }
            List<String> decoded = AfpTextDecoders.decodeTextFragments(field, profile).fragments();
            if (isFallbackCandidateText(decoded)) {
                int runEstimate = 1;
                if ("PTX".equals(kind)) {
                    runEstimate = Math.max(1, AfpTextDecoders.countPotentialTextRunsInPtxPayload(field.payload()));
                }
                fallbackRunBudget += runEstimate;
            }
        }
        return new SemanticFallbackState(interpretation.semantics().textFragments(), fallbackRunBudget);
    }

    private static float clamp(float value, float min, float max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static int percentile(List<Integer> values, double q) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(Integer::compareTo);
        double clampedQ = Math.max(0d, Math.min(1d, q));
        int idx = (int) Math.round((sorted.size() - 1) * clampedQ);
        idx = Math.max(0, Math.min(sorted.size() - 1, idx));
        return sorted.get(idx);
    }

    private static float estimateFontSize(PtocaState state) {
        if (state.explicitFontSize != null && state.explicitFontSize > 0f) {
            return clamp(state.explicitFontSize, 6f, 18f);
        }
        int base = Math.max(8, Math.max(Math.abs(state.inlineIncrement), Math.abs(state.baselineIncrement)));
        return clamp(base / 2.4f, 7f, 14f);
    }

    private static PDType1Font fontForLocalId(int localFontId, Map<Integer, PDType1Font> localFontMap) {
        PDType1Font mapped = localFontMap.get(localFontId);
        if (mapped != null) {
            return mapped;
        }
        int bucket = Math.floorMod(localFontId, 4);
        return switch (bucket) {
            case 1 -> PDType1Font.TIMES_ROMAN;
            case 2 -> PDType1Font.COURIER;
            case 3 -> PDType1Font.HELVETICA_BOLD;
            default -> PDType1Font.HELVETICA;
        };
    }

    private static void collectCodedFonts(Object sf,
                                          Map<Integer, PDType1Font> localFontMap,
                                          Map<Integer, Float> localFontSizeMap,
                                          ScopedFontResolver scopedFontResolver,
                                          int pageIndex,
                                          int resourceDepth) {
        if (!(sf instanceof CFI cfi)) {
            return;
        }
        int idx = 0;
        for (CFIRG rg : cfi.getFixedLengthRG()) {
            Integer localId = rg.getSection() == null ? idx : rg.getSection();
            PDType1Font mappedFont = mapCodedFont(rg.getFCSName(), rg.getCPName());
            localFontMap.put(localId, mappedFont);
            Float mappedSize = null;
            if (rg.getSVSize() != null && rg.getSVSize() > 0) {
                // AFPLib SVSize is typically encoded in 1/20th point units.
                mappedSize = rg.getSVSize() / 20f;
                localFontSizeMap.put(localId, mappedSize);
            }
            scopedFontResolver.addDefinition(localId, mappedFont, mappedSize, pageIndex, resourceDepth);
            idx++;
        }
    }

    private static PDType1Font mapCodedFont(String fcsName, String cpName) {
        String combined = ((fcsName == null ? "" : fcsName) + " " + (cpName == null ? "" : cpName)).toLowerCase();
        boolean bold = combined.contains("bold") || combined.contains("demi");
        boolean italic = combined.contains("italic") || combined.contains("oblique");
        if (combined.contains("cour") || combined.contains("lettergothic") || combined.contains("prestige")) {
            if (italic && bold) {
                return PDType1Font.COURIER_BOLD_OBLIQUE;
            }
            if (italic) {
                return PDType1Font.COURIER_OBLIQUE;
            }
            if (bold) {
                return PDType1Font.COURIER_BOLD;
            }
            return PDType1Font.COURIER;
        }
        if (combined.contains("times") || combined.contains("roman") || combined.contains("serif")) {
            if (italic && bold) {
                return PDType1Font.TIMES_BOLD_ITALIC;
            }
            if (italic) {
                return PDType1Font.TIMES_ITALIC;
            }
            if (bold) {
                return PDType1Font.TIMES_BOLD;
            }
            return PDType1Font.TIMES_ROMAN;
        }
        if (combined.contains("gothic") || combined.contains("swiss") || combined.contains("helv")) {
            if (italic && bold) {
                return PDType1Font.HELVETICA_BOLD_OBLIQUE;
            }
            if (italic) {
                return PDType1Font.HELVETICA_OBLIQUE;
            }
            if (bold) {
                return PDType1Font.HELVETICA_BOLD;
            }
            return PDType1Font.HELVETICA;
        }
        if (italic && bold) {
            return PDType1Font.HELVETICA_BOLD_OBLIQUE;
        }
        if (italic) {
            return PDType1Font.HELVETICA_OBLIQUE;
        }
        if (bold) {
            return PDType1Font.HELVETICA_BOLD;
        }
        return PDType1Font.HELVETICA;
    }

    private static boolean isLowSignalText(List<String> fragments) {
        if (fragments == null || fragments.isEmpty()) {
            return true;
        }
        String joined = String.join(" ", fragments);
        int letters = 0;
        int digits = 0;
        int symbols = 0;
        for (int i = 0; i < joined.length(); i++) {
            char c = joined.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
            } else if (Character.isDigit(c)) {
                digits++;
            } else if (!Character.isWhitespace(c)) {
                symbols++;
            }
        }
        int signal = letters + digits;
        return signal < 2 || symbols > signal;
    }

    private static boolean isFallbackCandidateText(List<String> fragments) {
        if (isLowSignalText(fragments)) {
            return true;
        }
        String joined = String.join(" ", fragments).trim();
        if (joined.isEmpty()) {
            return true;
        }
        int nonWhitespace = 0;
        int whitespace = 0;
        int control = 0;
        int nonAscii = 0;
        int maxRun = 1;
        int run = 1;
        char prev = 0;
        for (int i = 0; i < joined.length(); i++) {
            char c = joined.charAt(i);
            if (Character.isWhitespace(c)) {
                whitespace++;
                continue;
            }
            nonWhitespace++;
            if (Character.isISOControl(c)) {
                control++;
            }
            if (c > 0x7E) {
                nonAscii++;
            }
            if (c == prev) {
                run++;
                maxRun = Math.max(maxRun, run);
            } else {
                run = 1;
                prev = c;
            }
        }
        boolean denseSingleToken = joined.length() >= 24 && whitespace == 0;
        boolean repetitive = maxRun >= 6;
        boolean controlHeavy = control > 0 && control * 8 >= Math.max(1, nonWhitespace);
        boolean nonAsciiHeavy = nonAscii > 0 && nonAscii * 3 >= Math.max(1, nonWhitespace);
        boolean implausiblyShort = fragments.size() <= 1 && nonWhitespace <= 2;
        return denseSingleToken || repetitive || controlHeavy || nonAsciiHeavy || implausiblyShort;
    }

    private static Charset resolveCharset(String name, Charset fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        try {
            return Charset.forName(name);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String sanitizeForFont(PDType1Font font, String text) {
        String safe = text == null ? "" : text;
        StringBuilder sb = new StringBuilder(safe.length());
        for (int i = 0; i < safe.length(); i++) {
            String ch = String.valueOf(safe.charAt(i));
            try {
                font.encode(ch);
                sb.append(ch);
            } catch (Exception ignored) {
                sb.append('?');
            }
        }
        return sb.toString();
    }

    private static PDType1Font chooseBestEncodableFont(PDType1Font preferred, String text) {
        if (text == null || text.isEmpty()) {
            return preferred;
        }
        List<PDType1Font> candidates = List.of(
            preferred,
            PDType1Font.HELVETICA,
            PDType1Font.TIMES_ROMAN,
            PDType1Font.COURIER
        );
        PDType1Font best = preferred;
        int bestMissing = Integer.MAX_VALUE;
        for (PDType1Font candidate : candidates) {
            int missing = missingGlyphCount(candidate, text);
            if (missing < bestMissing) {
                bestMissing = missing;
                best = candidate;
            }
            if (missing == 0) {
                break;
            }
        }
        return best;
    }

    private static int missingGlyphCount(PDType1Font font, String text) {
        int missing = 0;
        for (int i = 0; i < text.length(); i++) {
            try {
                font.encode(String.valueOf(text.charAt(i)));
            } catch (Exception ignored) {
                missing++;
            }
        }
        return missing;
    }

    private static final class PtocaState {
        private int inline;
        private int baseline;
        private int inlineIncrement;
        private int baselineIncrement;
        private int localFontId;
        private PDType1Font resolvedFont;
        private Float explicitFontSize;
        private boolean overlay;
        private int resourceDepth;
        private int pageIndex;
        private int sequence;

        private PtocaState() {
            this.inline = 0;
            this.baseline = 0;
            this.inlineIncrement = 24;
            this.baselineIncrement = 30;
            this.localFontId = 0;
            this.resolvedFont = null;
            this.explicitFontSize = null;
            this.overlay = false;
            this.resourceDepth = 0;
            this.pageIndex = 0;
            this.sequence = 0;
        }
    }

    private static final class TextOp {
        private final int inline;
        private final int baseline;
        private final String text;
        private final int localFontId;
        private final PDType1Font resolvedFont;
        private final float fontSize;
        private final boolean overlay;
        private final int resourceDepth;
        private final int sequence;

        private TextOp(int inline, int baseline, String text, int localFontId, PDType1Font resolvedFont, float fontSize, boolean overlay, int resourceDepth, int sequence) {
            this.inline = inline;
            this.baseline = baseline;
            this.text = text;
            this.localFontId = localFontId;
            this.resolvedFont = resolvedFont;
            this.fontSize = fontSize;
            this.overlay = overlay;
            this.resourceDepth = Math.max(0, resourceDepth);
            this.sequence = Math.max(0, sequence);
        }
    }

    private static final class ImageState {
        private int x;
        private int y;
        private int width;
        private int height;
        private int orientation;
        private boolean overlay;
        private int resourceDepth;
        private int sequence;
        private int imageIndex;

        private ImageState() {
            this.x = 0;
            this.y = 0;
            this.width = 1200;
            this.height = 700;
            this.orientation = 0;
            this.overlay = false;
            this.resourceDepth = 0;
            this.sequence = 0;
            this.imageIndex = 0;
        }

        private ImageOp toImageOp() {
            return new ImageOp(x, y, Math.max(1, width), Math.max(1, height), orientation, overlay, resourceDepth, sequence, imageIndex);
        }
    }

    private static final class GraphicState {
        private int x;
        private int y;
        private int width;
        private int height;
        private int orientation;
        private boolean overlay;
        private int resourceDepth;
        private int sequence;

        private GraphicState() {
            this.x = 0;
            this.y = 0;
            this.width = 1200;
            this.height = 120;
            this.orientation = 0;
            this.overlay = false;
            this.resourceDepth = 0;
            this.sequence = 0;
        }

        private GraphicOp toGraphicOp() {
            return new GraphicOp(x, y, Math.max(1, width), Math.max(1, height), orientation, overlay, resourceDepth, sequence);
        }
    }

    private static final class ImageOp {
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final int orientation;
        private final boolean overlay;
        private final int resourceDepth;
        private final int sequence;
        private final int imageIndex;

        private ImageOp(int x, int y, int width, int height, int orientation, boolean overlay, int resourceDepth, int sequence, int imageIndex) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.orientation = orientation;
            this.overlay = overlay;
            this.resourceDepth = Math.max(0, resourceDepth);
            this.sequence = Math.max(0, sequence);
            this.imageIndex = Math.max(0, imageIndex);
        }
    }

    private static final class GraphicOp {
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final int orientation;
        private final boolean overlay;
        private final int resourceDepth;
        private final int sequence;

        private GraphicOp(int x, int y, int width, int height, int orientation, boolean overlay, int resourceDepth, int sequence) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.orientation = orientation;
            this.overlay = overlay;
            this.resourceDepth = Math.max(0, resourceDepth);
            this.sequence = Math.max(0, sequence);
        }
    }

    private static final class ImagePlacement {
        private final float x;
        private final float yBottom;
        private final float width;
        private final float height;

        private ImagePlacement(float x, float yBottom, float width, float height) {
            this.x = x;
            this.yBottom = yBottom;
            this.width = width;
            this.height = height;
        }
    }

    private enum PaintKind {
        IMAGE,
        GRAPHIC,
        TEXT
    }

    private static final class PagePaintOp {
        private final PaintKind kind;
        private final TextOp textOp;
        private final ImageOp imageOp;
        private final GraphicOp graphicOp;
        private final boolean overlay;
        private final int resourceDepth;
        private final int sequence;
        private final int kindOrder;

        private PagePaintOp(PaintKind kind,
                            TextOp textOp,
                            ImageOp imageOp,
                            GraphicOp graphicOp,
                            boolean overlay,
                            int resourceDepth,
                            int sequence,
                            int kindOrder) {
            this.kind = kind;
            this.textOp = textOp;
            this.imageOp = imageOp;
            this.graphicOp = graphicOp;
            this.overlay = overlay;
            this.resourceDepth = resourceDepth;
            this.sequence = sequence;
            this.kindOrder = kindOrder;
        }

        private static PagePaintOp ofText(TextOp op) {
            return new PagePaintOp(PaintKind.TEXT, op, null, null, op.overlay, op.resourceDepth, op.sequence, 2);
        }

        private static PagePaintOp ofImage(ImageOp op) {
            return new PagePaintOp(PaintKind.IMAGE, null, op, null, op.overlay, op.resourceDepth, op.sequence, 0);
        }

        private static PagePaintOp ofGraphic(GraphicOp op) {
            return new PagePaintOp(PaintKind.GRAPHIC, null, null, op, op.overlay, op.resourceDepth, op.sequence, 1);
        }
    }

    private static final class RunStyle {
        private final PDType1Font font;
        private final float fontSize;
        private final Color color;
        private final float characterSpacing;

        private RunStyle(PDType1Font font, float fontSize, Color color, float characterSpacing) {
            this.font = font;
            this.fontSize = fontSize;
            this.color = color;
            this.characterSpacing = characterSpacing;
        }
    }

    private static final class SemanticFallbackState {
        private final List<String> semantic;
        private int cursor;
        private int remainingLowSignalFields;

        private SemanticFallbackState(List<String> semantic, int remainingLowSignalFields) {
            this.semantic = semantic == null ? List.of() : semantic;
            this.cursor = 0;
            this.remainingLowSignalFields = Math.max(0, remainingLowSignalFields);
        }

        private List<String> takeNext() {
            int remainingSemantic = Math.max(0, semantic.size() - cursor);
            if (remainingSemantic <= 0) {
                remainingLowSignalFields = Math.max(0, remainingLowSignalFields - 1);
                return List.of();
            }
            int divisor = Math.max(1, remainingLowSignalFields);
            int take = remainingLowSignalFields <= 1
                ? remainingSemantic
                : Math.max(1, (int) Math.ceil((double) remainingSemantic / divisor));
            int end = Math.min(semantic.size(), cursor + take);
            List<String> out = List.copyOf(semantic.subList(cursor, end));
            cursor = end;
            remainingLowSignalFields = Math.max(0, remainingLowSignalFields - 1);
            return out;
        }
    }

    private static final class ScopedFontResolver {
        private final List<ScopedFontDefinition> definitions;
        private final Map<Integer, PDType1Font> globalFontMap;
        private final Map<Integer, Float> globalFontSizeMap;
        private int definitionOrder;

        private ScopedFontResolver(Map<Integer, PDType1Font> globalFontMap, Map<Integer, Float> globalFontSizeMap) {
            this.definitions = new ArrayList<>();
            this.globalFontMap = globalFontMap;
            this.globalFontSizeMap = globalFontSizeMap;
            this.definitionOrder = 0;
        }

        private void addDefinition(int localFontId, PDType1Font font, Float size, int pageIndex, int resourceDepth) {
            definitions.add(new ScopedFontDefinition(
                localFontId,
                font,
                size,
                Math.max(0, pageIndex),
                Math.max(0, resourceDepth),
                definitionOrder++
            ));
        }

        private ScopedFontResolution resolve(int localFontId, int pageIndex, int resourceDepth) {
            ScopedFontDefinition best = null;
            for (ScopedFontDefinition candidate : definitions) {
                if (candidate.localFontId != localFontId) {
                    continue;
                }
                if (candidate.resourceDepth > resourceDepth) {
                    continue;
                }
                if (candidate.pageIndex > 0 && pageIndex > 0 && candidate.pageIndex != pageIndex) {
                    continue;
                }
                if (best == null) {
                    best = candidate;
                    continue;
                }
                if (candidate.resourceDepth > best.resourceDepth) {
                    best = candidate;
                    continue;
                }
                if (candidate.resourceDepth == best.resourceDepth && candidate.definitionOrder > best.definitionOrder) {
                    best = candidate;
                }
            }
            if (best != null) {
                return new ScopedFontResolution(best.font, best.size);
            }
            return new ScopedFontResolution(globalFontMap.get(localFontId), globalFontSizeMap.get(localFontId));
        }
    }

    private static final class ScopedFontDefinition {
        private final int localFontId;
        private final PDType1Font font;
        private final Float size;
        private final int pageIndex;
        private final int resourceDepth;
        private final int definitionOrder;

        private ScopedFontDefinition(int localFontId,
                                     PDType1Font font,
                                     Float size,
                                     int pageIndex,
                                     int resourceDepth,
                                     int definitionOrder) {
            this.localFontId = localFontId;
            this.font = font;
            this.size = size;
            this.pageIndex = pageIndex;
            this.resourceDepth = resourceDepth;
            this.definitionOrder = definitionOrder;
        }
    }

    private static final class ScopedFontResolution {
        private final PDType1Font font;
        private final Float fontSize;

        private ScopedFontResolution(PDType1Font font, Float fontSize) {
            this.font = font;
            this.fontSize = fontSize;
        }
    }

    private static final class PtxControlSequence {
        private final int length;
        private final int functionType;
        private final int dataStart;
        private final int dataLength;

        private PtxControlSequence(int length, int functionType, int dataStart, int dataLength) {
            this.length = length;
            this.functionType = functionType;
            this.dataStart = dataStart;
            this.dataLength = dataLength;
        }
    }

    private static final class PageGeometryState {
        private final int xSize;
        private final int ySize;
        private final int xUnits;
        private final int yUnits;

        private PageGeometryState(int xSize, int ySize, int xUnits, int yUnits) {
            this.xSize = xSize;
            this.ySize = ySize;
            this.xUnits = xUnits;
            this.yUnits = yUnits;
        }

        private static PageGeometryState from(PGD pgd) {
            if (pgd == null) {
                return null;
            }
            int xSize = pgd.getXpgSize() == null ? -1 : pgd.getXpgSize();
            int ySize = pgd.getYpgSize() == null ? -1 : pgd.getYpgSize();
            int xUnits = pgd.getXpgUnits() == null ? -1 : pgd.getXpgUnits();
            int yUnits = pgd.getYpgUnits() == null ? -1 : pgd.getYpgUnits();
            if (xSize <= 0 || ySize <= 0) {
                return null;
            }
            return new PageGeometryState(xSize, ySize, xUnits, yUnits);
        }

        private boolean valid() {
            return xSize > 0 && ySize > 0;
        }
    }

    private static List<byte[]> collectFieldPayloads(List<AfpStructuredField> fields, byte[] rawAfpBytes, String sfIdHex) {
        if (fields == null || fields.isEmpty() || sfIdHex == null || sfIdHex.isBlank()) {
            return List.of();
        }
        List<byte[]> payloads = new ArrayList<>();
        for (AfpStructuredField field : fields) {
            if (!sfIdHex.equals(field.sfIdHex()) || field.payloadLength() <= 0) {
                continue;
            }
            byte[] payload = null;
            int payloadLength = Math.max(0, field.payloadLength());
            if (rawAfpBytes != null && rawAfpBytes.length > 0) {
                long payloadStart = field.offset() + 9L;
                if (payloadStart >= 0 && payloadStart < rawAfpBytes.length) {
                    int available = (int) Math.min(Integer.MAX_VALUE, rawAfpBytes.length - payloadStart);
                    int length = Math.min(available, payloadLength);
                    if (length > 0) {
                        payload = new byte[length];
                        System.arraycopy(rawAfpBytes, (int) payloadStart, payload, 0, length);
                    }
                }
            }
            if (payload == null || payload.length == 0) {
                byte[] fieldPayload = field.payload();
                int length = Math.min(fieldPayload.length, payloadLength);
                if (length > 0) {
                    payload = new byte[length];
                    System.arraycopy(fieldPayload, 0, payload, 0, length);
                }
            }
            if (payload != null && payload.length > 0) {
                payloads.add(payload);
            }
        }
        return List.copyOf(payloads);
    }

    private record PtocaRenderResult(boolean rendered,
                                     int renderedTextOps,
                                     int renderedPages,
                                     int imageObjectCount,
                                     int distinctLocalFonts) {
        private static PtocaRenderResult empty() {
            return new PtocaRenderResult(false, 0, 0, 0, 0);
        }
    }
}
