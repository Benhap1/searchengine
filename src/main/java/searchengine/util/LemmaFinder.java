package searchengine.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.english.EnglishLuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.*;

@Service
@Slf4j
public class LemmaFinder {
    private LuceneMorphology russianMorphology;
    private LuceneMorphology englishMorphology;
    private static final Set<String> RUSSIAN_PARTICLES = Set.of("МЕЖД", "ПРЕДЛ", "СОЮЗ", "ЧАСТ");
    private static final Set<String> ENGLISH_PARTICLES = Set.of("IN", "CC", "DT", "RP");

    @PostConstruct
    public void init() {
        try {
            this.russianMorphology = new RussianLuceneMorphology();
            this.englishMorphology = new EnglishLuceneMorphology();
        } catch (IOException e) {
            throw new RuntimeException("Ошибка при инициализации LuceneMorphology", e);
        }
    }

    public Map<String, Integer> collectLemmas(String text) {
        String[] words = preprocessText(text);
        Map<String, Integer> lemmas = new HashMap<>();

        for (String word : words) {
            if (word.isBlank() || isValidWord(word)) {
                continue;
            }

            List<String> wordBaseForms = getMorphInfo(word);
            if (anyWordBaseBelongToParticle(wordBaseForms)) {
                continue;
            }

            List<String> normalForms = getNormalForms(word);
            if (normalForms.isEmpty()) {
                continue;
            }

            String normalWord = normalForms.get(0);
            lemmas.merge(normalWord, 1, Integer::sum);
        }

        return lemmas;
    }

    public Set<String> getLemmaSet(String text) {
        String[] words = preprocessText(text);
        Set<String> lemmaSet = new HashSet<>();

        for (String word : words) {
            if (word.isBlank() || isValidWord(word)) {
                continue;
            }

            List<String> wordBaseForms = getMorphInfo(word);
            if (anyWordBaseBelongToParticle(wordBaseForms)) {
                continue;
            }

            lemmaSet.addAll(getNormalForms(word));
        }

        return lemmaSet;
    }

    private String[] preprocessText(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[^а-яА-Яa-zA-Z\\s]", "")
                .split("\\s+");
    }

    private List<String> getMorphInfo(String word) {
        if (isRussianWord(word)) {
            return getRussianMorphInfo(word);
        } else if (isEnglishWord(word)) {
            return getEnglishMorphInfo(word);
        }
        return Collections.emptyList();
    }

    private List<String> getRussianMorphInfo(String word) {
        try {
            return russianMorphology.getMorphInfo(word);
        } catch (Exception e) {
            log.error("Ошибка при обработке русского слова: " + word, e);
        }
        return Collections.emptyList();
    }

    private List<String> getEnglishMorphInfo(String word) {
        try {
            return englishMorphology.getMorphInfo(word);
        } catch (Exception e) {
            log.error("Ошибка при обработке английского слова: " + word, e);
        }
        return Collections.emptyList();
    }

    private List<String> getNormalForms(String word) {
        if (isRussianWord(word)) {
            return getRussianNormalForms(word);
        } else if (isEnglishWord(word)) {
            return getEnglishNormalForms(word);
        }
        return Collections.emptyList();
    }

    private List<String> getRussianNormalForms(String word) {
        try {
            return russianMorphology.getNormalForms(word);
        } catch (Exception e) {
            log.error("Ошибка при получении нормальных форм русского слова: " + word, e);
        }
        return Collections.emptyList();
    }

    private List<String> getEnglishNormalForms(String word) {
        try {
            return englishMorphology.getNormalForms(word);
        } catch (Exception e) {
            log.error("Ошибка при получении нормальных форм английского слова: " + word, e);
        }
        return Collections.emptyList();
    }

    private boolean isRussianWord(String word) {
        return word.chars().anyMatch(c -> Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC);
    }

    private boolean isEnglishWord(String word) {
        return word.chars().anyMatch(c -> Character.UnicodeBlock.of(c) == Character.UnicodeBlock.BASIC_LATIN);
    }

    private boolean anyWordBaseBelongToParticle(List<String> wordBaseForms) {
        return wordBaseForms.stream().anyMatch(this::hasParticleProperty);
    }

    private boolean hasParticleProperty(String wordBase) {
        return RUSSIAN_PARTICLES.stream().anyMatch(wordBase.toUpperCase()::contains) ||
                ENGLISH_PARTICLES.stream().anyMatch(wordBase.toUpperCase()::contains);
    }

    private boolean isValidWord(String word) {
        boolean hasCyrillic = word.chars().anyMatch(c -> Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC);
        boolean hasLatin = word.chars().anyMatch(c -> Character.UnicodeBlock.of(c) == Character.UnicodeBlock.BASIC_LATIN);
        return hasCyrillic && hasLatin;
    }
}