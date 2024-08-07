package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.Site;
import searchengine.model.*;
import searchengine.repository.IndexRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.SiteRepository;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import searchengine.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class SiteIndexingService {

    private final SiteRepository siteRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final LemmaFinder lemmaFinder;
    private final GlobalErrorsHandler globalErrorsHandler;
    private final CacheManagement cacheManagement;
    private final UrlNormalizer urlNormalizer;
    private final UrlCheckerIsInternalOrNot urlCheckerIsInternalOrNot;
    private final PageEntityCreator pageEntityCreator;
    private final SiteStatusUpdateService siteStatusUpdateService;
    protected volatile boolean stopRequested = false;
    private boolean indexingInProgress = false;
    private final Lock indexingLock = new ReentrantLock();
    private final Lock saveLock = new ReentrantLock();

    private final DatabaseInitializer databaseInitializer;

    @Value("${indexing-settings.fork-join-pool.parallelism}")
    private int parallelism;

    private static final Set<String> FILE_EXTENSIONS = Set.of(
            ".pdf", ".png", ".jpg", ".doc", ".docx", ".xls", ".xlsx",
            ".ppt", ".pptx", ".txt", ".rtf", ".jpeg", ".gif", ".bmp",
            ".tiff", ".svg", ".webp", ".mp4", ".avi", ".mkv", ".mov",
            ".wmv", ".flv", ".mp3", ".wav", ".aac", ".flac", ".ogg",
            ".zip", ".rar", ".7z", ".tar", ".gz", ".exe", ".dmg",
            ".iso", ".apk", ".sql"
    );


public String startIndexing(List<Site> sites) {
    new Thread(() -> {
        String response = "{\"result\": true}";
        log.info("Response to client: {}", response);
    }).start();

    new Thread(() -> {
        try {
            indexingLock.lock();
            try {
                if (indexingInProgress) {
                    // Если индексация уже запущена, просто завершить выполнение
                    return;
                }
                indexingInProgress = true;
                cacheManagement.clearCache();
                databaseInitializer.initializeDatabase();
            } finally {
                indexingLock.unlock();
            }

            indexSites(sites);

        } catch (Exception e) {
            log.error("Ошибка при запуске индексации: " + e.getMessage(), e);
        } finally {
            indexingLock.lock();
            try {
                indexingInProgress = false;
            } finally {
                indexingLock.unlock();
            }
        }
    }).start();

    return "{\"result\": true}";
}


    public String stopIndex() {
        indexingLock.lock();
        try {
            if (!indexingInProgress) {
                return "{\"result\": false, \"error\": \"Индексация не запущена\"}";
            }
            stopRequested = true;
            indexingInProgress = false;
        } finally {
            indexingLock.unlock();
        }
        return "{\"result\": true}";
    }


    public void indexSites(List<Site> sites) {
        log.info("Начало индексации сайтов: {}", sites);
        List<String> clearedErrors = globalErrorsHandler.getAllErrorsAndClear();
        if (!clearedErrors.isEmpty()) {
            log.info("Ошибки, очищенные перед началом индексации: {}", clearedErrors);
        }
        indexingLock.lock();
        try {
            stopRequested = false;
        } finally {
            indexingLock.unlock();
        }
        ForkJoinPool forkJoinPool = new ForkJoinPool(parallelism);
        try {
            forkJoinPool.submit(() -> sites.parallelStream().forEach(this::indexSite));
            forkJoinPool.shutdown();
            awaitForkJoinPoolTermination(forkJoinPool);
        } catch (InterruptedException e) {
            handleInterruptedException(e);
        } finally {
            finalizeIndexing(forkJoinPool);
        }

        logErrorsAfterIndexing();
        cacheManagement.clearCache();
        log.info("Конец индексации сайтов: {}", sites);
    }


    private void awaitForkJoinPoolTermination(ForkJoinPool forkJoinPool) throws InterruptedException {
        boolean terminated = forkJoinPool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        if (!terminated) {
            globalErrorsHandler.addError("ForkJoinPool не завершился в указанный срок");
        }
        if (stopRequested) {
            globalErrorsHandler.addError("Индексация была остановлена пользователем.");
        }
    }


    private void handleInterruptedException(InterruptedException e) {
        globalErrorsHandler.addError("Ошибка при ожидании завершения индексации сайтов: " + e.getMessage());
        Thread.currentThread().interrupt();
    }


    private void finalizeIndexing(ForkJoinPool forkJoinPool) {
        if (!forkJoinPool.isTerminated()) {
            globalErrorsHandler.addError("Принудительное завершение незавершенных задач в ForkJoinPool");
            forkJoinPool.shutdownNow();
        }
        indexingLock.lock();
        try {
            indexingInProgress = false;
        } finally {
            indexingLock.unlock();
        }
    }


    private void logErrorsAfterIndexing() {
        List<String> errorsAfterIndexing = globalErrorsHandler.getErrors();
        if (!errorsAfterIndexing.isEmpty()) {
            log.info("Ошибки, возникшие во время индексации: {}", errorsAfterIndexing);
        }
    }


    @Transactional
    protected void indexSite(Site site) {
        log.info("Начало индексации сайта: {}", site.getUrl());
        boolean hasError = false;
        String errorMessage = null;
        try {
            initializeSiteIndexing(site);
            SiteEntity indexedSite = getSiteEntity(site);
            indexPages(site.getUrl(), indexedSite);
        } catch (Exception e) {
            hasError = true;
            errorMessage = "Ошибка при индексации сайта " + site.getUrl() + ": " + e.getMessage();
            globalErrorsHandler.addError(errorMessage);
            log.error(errorMessage, e);
        } finally {
            updateSiteStatus(site, hasError, errorMessage);
        }
        log.info("Завершение индексации сайта: {}", site.getUrl());
    }


    private void initializeSiteIndexing(Site site) {
        log.info("Установка статуса индексации для сайта: {}", site.getUrl());
        saveSiteEntity(site);
        cacheManagement.clearCache();
    }


    private void saveSiteEntity(Site site) {
        SiteEntity indexedSite = new SiteEntity();
        indexedSite.setUrl(site.getUrl());
        indexedSite.setName(site.getName());
        indexedSite.setStatus(SiteStatus.INDEXING.name());
        indexedSite.setStatusTime(LocalDateTime.now());
        try {
            siteRepository.save(indexedSite);
            log.info("Создана запись для сайта: {}", site.getUrl());
        } catch (Exception e) {
            log.error("Ошибка при создании записи для сайта {}: {}", site.getUrl(), e.getMessage());
        }
        log.info("Статус индексации для сайта установлен: {}", site.getUrl());
    }


    private SiteEntity getSiteEntity(Site site) {
        return siteRepository.findByUrl(site.getUrl())
                .orElseThrow(() -> new RuntimeException("Не удалось получить запись для сайта: " + site.getUrl()));
    }


    private void updateSiteStatus(Site site, boolean hasError, String errorMessage) {
        SiteEntity indexedSite = getSiteEntity(site);
        siteStatusUpdateService.updateSiteStatus(indexedSite, stopRequested, hasError, errorMessage);
    }


    private void indexPages(String baseUrl, SiteEntity indexedSite) {
        if (stopRequested) return;
        log.info("Начало индексации страниц сайта: {}", baseUrl);
        try {
            Document document = Jsoup.connect(baseUrl).get();
            String baseUri = document.baseUri();
            ConcurrentHashMap<String, Boolean> visitedUrls = new ConcurrentHashMap<>();
            visitPage(document, baseUri, indexedSite, visitedUrls);
        } catch (IOException e) {
            String errorMessage = "Ошибка при индексации страниц сайта " + baseUrl + ": " + e.getMessage();
            globalErrorsHandler.addError(errorMessage);
            log.error(errorMessage, e);
        } finally {
            log.info("Конец индексации страниц сайта: {}", baseUrl);
        }
    }


    public void visitPage(Document document, String url, SiteEntity siteEntity, ConcurrentHashMap<String, Boolean> visitedUrls) {
        if (stopRequested) return;

        String normalizedUrl = urlNormalizer.normalizeUrl(url);
        log.info("Начало обработки страницы: {}", normalizedUrl);
        visitedUrls.putIfAbsent(normalizedUrl, true);

        if (isPageProcessed(normalizedUrl)) return;

        if (isFileUrl(normalizedUrl)) {
            log.info("Пропуск файла: {}", normalizedUrl);
            return;
        }

        PageEntity pageEntity = pageEntityCreator.createPageEntity(document, normalizedUrl, siteEntity);
        if (pageEntity == null) {
            log.error("Не удалось создать запись для страницы: {}", normalizedUrl);
            return;
        }

        Map<String, Integer> lemmas = lemmaFinder.collectLemmas(pageEntity.getContent());
        saveLemmasAndIndices(siteEntity, pageEntity, lemmas);
        extractLinksAndIndexPages(document, siteEntity, visitedUrls);

        log.info("Завершение обработки страницы: {}", normalizedUrl);
    }


    private boolean isPageProcessed(String normalizedUrl) {
        if (cacheManagement.pageUrlCache.getIfPresent(normalizedUrl) != null) {
            log.info("Страница уже была обработана: {}", normalizedUrl);
            return true;
        }
        cacheManagement.pageUrlCache.put(normalizedUrl, true);
        return false;
    }


    private boolean isFileUrl(String url) {
        try {
            URL parsedUrl = new URL(url);
            if (UnsupportedProtocols.PROTOCOLS.contains(parsedUrl.getProtocol().toLowerCase())) {
                return true;
            }
        } catch (MalformedURLException e) {
            return true;
        }
        return FILE_EXTENSIONS.stream().anyMatch(url::endsWith);
    }


    private void extractLinksAndIndexPages(Document document, SiteEntity siteEntity, ConcurrentHashMap<String, Boolean> visitedUrls) {
        if (stopRequested) return;
        log.info("Начало извлечения ссылок и индексации страниц: {}", document.baseUri());

        Elements links = document.select("a[href]");
        ForkJoinPool forkJoinPool = new ForkJoinPool(parallelism);
        try {
            links.forEach(link -> processLink(link, siteEntity, visitedUrls, forkJoinPool));
            forkJoinPool.shutdown();
            awaitForkJoinPoolTermination(forkJoinPool);
        } catch (InterruptedException e) {
            handleInterruptedException(e);
        } finally {
            finalizeForkJoinPool(forkJoinPool);
        }

        log.info("Завершение извлечения ссылок и индексации страниц: {}", document.baseUri());
    }


    private void processLink(Element link, SiteEntity siteEntity, ConcurrentHashMap<String, Boolean> visitedUrls, ForkJoinPool forkJoinPool) {
        String nextUrl = link.absUrl("href");
        String normalizedNextUrl = urlNormalizer.normalizeUrl(nextUrl);

        if (isFileUrl(normalizedNextUrl)) {
            log.info("Пропуск файла: {}", normalizedNextUrl);
            return;
        }

        if (visitedUrls.putIfAbsent(normalizedNextUrl, true) == null && urlCheckerIsInternalOrNot.isInternalLink(normalizedNextUrl, siteEntity.getUrl())) {
            forkJoinPool.submit(() -> fetchAndVisitPage(normalizedNextUrl, siteEntity, visitedUrls));
        }
    }


    private void fetchAndVisitPage(String normalizedNextUrl, SiteEntity siteEntity, ConcurrentHashMap<String, Boolean> visitedUrls) {
        if (stopRequested) {
            log.info("Индексация остановлена пользователем.");
            return;
        }
        try {
            Document nextDocument = Jsoup.connect(normalizedNextUrl).get();
            if (stopRequested) {
                log.info("Индексация остановлена пользователем.");
                return;
            }
            visitPage(nextDocument, normalizedNextUrl, siteEntity, visitedUrls);
        } catch (IOException e) {
            String errorMessage = "Ошибка при получении страницы " + normalizedNextUrl + ": " + e.getMessage();
            globalErrorsHandler.addError(errorMessage);
            log.error(errorMessage);
        }
    }


    private void finalizeForkJoinPool(ForkJoinPool forkJoinPool) {
        if (!forkJoinPool.isTerminated()) {
            log.warn("Принудительное завершение незавершенных задач в ForkJoinPool");
            forkJoinPool.shutdownNow();
        }
    }


    protected void saveLemmasAndIndices(SiteEntity siteEntity, PageEntity pageEntity, Map<String, Integer> lemmas) {
        try {
            saveLock.lock();
            if (stopRequested) return;

            Map<String, LemmaEntity> lemmaMap = new HashMap<>();
            List<LemmaEntity> newLemmaEntities = new ArrayList<>();
            List<IndexEntity> indexEntities = new ArrayList<>();

            lemmas.forEach((lemma, frequency) -> processLemma(lemma, frequency, siteEntity, lemmaMap, newLemmaEntities));
            lemmaRepository.saveAll(newLemmaEntities);
            updateCacheWithNewLemmas(newLemmaEntities);

            lemmas.forEach((lemma, frequency) -> createIndexEntity(pageEntity, lemmaMap.get(lemma), frequency, indexEntities));
            indexRepository.saveAll(indexEntities);
        } finally {
            saveLock.unlock();
        }
    }


    private void processLemma(String lemma, Integer frequency, SiteEntity siteEntity, Map<String, LemmaEntity> lemmaMap, List<LemmaEntity> newLemmaEntities) {
        LemmaEntity cachedLemmaEntity = cacheManagement.getLemmaFromCache(lemma);
        if (cachedLemmaEntity == null) {
            LemmaEntity lemmaEntity = new LemmaEntity();
            lemmaEntity.setLemma(lemma);
            lemmaEntity.setSite(siteEntity);
            lemmaEntity.setFrequency(frequency);
            newLemmaEntities.add(lemmaEntity);
            lemmaMap.put(lemma, lemmaEntity);
        } else {
            cachedLemmaEntity.setFrequency(cachedLemmaEntity.getFrequency() + frequency);
            lemmaMap.put(lemma, cachedLemmaEntity);
        }
    }


    private void updateCacheWithNewLemmas(List<LemmaEntity> newLemmaEntities) {
        newLemmaEntities.forEach(lemmaEntity -> cacheManagement.putLemmaInCache(lemmaEntity.getLemma(), lemmaEntity));
    }


    private void createIndexEntity(PageEntity pageEntity, LemmaEntity lemmaEntity, Integer frequency, List<IndexEntity> indexEntities) {
        IndexEntity indexEntity = new IndexEntity();
        indexEntity.setPage(pageEntity);
        indexEntity.setLemma(lemmaEntity);
        indexEntity.setRank(frequency.floatValue());
        indexEntities.add(indexEntity);
    }
}


