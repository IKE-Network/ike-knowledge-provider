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
import dev.ikm.tinkar.common.service.EntityCountSummary;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.service.ServiceKeys;
import dev.ikm.tinkar.common.service.ServiceProperties;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.aggregator.DefaultEntityAggregator;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.entity.transform.EntityToTinkarSchemaTransformer;
import network.ike.knowledge.spi.ArtifactInput;
import network.ike.knowledge.spi.Finding;
import network.ike.knowledge.spi.KnowledgeVerifier;
import network.ike.knowledge.spi.VerifyReport;
import network.ike.knowledge.spi.VerifyRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The chronology-store {@link KnowledgeVerifier}: assembles a pristine throwaway store
 * from the declared base plus the artifact under verification and runs the requested
 * checks against it — never against the store that produced the artifact
 * (IKE-Network/ike-issues#852, #951).
 *
 * <p>Implemented checks:
 * <ul>
 *   <li>{@link VerifyRequest.Check#PRESENCE} — the artifact loads into the pristine
 *       store with its own manifest verification and count-equality check intact: the
 *       exact gate a consumer's import runs, moved into the build.</li>
 *   <li>{@link VerifyRequest.Check#FIT_REFERENCES} — every loaded entity survives the
 *       protobuf transform, which resolves every reference it serializes: a reference
 *       to a component with no entity and no resolvable public id is reported with the
 *       carrying entity named.</li>
 * </ul>
 * Any other requested check fails closed with an {@link Finding.Severity#ERROR}: a
 * check this provider cannot run must never read as a check that passed.
 */
public final class ChronologyStoreVerifier implements KnowledgeVerifier {

    /** The cap on per-entity findings for one check, so a systemic defect reports as a
     * bounded list plus a remainder count rather than an unbounded flood. */
    private static final int FINDING_CAP = 50;

    /** Creates the provider (ServiceLoader requirement). */
    public ChronologyStoreVerifier() {
    }

    @Override
    public VerifyReport verify(VerifyRequest request) {
        List<Finding> findings = new ArrayList<>();
        try {
            Path storeRoot = Files.createTempDirectory("ike-verify");
            try {
                CachingService.clearAll();
                ServiceProperties.set(ServiceKeys.DATA_STORE_ROOT, storeRoot.toFile());
                PrimitiveData.selectControllerByName("Open SpinedArrayStore");
                PrimitiveData.start();
                try {
                    loadBase(request.baseInputs(), storeRoot);
                    boolean loaded = checkPresence(request, findings);
                    if (loaded && request.checks().contains(VerifyRequest.Check.FIT_REFERENCES)) {
                        checkFitReferences(findings);
                    }
                    for (VerifyRequest.Check check : request.checks()) {
                        if (check != VerifyRequest.Check.PRESENCE
                                && check != VerifyRequest.Check.FIT_REFERENCES) {
                            findings.add(new Finding(Finding.Severity.ERROR, check,
                                    request.artifact().getFileName().toString(),
                                    "Check not implemented by " + getClass().getSimpleName()
                                            + " — failing closed rather than reading as passed"));
                        }
                    }
                } finally {
                    PrimitiveData.stop();
                }
            } finally {
                ChronologyStoreAssembler.deleteRecursively(storeRoot);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Knowledge verification failed to manage its store", e);
        }
        return new VerifyReport(findings);
    }

    /**
     * Loads the declared base into the pristine store, in order: an optional store
     * seed first, then entity loads inside the bulk-load bracket.
     *
     * @param baseInputs the base inputs, in load order
     * @param storeRoot  the pristine store root
     * @throws IOException if a store seed cannot be unzipped
     */
    private static void loadBase(List<ArtifactInput> baseInputs, Path storeRoot) throws IOException {
        if (baseInputs.isEmpty()) {
            return;
        }
        int firstLoad = 0;
        if (baseInputs.getFirst().role() == ArtifactInput.Role.STORE_SEED) {
            ChronologyStoreAssembler.unzipInto(baseInputs.getFirst().path(), storeRoot);
            firstLoad = 1;
        }
        EntityService.get().beginLoadPhase();
        try {
            for (int i = firstLoad; i < baseInputs.size(); i++) {
                new LoadEntitiesFromProtobufFile(baseInputs.get(i).path().toFile()).compute();
            }
        } finally {
            EntityService.get().endLoadPhase();
        }
    }

    /**
     * Loads the artifact under verification with the loader's own manifest and
     * count-equality gates intact.
     *
     * @param request  the verification request
     * @param findings the findings to append to
     * @return whether the artifact loaded, so dependent checks know to run
     */
    private static boolean checkPresence(VerifyRequest request, List<Finding> findings) {
        String artifactName = request.artifact().getFileName().toString();
        EntityService.get().beginLoadPhase();
        try {
            EntityCountSummary loaded =
                    new LoadEntitiesFromProtobufFile(request.artifact().toFile()).compute();
            findings.add(new Finding(Finding.Severity.INFO, VerifyRequest.Check.PRESENCE,
                    artifactName,
                    "Loaded " + loaded.getTotalCount() + " entities ("
                            + loaded.conceptCount() + " concepts, " + loaded.semanticCount()
                            + " semantics, " + loaded.patternCount() + " patterns, "
                            + loaded.stampCount() + " stamps); manifest counts exact"));
            return true;
        } catch (RuntimeException e) {
            findings.add(new Finding(Finding.Severity.ERROR, VerifyRequest.Check.PRESENCE,
                    artifactName, "Artifact failed to load into a pristine store: " + e));
            return false;
        } finally {
            EntityService.get().endLoadPhase();
        }
    }

    /**
     * Sweeps every entity in the assembled store through the protobuf transform — the
     * serialization that resolves every reference — reporting each entity whose
     * references do not fit, capped with a remainder count.
     *
     * @param findings the findings to append to
     */
    private static void checkFitReferences(List<Finding> findings) {
        EntityToTinkarSchemaTransformer transformer = EntityToTinkarSchemaTransformer.getInstance();
        List<Finding> failures = new ArrayList<>();
        long[] overflow = {0};
        new DefaultEntityAggregator().aggregateEntities(entity -> {
            try {
                transformer.transform(entity);
            } catch (RuntimeException e) {
                if (failures.size() < FINDING_CAP) {
                    failures.add(new Finding(Finding.Severity.ERROR,
                            VerifyRequest.Check.FIT_REFERENCES,
                            entity.getClass().getSimpleName() + " " + entity.publicId(),
                            "Reference does not resolve within the artifact plus declared base: " + e));
                } else {
                    overflow[0]++;
                }
            }
        });
        findings.addAll(failures);
        if (overflow[0] > 0) {
            findings.add(new Finding(Finding.Severity.ERROR, VerifyRequest.Check.FIT_REFERENCES,
                    "(aggregate)", overflow[0] + " further entities with unresolvable references"
                    + " beyond the first " + FINDING_CAP));
        }
    }
}
