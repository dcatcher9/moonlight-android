package com.limelight.utils;

import static org.junit.Assert.assertArrayEquals;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

public class ClientSbsModelArchiveTest {
    @Test
    public void exactFullModelEntryIsStreamedAndHashed() throws Exception {
        byte[] model = new byte[] {1, 2, 3, 4, 5};
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("other.model", new byte[] {9, 8, 7});
        entries.put("models/target.model", new byte[] {6, 6, 6});
        entries.put("target.model", model);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        ClientSbsModelAssetCache.extractArchivedModel(
                new ByteArrayInputStream(createArchive(entries)),
                "target.model", output, digest);

        assertArrayEquals(model, output.toByteArray());
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(model), digest.digest());
    }

    @Test(expected = IOException.class)
    public void missingFullModelEntryIsRejected() throws Exception {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("other.model", new byte[] {1});

        ClientSbsModelAssetCache.extractArchivedModel(
                new ByteArrayInputStream(createArchive(entries)),
                "target.model", new ByteArrayOutputStream(),
                MessageDigest.getInstance("SHA-256"));
    }

    private static byte[] createArchive(LinkedHashMap<String, byte[]> entries)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (XZCompressorOutputStream compressed = new XZCompressorOutputStream(bytes);
             TarArchiveOutputStream archive = new TarArchiveOutputStream(compressed)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                TarArchiveEntry archiveEntry = new TarArchiveEntry(entry.getKey());
                archiveEntry.setSize(entry.getValue().length);
                archive.putArchiveEntry(archiveEntry);
                archive.write(entry.getValue());
                archive.closeArchiveEntry();
            }
            archive.finish();
        }
        return bytes.toByteArray();
    }
}
