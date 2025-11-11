package com.example.transfers_service.repository;

import com.example.transfers_service.entity.Movement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface MovementRepository extends JpaRepository<Movement, String> {
    // luego aquí podemos poner:
    // List<Movement> findByTransferId(String transferId);
    @Query(value = "SELECT LPAD(\n" +
            "         IFNULL(MAX(CAST(RIGHT(movement_id, 8) AS UNSIGNED)), 0) + 1,\n" +
            "         8,\n" +
            "         '0'\n" +
            "       )\n" +
            "FROM movements;", nativeQuery = true)
    String nextMovementId();
}
