package com.upland.connect.afp.engine;

import java.awt.image.BufferedImage;
import java.util.List;

final class ImageResolutionService {

    SelectedImage selectForImageOp(List<BufferedImage> decodedImages,
                                   List<BufferedImage> resolvedResourceImages,
                                   List<byte[]> rawImagePayloads,
                                   int idx,
                                   int hintedWidth,
                                   int hintedHeight) {
        BufferedImage decoded = imageAt(decodedImages, idx);
        if (decoded != null) {
            return SelectedImage.decoded(decoded);
        }
        BufferedImage resolved = imageAtOrAny(resolvedResourceImages, idx);
        if (resolved != null) {
            return SelectedImage.resource(resolved);
        }
        byte[] raw = payloadAt(rawImagePayloads, idx);
        if (raw != null) {
            BufferedImage rawDecoded = decodeRawImageCandidate(raw, hintedWidth, hintedHeight);
            if (rawDecoded != null) {
                return SelectedImage.raw(rawDecoded);
            }
        }
        return SelectedImage.unresolved();
    }

    SelectedImage selectForFallbackTile(List<BufferedImage> decodedImages,
                                        List<BufferedImage> resolvedResourceImages,
                                        List<byte[]> rawImagePayloads,
                                        int idx) {
        return selectForImageOp(decodedImages, resolvedResourceImages, rawImagePayloads, idx, 120, 64);
    }

    boolean hasAnyDecodedImages(List<BufferedImage> decodedImages) {
        if (decodedImages == null || decodedImages.isEmpty()) {
            return false;
        }
        for (BufferedImage image : decodedImages) {
            if (image != null) {
                return true;
            }
        }
        return false;
    }

    boolean hasAnyRawPayloads(List<byte[]> rawImagePayloads) {
        if (rawImagePayloads == null || rawImagePayloads.isEmpty()) {
            return false;
        }
        for (byte[] payload : rawImagePayloads) {
            if (payload != null && payload.length > 0) {
                return true;
            }
        }
        return false;
    }

    boolean hasAnyResolvedImages(List<BufferedImage> resolvedResourceImages) {
        if (resolvedResourceImages == null || resolvedResourceImages.isEmpty()) {
            return false;
        }
        for (BufferedImage image : resolvedResourceImages) {
            if (image != null) {
                return true;
            }
        }
        return false;
    }

    boolean hasEvidenceAt(List<BufferedImage> decodedImages,
                          List<BufferedImage> resolvedResourceImages,
                          List<byte[]> rawImagePayloads,
                          int idx) {
        return imageAt(decodedImages, idx) != null
            || imageAtOrAny(resolvedResourceImages, idx) != null
            || payloadAt(rawImagePayloads, idx) != null;
    }

    private static BufferedImage imageAt(List<BufferedImage> images, int idx) {
        if (images == null || idx < 0 || idx >= images.size()) {
            return null;
        }
        return images.get(idx);
    }

    private static BufferedImage imageAtOrAny(List<BufferedImage> images, int idx) {
        BufferedImage byIndex = imageAt(images, idx);
        if (byIndex != null) {
            return byIndex;
        }
        if (images == null) {
            return null;
        }
        for (BufferedImage image : images) {
            if (image != null) {
                return image;
            }
        }
        return null;
    }

    private static byte[] payloadAt(List<byte[]> payloads, int idx) {
        if (payloads == null || idx < 0 || idx >= payloads.size()) {
            return null;
        }
        byte[] payload = payloads.get(idx);
        if (payload == null || payload.length == 0) {
            return null;
        }
        return payload;
    }

    private static BufferedImage decodeRawImageCandidate(byte[] bytes, int hintedWidth, int hintedHeight) {
        if (bytes == null || bytes.length == 0 || hintedWidth <= 0 || hintedHeight <= 0) {
            return null;
        }
        int rowPacked = ((hintedWidth + 7) / 8) * hintedHeight;
        if (bytes.length >= rowPacked && rowPacked > 0) {
            BufferedImage biLevel = new BufferedImage(hintedWidth, hintedHeight, BufferedImage.TYPE_BYTE_GRAY);
            int idx = 0;
            for (int y = 0; y < hintedHeight; y++) {
                for (int x = 0; x < hintedWidth; x++) {
                    int bitPos = 7 - (x % 8);
                    int b = bytes[idx + (x / 8)] & 0xFF;
                    int on = (b >> bitPos) & 0x01;
                    int gray = on == 0 ? 255 : 0;
                    biLevel.getRaster().setSample(x, y, 0, gray);
                }
                idx += ((hintedWidth + 7) / 8);
                if (idx >= bytes.length) {
                    break;
                }
            }
            return biLevel;
        }
        int grayBytes = hintedWidth * hintedHeight;
        if (bytes.length >= grayBytes && grayBytes > 0) {
            BufferedImage gray = new BufferedImage(hintedWidth, hintedHeight, BufferedImage.TYPE_BYTE_GRAY);
            int idx = 0;
            for (int y = 0; y < hintedHeight; y++) {
                for (int x = 0; x < hintedWidth; x++) {
                    gray.getRaster().setSample(x, y, 0, bytes[idx] & 0xFF);
                    idx++;
                    if (idx >= bytes.length) {
                        return gray;
                    }
                }
            }
            return gray;
        }
        return null;
    }

    static final class SelectedImage {
        private final BufferedImage image;
        private final String decision;
        private final double confidence;

        private SelectedImage(BufferedImage image, String decision, double confidence) {
            this.image = image;
            this.decision = decision;
            this.confidence = confidence;
        }

        static SelectedImage decoded(BufferedImage image) {
            return new SelectedImage(image, "embedded-decoded", 1.0d);
        }

        static SelectedImage resource(BufferedImage image) {
            return new SelectedImage(image, "resource-reference", 0.9d);
        }

        static SelectedImage raw(BufferedImage image) {
            return new SelectedImage(image, "raw-fallback", 0.6d);
        }

        static SelectedImage unresolved() {
            return new SelectedImage(null, "unresolved", 0.0d);
        }

        BufferedImage image() {
            return image;
        }

        String decision() {
            return decision;
        }

        double confidence() {
            return confidence;
        }
    }
}
