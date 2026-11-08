package eu.wohlben.qits.maintenance.entity;

import eu.wohlben.qits.db.exposure.Technical;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * <b>One pact a consumer repository declares against a provider repository</b> (V31, qits-1952) —
 * a key of {@code contracts.pacts} in the consumer's {@code .config/qits/release.yml}, read by the
 * consumer's scan and replaced wholesale with it.
 *
 * <p>What it is for: {@code pending/PactAdoptions} offers the provider the FIRST pin of {@code
 * eu.wohlben.qits:<consumer>-pacts-<provider>}, which no pin move ever could. An edge the consumer
 * no longer declares is deleted by its next scan and so is never adopted.
 */
@Technical(
    reason =
        "A scan-derived index of the contracts.pacts keys consumers' release.yml declare, read only"
            + " by PactAdoptions to offer a provider its first pin; the release.yml is the record.")
@Entity
@Table(name = "mt_pact_edge")
public class MtPactEdge extends PanacheEntityBase {

  @Id public UUID id;

  /** The repository whose release.yml declares the pact, and whose release publishes the jar. */
  @Column(name = "consumer_repository", nullable = false, length = 255)
  public String consumerRepository;

  /** The provider repository, verbatim off the consumer's {@code contracts.pacts} key. */
  @Column(name = "provider_repository", nullable = false, length = 255)
  public String providerRepository;
}
