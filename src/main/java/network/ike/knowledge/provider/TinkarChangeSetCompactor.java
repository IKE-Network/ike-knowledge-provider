/*
 * Copyright © 2026 IKE Network (support@ike.network)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package network.ike.knowledge.provider;

import dev.ikm.tinkar.common.service.CachingService;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.service.ServiceKeys;
import dev.ikm.tinkar.common.service.ServiceProperties;
import dev.ikm.tinkar.entity.changeset.ChangeSetCompaction;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import network.ike.knowledge.spi.ArtifactInput;
import network.ike.knowledge.spi.ChangeSetCompactor;
import network.ike.knowledge.spi.ChangeSetReport;
import network.ike.knowledge.spi.ChangeSetRequest;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code ike:changeset-compact} implementation over the entity module's {@link ChangeSetCompaction}.
 * With knowledge bases it loads them into a memory-only 64-bit store and names the pattern of
 * every reference from there, so the result loads into a store that mints by pattern with no
 * base; a reference none of them holds fails the compaction, naming it. With modules it keeps
 * only the versions stamped in them, as the merge does. Given a directory, it compacts every change
 * set in it into the target directory under the same name, the knowledge bases loaded once; a
 * file that is not a change set (an empty one, say) is skipped and named.
 */
public final class TinkarChangeSetCompactor implements ChangeSetCompactor {

    public TinkarChangeSetCompactor() {
    }

    /** Whether a file is a change set: a zip holding a manifest. An empty session file is not. */
    private static boolean isChangeSet(Path file) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file.toFile())) {
            return zip.getEntry("META-INF/MANIFEST.MF") != null;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public ChangeSetReport compact(ChangeSetRequest request) {
        File target = request.requireTarget("ike:changeset-compact").toFile();
        java.util.Set<java.util.UUID> modules = new java.util.HashSet<>();
        request.modules().forEach(module -> modules.add(java.util.UUID.fromString(module.strip())));
        if (request.knowledgeBases().isEmpty()) {
            ChangeSetCompaction.Result result = TinkarChangeSetTools.call(new ChangeSetCompaction(
                    request.changeSet().toFile(), target, null, modules));
            return TinkarChangeSetTools.report(true, result.text());
        }
        List<String> lines = new ArrayList<>();
        try {
            Path storeRoot = Files.createTempDirectory("ike-changeset-compact");
            CachingService.clearAll();
            ServiceProperties.set(ServiceKeys.DATA_STORE_ROOT, storeRoot.toFile());
            PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
            PrimitiveData.start();
            try {
                for (ArtifactInput base : request.knowledgeBases()) {
                    new LoadEntitiesFromProtobufFile(base.path().toFile()).compute();
                    lines.add("knowledge base " + base.coordinate().orElse(base.path().getFileName().toString())
                            + base.resolvedVersion().map(version -> " (" + version + ")").orElse(""));
                }
                if (!Files.isDirectory(request.changeSet())) {
                    ChangeSetCompaction.Result result = TinkarChangeSetTools.call(new ChangeSetCompaction(
                            request.changeSet().toFile(), target, ChangeSetCompaction.patternsFromStore(), modules));
                    if (!result.withoutPattern().isEmpty()) {
                        lines.add("error: " + result.withoutPattern().size()
                                + " reference(s) are in none of the knowledge bases; add the one that holds them: "
                                + result.withoutPattern());
                        return new ChangeSetReport(false, "Not compacted: references without a pattern remain", lines);
                    }
                    return new ChangeSetReport(true, result.text(), lines);
                }
                Files.createDirectories(target.toPath());
                int compacted = 0;
                int failed = 0;
                int skipped = 0;
                List<Path> changeSets;
                try (var list = Files.list(request.changeSet())) {
                    changeSets = list.filter(file -> file.getFileName().toString().endsWith(".zip")).sorted().toList();
                }
                for (Path changeSet : changeSets) {
                    if (!isChangeSet(changeSet)) {
                        skipped++;
                        continue;
                    }
                    try {
                        ChangeSetCompaction.Result result = TinkarChangeSetTools.call(new ChangeSetCompaction(
                                changeSet.toFile(), target.toPath().resolve(changeSet.getFileName()).toFile(),
                                ChangeSetCompaction.patternsFromStore(), modules));
                        if (result.withoutPattern().isEmpty()) {
                            compacted++;
                            lines.add(result.text());
                        } else {
                            failed++;
                            Files.deleteIfExists(target.toPath().resolve(changeSet.getFileName()));
                            lines.add("error: " + changeSet.getFileName() + ": " + result.withoutPattern().size()
                                    + " reference(s) are in none of the knowledge bases: " + result.withoutPattern());
                        }
                    } catch (RuntimeException e) {
                        failed++;
                        lines.add("error: " + changeSet.getFileName() + ": " + e.getMessage());
                    }
                }
                String summary = "Compacted " + compacted + " of " + (changeSets.size() - skipped) + " change set(s) into "
                        + target + (skipped == 0 ? "" : "; skipped " + skipped + " file(s) that are not change sets")
                        + (failed == 0 ? "" : "; " + failed + " failed");
                return new ChangeSetReport(failed == 0, summary, lines);
            } finally {
                PrimitiveData.stop();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Compacting " + request.changeSet() + " against its knowledge bases failed", e);
        }
    }
}
