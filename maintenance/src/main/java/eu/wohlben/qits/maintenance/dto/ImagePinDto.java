package eu.wohlben.qits.maintenance.dto;

/**
 * One external image tag the platform's manifests still pin — an entry of {@code GET
 * /pins/images}, the keep-set qits-mirror evicts its mirrored tags against (qits-2253).
 *
 * @param name the image as the scan stored it: the mirror's path without the registry host, such
 *     as {@code hub/library/postgres} or {@code quay/quarkus/ubi9-quarkus-mandrel-builder-image},
 *     or a bare {@code node} where a Dockerfile names one
 * @param version the tag
 */
public record ImagePinDto(String name, String version) {}
