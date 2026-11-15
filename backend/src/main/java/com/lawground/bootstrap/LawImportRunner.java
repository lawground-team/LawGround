package com.lawground.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lawground.law.dto.LawImport;
import com.lawground.law.search.LiveLawIndexService;
import com.lawground.law.service.LawImportService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Explicit local-only loader: reads reviewed {@code *.import.json} files (LawImport), imports each
 * law version, then indexes its chunks through the existing AI client. No public endpoint; enabled
 * only by {@code --lawground.law-import.dir=...} on the isolated local loopback profile (same guard
 * as AiConnectionSmoke).
 *
 * <p>Re-running is safe: an identical import is reused and already-indexed chunks are skipped. A
 * chunk that fails to index (for example a very long annex hitting the embed timeout) is reported
 * at the end instead of stopping the load.
 */
@Component
@ConditionalOnProperty(name = "lawground.law-import.dir")
public class LawImportRunner implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(LawImportRunner.class);
    private final LawImportService importer;
    private final LiveLawIndexService indexer;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final Path dir;
    private final boolean index;

    public LawImportRunner(
            LawImportService importer,
            LiveLawIndexService indexer,
            ObjectMapper mapper,
            JdbcTemplate jdbc,
            Environment environment,
            @Value("${lawground.law-import.dir}") String dir,
            @Value("${lawground.law-import.index:true}") boolean index) {
        validateEnvironment(environment);
        this.importer = importer;
        this.indexer = indexer;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.dir = Path.of(dir);
        this.index = index;
    }

    static void validateEnvironment(Environment environment) {
        String address = environment.getProperty("server.address");
        if (!environment.matchesProfiles("local & !prod & !test")
                || address == null
                || !Set.of("127.0.0.1", "localhost", "::1").contains(address)) {
            throw new IllegalStateException(
                    "Law import requires the isolated local loopback profile");
        }
    }

    static List<String> spanLabels(LawImport input) {
        List<String> labels = new ArrayList<>();
        for (var article : input.articles()) {
            for (int i = 0; i < article.spans().size(); i++) labels.add(article.articleKey());
        }
        return labels;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<Path> files;
        try (Stream<Path> listing = Files.list(dir)) {
            files =
                    listing.filter(p -> p.getFileName().toString().endsWith(".import.json"))
                            .sorted()
                            .toList();
        }
        if (files.isEmpty()) throw new IllegalStateException("No *.import.json files in " + dir);
        List<String> failures = new ArrayList<>();
        Set<UUID> lawIds = new LinkedHashSet<>();
        int chunks = 0;
        int indexedNow = 0;
        int alreadyIndexed = 0;
        for (Path file : files) {
            LawImport input = mapper.readValue(file.toFile(), LawImport.class);
            var imported = importer.importVersion(input);
            UUID lawId =
                    jdbc.queryForObject(
                            "SELECT law_id FROM law_versions WHERE id=?",
                            UUID.class,
                            imported.versionId());
            lawIds.add(lawId);
            List<UUID> chunkIds = imported.chunkIds();
            LOG.info(
                    "LAW_IMPORT title={} versionKey={} lawId={} versionId={} articles={} chunks={} reusedImport={}",
                    input.title(),
                    input.sourceVersionKey(),
                    lawId,
                    imported.versionId(),
                    imported.articleIds().size(),
                    chunkIds.size(),
                    imported.reused());
            if (!index) continue;
            List<String> labels = spanLabels(input);
            for (int i = 0; i < chunkIds.size(); i++) {
                chunks++;
                try {
                    var outcome = indexer.indexChunk(chunkIds.get(i));
                    if (outcome == LiveLawIndexService.Outcome.INDEXED) indexedNow++;
                    else alreadyIndexed++;
                } catch (RuntimeException failure) {
                    String label = labels.size() == chunkIds.size() ? labels.get(i) : "#" + i;
                    failures.add(
                            input.title()
                                    + " "
                                    + label
                                    + " -> "
                                    + failure.getClass().getSimpleName()
                                    + ": "
                                    + failure.getMessage());
                }
                if ((i + 1) % 20 == 0 || i + 1 == chunkIds.size()) {
                    LOG.info(
                            "LAW_INDEX_PROGRESS title={} {}/{}",
                            input.title(),
                            i + 1,
                            chunkIds.size());
                }
            }
        }
        LOG.info(
                "LAW_IMPORT_DONE laws={} chunks={} indexedNow={} alreadyIndexed={} failed={}",
                lawIds.size(),
                chunks,
                indexedNow,
                alreadyIndexed,
                failures.size());
        for (String failure : failures) LOG.warn("LAW_INDEX_FAILED {}", failure);
        LOG.info(
                "LAWGROUND_ANALYSIS_LIVE_LAW_IDS={}",
                String.join(",", lawIds.stream().map(UUID::toString).toList()));
    }
}
