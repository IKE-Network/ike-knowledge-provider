/**
 * The chronology-store implementation of the IKE knowledge-pipeline SPI. Consumers
 * depend on this stable IKE-named artifact (directly or via the parent POM's default
 * wiring); the engine libraries it composes keep names that are free to migrate
 * underneath it.
 */
module network.ike.knowledge.provider {
    requires network.ike.knowledge.spi;
    requires dev.ikm.tinkar.entity;
    requires dev.ikm.tinkar.common;
    requires dev.ikm.tinkar.terms;
    requires dev.ikm.tinkar.reasoner.service;
    requires org.eclipse.collections.api;
    requires org.slf4j;

    provides network.ike.knowledge.spi.KnowledgeExporter
            with network.ike.knowledge.provider.ChronologyStoreExporter;
    provides network.ike.knowledge.spi.KnowledgeBaseAssembler
            with network.ike.knowledge.provider.ChronologyStoreAssembler;
    provides network.ike.knowledge.spi.KnowledgeVerifier
            with network.ike.knowledge.provider.ChronologyStoreVerifier;
    provides network.ike.knowledge.spi.ChangeSetInspector
            with network.ike.knowledge.provider.TinkarChangeSetInspector;
    provides network.ike.knowledge.spi.ChangeSetVerifier
            with network.ike.knowledge.provider.TinkarChangeSetVerifier;
    provides network.ike.knowledge.spi.ChangeSetExpander
            with network.ike.knowledge.provider.TinkarChangeSetExpander;
    provides network.ike.knowledge.spi.ChangeSetCompactor
            with network.ike.knowledge.provider.TinkarChangeSetCompactor;
}
