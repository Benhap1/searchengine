package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.*;
import searchengine.repository.*;
import searchengine.util.GlobalErrorsHandler;
import searchengine.util.LemmaFinder;
import searchengine.util.UrlNormalizer;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class IndexPageService {

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final LemmaFinder lemmaFinder;
    private final GlobalErrorsHandler globalErrorsHandler;
    private final SiteIndexingService siteIndexingService;
    private final UrlNormalizer urlNormalizer;

    @Transactional
    public Map<String, Object> processIndexPage(String url) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (url == null || url.trim().isEmpty()) {
                response.put("result", false);
                response.put("error", "Пустой поисковый запрос");
                return response;
            }

            boolean result = indexPage(url);
            response.put("result", result);
            if (!result) {
                response.put("error", "Данная страница находится за пределами сайтов, " +
                        "указанных в конфигурационном файле");
            }
        } catch (MalformedURLException e) {
            response.put("result", false);
            response.put("error", "Неправильный формат URL");
        } catch (Exception e) {
            response.put("result", false);
            response.put("error", e.getMessage());
        }
        return response;
    }

    @Transactional
    protected boolean indexPage(String url) throws MalformedURLException {
        String normalizedUrl = urlNormalizer.normalizeUrl(url);
        URL parsedUrl = new URL(normalizedUrl);
        String host = parsedUrl.getHost();
        Optional<SiteEntity> optionalSiteEntity = siteRepository.findByUrlContaining(host);
        if (optionalSiteEntity.isEmpty()) {
            return false;
        }

        SiteEntity siteEntity = optionalSiteEntity.get();
        String path = parsedUrl.getPath();
        Optional<PageEntity> existingPage = pageRepository.findBySiteAndPath(siteEntity, path);

        if (existingPage.isPresent()) {
            processPage(existingPage.get(), normalizedUrl, true);
        } else {
            PageEntity pageEntity = new PageEntity();
            pageEntity.setSite(siteEntity);
            pageEntity.setPath(path);
            processPage(pageEntity, normalizedUrl, false);
        }
        return true;
    }

    @Transactional
    protected void processPage(PageEntity pageEntity, String normalizedUrl, boolean isUpdate) {
        try {
            Document document = Jsoup.connect(normalizedUrl).get();
            pageEntity.setContent(document.outerHtml());

            int statusCode = Jsoup.connect(normalizedUrl).execute().statusCode();
            pageEntity.setCode(statusCode);

            pageRepository.save(pageEntity);

            deleteOldIndicesAndAdjustLemmas(pageEntity);
            Map<String, Integer> lemmas = lemmaFinder.collectLemmas(pageEntity.getContent());
            siteIndexingService.saveLemmasAndIndices(pageEntity.getSite(), pageEntity, lemmas);

            String logMessage = isUpdate ? "обновлена" : "индексирована";
            log.info("Страница {} успешно {}", normalizedUrl, logMessage);
        } catch (IOException e) {
            String errorMessage = isUpdate ? "обновлении" : "индексации";
            handleIndexingError("Ошибка при " + errorMessage + " страницы " + normalizedUrl, e);
        }
    }

    @Transactional
    protected void deleteOldIndicesAndAdjustLemmas(PageEntity pageEntity) {
        List<IndexEntity> oldIndices = indexRepository.findByPage(pageEntity);

        List<Long> indexIds = oldIndices.stream()
                .map(IndexEntity::getId)
                .collect(Collectors.toList());

        if (!indexIds.isEmpty()) {
            indexRepository.deleteAllByIdInBatch(indexIds);
            log.info("Удаление {} старых индексов", oldIndices.size());
        }

        oldIndices.forEach(index -> {
            LemmaEntity lemma = index.getLemma();
            int newFrequency = lemma.getFrequency() - index.getRank().intValue();
            lemma.setFrequency(newFrequency);
            lemmaRepository.save(lemma);
        });

        log.info("Обновление частот для {} лемм", oldIndices.size());
    }

    private void handleIndexingError(String message, Exception e) {
        String errorMessage = String.format("%s: %s", message, e.getMessage());
        globalErrorsHandler.addError(errorMessage);
        log.error(errorMessage);
    }
}



