package searchengine.repository;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import searchengine.model.LemmaEntity;
import searchengine.model.SiteEntity;
import java.util.List;


@Repository
public interface LemmaRepository extends JpaRepository<LemmaEntity, Long> {

    @Cacheable(value = "lemmaCountCache")
    long count();

    @Cacheable(value = "lemmaCountBySiteCache")
    int countBySite(SiteEntity site);

    @Cacheable(value = "lemmaCountByLemmaCache")
    int countByLemma(String lemma);

    List<LemmaEntity> findByLemmaIn(List<String> lemmas);


}
