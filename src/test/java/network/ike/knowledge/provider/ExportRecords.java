package network.ike.knowledge.provider;

import dev.ikm.tinkar.entity.changeset.ChangeSetFormat;
import dev.ikm.tinkar.schema.TinkarMsg;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;

/** Reads every record of an export, in the order its manifest lists its record entries. */
final class ExportRecords {

    private ExportRecords() {
    }

    /**
     * The records of an export.
     *
     * @param export the export file
     * @return its records
     * @throws IOException if the file cannot be read
     */
    static List<TinkarMsg> read(Path export) throws IOException {
        List<TinkarMsg> records = new ArrayList<>();
        try (ZipFile zip = new ZipFile(export.toFile())) {
            Manifest manifest = ChangeSetFormat.manifest(zip).orElseGet(Manifest::new);
            for (ChangeSetFormat.RecordEntry entry : ChangeSetFormat.recordEntries(zip, manifest)) {
                try (InputStream in = ChangeSetFormat.openRecords(zip, entry.entry())) {
                    TinkarMsg message = TinkarMsg.parseDelimitedFrom(in);
                    while (message != null) {
                        records.add(message);
                        message = TinkarMsg.parseDelimitedFrom(in);
                    }
                }
            }
        }
        return records;
    }
}
