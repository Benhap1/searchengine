package searchengine.repository;

import io.micrometer.common.lang.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import searchengine.model.SiteEntity;
import java.util.List;
import java.util.Optional;

@Repository
public interface SiteRepository extends JpaRepository<SiteEntity, Long> {


    Optional<SiteEntity> findByUrl(String url);


    Optional<SiteEntity> findByUrlContaining(String host);

    @NonNull
    List<SiteEntity> findAll();


}

