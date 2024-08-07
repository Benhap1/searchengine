package searchengine.repository;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import searchengine.model.PageEntity;
import searchengine.model.SiteEntity;
import java.util.List;
import java.util.Optional;

@Repository
public interface PageRepository extends JpaRepository<PageEntity, Long> {

    @Cacheable(value = "pageCountCache")
    long count();

    @Cacheable(value = "pageCountBySiteCache")
    int countBySite(SiteEntity site);

    @Cacheable(value = "pageBySiteAndPathCache", key = "#siteEntity.id + '-' + #path")
    Optional<PageEntity> findBySiteAndPath(SiteEntity siteEntity, String path);


    @Query("SELECT DISTINCT p FROM PageEntity p " +
            "JOIN FETCH p.site s " +
            "JOIN FETCH IndexEntity i ON p.id = i.page.id " +
            "JOIN FETCH LemmaEntity l ON i.lemma.id = l.id " +
            "WHERE l.lemma IN :lemmas AND p.site.url = :site " +
            "GROUP BY p.id HAVING COUNT(DISTINCT l.lemma) = :lemmasSize")
    List<PageEntity> findPagesByLemmasAndSite(@Param("lemmas") List<String> lemmas,
                                              @Param("site") String site,
                                              @Param("lemmasSize") long lemmasSize);



}
