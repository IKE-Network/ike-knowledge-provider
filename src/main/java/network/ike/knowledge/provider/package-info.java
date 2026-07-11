/**
 * Chronology-store providers for the IKE knowledge-pipeline SPI
 * ({@code network.ike.knowledge.spi}). Each provider is SPI adaptation and
 * orchestration only — the engine-generic capabilities (ledger builders, loaders,
 * exporters, aggregators) live in the engine's own libraries, and this package is the
 * boundary where the engine's naming is absorbed: when those libraries migrate into
 * IKE, this artifact's dependencies change and its identity does not.
 *
 * <p>Providers register through both {@code module-info} {@code provides} clauses and
 * classpath {@code META-INF/services} entries — the forked seam runs classpath-mode.
 *
 * @see <a href="https://github.com/IKE-Network/ike-issues/issues/850">IKE-Network/ike-issues#850</a>
 */
package network.ike.knowledge.provider;
