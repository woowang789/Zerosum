package com.zerosum.inventory.repository;

import com.zerosum.inventory.domain.LocationId;
import com.zerosum.inventory.domain.LockedLocation;
import com.zerosum.inventory.domain.LockedLocations;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * location을 FOR SHARE로, id 오름차순으로 한 행씩 잠근다 (docs/04-write-path.md 잠금 규칙).
 * db/04-harness.sql의 tst_post ③과 동일하게, 하나의 다중 행 쿼리가 아니라 행마다 별도로 잠가
 * 잠금 획득 순서를 명시적으로 보장한다.
 */
@Repository
public class LocationLockRepository {

    private final JdbcClient jdbc;

    LocationLockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LockedLocations lockForShareOrderById(List<LocationId> locationIds) {
        List<Long> ids = locationIds.stream().map(LocationId::value).distinct().sorted().toList();
        List<LockedLocation> locked = new ArrayList<>();
        for (Long id : ids) {
            LockedLocation row = jdbc.sql("""
                    SELECT id, code, count_session_id FROM location WHERE id = :id FOR SHARE
                    """)
                    .param("id", id)
                    .query((rs, rowNum) -> new LockedLocation(
                            new LocationId(rs.getLong("id")),
                            rs.getString("code"),
                            (Long) rs.getObject("count_session_id")))
                    .single();
            locked.add(row);
        }
        return new LockedLocations(locked);
    }
}
