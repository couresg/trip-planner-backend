package com.tripplanner.place.repository;

import com.tripplanner.place.domain.Place;
import com.tripplanner.place.domain.PlaceCategory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlaceRepository extends JpaRepository<Place, Long> {

    /**
     * 도시의 장소를 카테고리·이름으로 검색한다. 각 조건은 null이면 무시한다.
     * namePattern은 LIKE 패턴이며 이스케이프 문자는 '!'이다.
     */
    @Query("""
            select p from Place p
            where p.city.id = :cityId
              and (:category is null or p.category = :category)
              and (:namePattern is null or p.nameKo like :namePattern escape '!')
            order by p.id
            """)
    List<Place> search(@Param("cityId") Long cityId, @Param("category") PlaceCategory category,
            @Param("namePattern") String namePattern);
}
