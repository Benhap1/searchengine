package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import searchengine.model.IndexEntity;
import searchengine.model.PageEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;


@Repository
public interface IndexRepository extends JpaRepository<IndexEntity, Long> {


    @Query("SELECT ie FROM IndexEntity ie WHERE ie.page IN :pages AND ie.lemma.lemma IN :lemmas")
    List<IndexEntity> findByPagesAndLemmas(@Param("pages") List<PageEntity> pages, @Param("lemmas") List<String> lemmas);


    @Query("SELECT i FROM IndexEntity i JOIN FETCH i.page p JOIN FETCH i.lemma l WHERE p = :page")
    List<IndexEntity> findByPage(@Param("page") PageEntity page);



    @Modifying
    @Query("DELETE FROM IndexEntity i WHERE i.id IN :ids")
    void deleteAllByIdInBatch(@Param("ids") List<Long> ids);

}


