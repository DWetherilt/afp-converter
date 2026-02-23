package solutions.pointzero.symphony.afp.engine;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;

class ImageResolutionServiceTest {

    private final ImageResolutionService service = new ImageResolutionService();

    @Test
    void prefersDecodedThenResourceThenRaw() {
        BufferedImage decoded = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        BufferedImage resource = new BufferedImage(3, 1, BufferedImage.TYPE_INT_RGB);
        byte[] rawRaster = new byte[] {(byte) 0xFF};

        ImageResolutionService.SelectedImage selectedDecoded = service.selectForImageOp(
            List.of(decoded),
            List.of(resource),
            List.of(rawRaster),
            0,
            8,
            1
        );
        assertSame(decoded, selectedDecoded.image());
        assertEquals("embedded-decoded", selectedDecoded.decision());
        assertEquals(1.0d, selectedDecoded.confidence());

        ImageResolutionService.SelectedImage selectedResource = service.selectForImageOp(
            Arrays.asList((BufferedImage) null),
            List.of(resource),
            List.of(rawRaster),
            0,
            8,
            1
        );
        assertSame(resource, selectedResource.image());
        assertEquals("resource-reference", selectedResource.decision());
        assertEquals(0.9d, selectedResource.confidence());

        ImageResolutionService.SelectedImage selectedRaw = service.selectForImageOp(
            Arrays.asList((BufferedImage) null),
            Arrays.asList((BufferedImage) null),
            List.of(rawRaster),
            0,
            8,
            1
        );
        assertNotNull(selectedRaw.image());
        assertEquals("raw-fallback", selectedRaw.decision());
        assertEquals(0.6d, selectedRaw.confidence());
    }

    @Test
    void imageOpBindingDoesNotUseNonIndexedResourceFallback() {
        BufferedImage resourceAtZero = new BufferedImage(3, 1, BufferedImage.TYPE_INT_RGB);
        byte[] rawRaster = new byte[] {(byte) 0xFF};

        ImageResolutionService.SelectedImage selected = service.selectForImageOp(
            Arrays.asList((BufferedImage) null, (BufferedImage) null),
            List.of(resourceAtZero),
            Arrays.asList(null, rawRaster),
            1,
            8,
            1
        );

        assertNotNull(selected.image(), "expected raw fallback to be used for index 1");
        assertEquals("raw-fallback", selected.decision());

        ImageResolutionService.SelectedImage unresolved = service.selectForImageOp(
            Arrays.asList((BufferedImage) null, (BufferedImage) null),
            List.of(resourceAtZero),
            Arrays.asList(null, null),
            1,
            8,
            1
        );
        assertNull(unresolved.image());
        assertEquals("unresolved", unresolved.decision());
    }
}
