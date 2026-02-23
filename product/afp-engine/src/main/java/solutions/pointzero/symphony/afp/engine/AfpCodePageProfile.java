package solutions.pointzero.symphony.afp.engine;

import java.nio.charset.Charset;
import java.util.List;

record AfpCodePageProfile(Charset sbcsCharset,
                          Charset dbcsCharset,
                          String source,
                          List<String> codePageMappingMatrix,
                          int mixedRunChunkCount,
                          int hintedChunkCount) {
    AfpCodePageProfile(Charset sbcsCharset, Charset dbcsCharset, String source) {
        this(sbcsCharset, dbcsCharset, source, List.of(), 0, 0);
    }
}
