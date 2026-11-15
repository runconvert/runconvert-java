package com.runconvert;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Live tests against the real API: mvn test -Plive with RUNCONVERT_API_KEY set (about 2 credits). */
class LiveTest {
    @BeforeEach void needKey() {
        String k = System.getenv("RUNCONVERT_API_KEY");
        Assumptions.assumeTrue(k != null && !k.isEmpty(), "set RUNCONVERT_API_KEY to run live tests");
    }

    @Test void meAndOperations() {
        assertTrue(RunConvert.builder().build().me().has("credits"));
        RunConvert rc = RunConvert.builder().apiKey("").build();
        boolean pdf = false;
        for (var o : rc.operations().forInput("TXT").path("outputs")) pdf |= "pdf".equals(o.path("format").asText());
        assertTrue(pdf);
        assertTrue(rc.operations().forPair("mp4", "mp3").path("engines").size() > 0);
    }

    @Test void convertTextToPdf(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("sdk-live-test.txt");
        java.nio.file.Files.writeString(src, "RunConvert Java SDK live test\nLine two\n\nCafé – 你好 – Привет\n");
        Set<String> seen = new LinkedHashSet<>();
        RunConvert.ConvertResult res = RunConvert.builder().build().convert(src,
                RunConvert.ConvertOptions.to("pdf").outDir(dir.resolve("out")).tag("sdk-java-live-test").timeout(Duration.ofSeconds(150)).onUpdate(j -> seen.add(j.status)));
        byte[] pdf = java.nio.file.Files.readAllBytes(res.saved.get(0));
        assertEquals("%PDF-", new String(pdf, 0, 5));
        System.out.println("  job " + res.job.id + ": " + String.join(" → ", seen) + ", " + res.job.credits_used + " credit(s), " + pdf.length + " bytes");
    }

    @Test void badKey() {
        RunConvertException e = assertThrows(RunConvertException.class, () -> RunConvert.builder().apiKey("rc_live_invalid").maxRetries(0).build().me());
        assertEquals(401, e.getStatus());
    }
}
